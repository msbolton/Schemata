package io.schemata.importer.xsd

import io.schemata.importer.ImportCodes
import io.schemata.importer.ImportNames
import io.schemata.importer.Imported
import io.schemata.importer.SchemataUnit
import io.schemata.importer.UnitAnnotation
import io.schemata.importer.UnitDecl
import io.schemata.importer.UnitField
import io.schemata.importer.UnitRecord
import io.schemata.importer.UnitType
import io.schemata.importer.UnitUnion
import io.schemata.importer.xsd.XsdImport.Claim
import io.schemata.importer.xsd.XsdImport.ClaimKind
import io.schemata.importer.xsd.XsdImport.Cycles
import io.schemata.importer.xsd.XsdImport.ELEMENT_REF_PREFIX
import io.schemata.importer.xsd.XsdImport.PLACEHOLDER
import io.schemata.importer.xsd.XsdImport.PendingName
import io.schemata.importer.xsd.XsdImport.diagnostic
import io.schemata.importer.xsd.XsdImport.dropped
import io.schemata.importer.xsd.XsdImport.enumFacets
import io.schemata.importer.xsd.XsdImport.fieldNameFor
import io.schemata.importer.xsd.XsdImport.isMapUnique
import io.schemata.importer.xsd.XsdImport.renamed
import io.schemata.lang.Diagnostic
import io.schemata.lang.DiagnosticCode
import io.schemata.lang.Span
import io.schemata.target.Names
import java.util.Collections
import java.util.IdentityHashMap

/**
 * A type's resolved Schemata name, the `@xsd(name)` annotation it needs (when its name can be
 * fixed), and a lossy note when it can't be: [TypeNote.Unfixable] when the XSD name doesn't end in
 * `Type` at all (no override can ever reproduce it), [TypeNote.Blocked] when an override exists in
 * principle but would regenerate a type name another type in the namespace already owns.
 */
internal data class TypeNameInfo(
    val finalName: String,
    val annotation: UnitAnnotation?,
    val note: TypeNote? = null,
)

internal sealed interface TypeNote {
    data class Unfixable(val regeneratedType: String) : TypeNote

    data class Blocked(val other: String) : TypeNote
}

/**
 * Lowers a resolved set of [XsdDoc]s (includes already merged, every referenced namespace present)
 * into [SchemataUnit]s: namespace naming and imports, records with attributes, lists, maps, roots,
 * documentation, and the full type- and field-naming rules, including unions from choice-only
 * complex types, enums from enumerated simple types, inheritance, groups, inline choices, and every
 * dropped construct.
 */
object XsdImport {
    fun lower(docs: List<XsdDoc>, namespaceOverride: String?): Imported {
        val diagnostics = mutableListOf<Diagnostic>()
        val inherited: MutableSet<Diagnostic> = Collections.newSetFromMap(IdentityHashMap())
        val names = LinkedHashMap<XsdDoc, String>()
        val claimed = mutableMapOf<String, XsdDoc>()
        val live = mutableListOf<XsdDoc>()

        docs.forEachIndexed { index, doc ->
            // Two documents claiming one foreign namespace without including each other would
            // declare it twice; the first is kept and the second dropped. A urn:schemata: one is
            // caught below, as a namespace name collision.
            val tn = doc.targetNamespace
            val sameUri =
                live.firstOrNull {
                    tn != null && schemataName(tn) == null && it.targetNamespace == tn
                }
            if (sameUri != null) {
                diagnostics +=
                    dropped(
                        doc.path,
                        1,
                        doc.path,
                        "namespace '$tn' is also declared by ${sameUri.path}; dropped",
                    )
                return@forEachIndexed
            }
            val name =
                if (index == 0 && namespaceOverride != null) namespaceOverride
                else deriveNamespaceName(doc, diagnostics)
            val existing = claimed[name]
            if (existing != null) {
                diagnostics +=
                    unresolved(
                        doc.path,
                        1,
                        "${existing.path} and ${doc.path} both lower to schema '$name'",
                    )
                return@forEachIndexed
            }
            claimed[name] = doc
            names[doc] = name
            live += doc
        }

        val docsByNamespace = LinkedHashMap<String?, XsdDoc>()
        live.forEach { docsByNamespace.putIfAbsent(it.targetNamespace, it) }
        val isEnum = { st: XSimpleType -> enumFacets(st, docsByNamespace) != null }

        // Type names are resolved once for the whole document set, per namespace, before any field
        // is lowered, so a cross-document reference always sees the final name. Only enumerated
        // simple types (the only simple types that ever become declarations) claim a name here; a
        // plain restriction, inlined at each use, never competes for one.
        val typeNames = mutableMapOf<QName, TypeNameInfo>()
        live.forEach { doc ->
            val originals =
                doc.complexTypes.mapNotNull { it.name } +
                    doc.simpleTypes.mapNotNull { st -> st.name?.takeIf { isEnum(st) } }
            resolveNamespaceTypeNames(originals).forEach { (original, info) ->
                typeNames[QName(doc.targetNamespace, original)] = info
            }
        }
        val cycles = Cycles(cyclicGroups(docsByNamespace), cyclicAttributeGroups(docsByNamespace))
        val heads = Heads(live)
        val propertyElements = elementRefs(live)
        val elementRecords = ElementRecords()

        val units =
            live.map { doc ->
                val imports = mutableListOf<String>()
                doc.imports.forEach { imp ->
                    val target = docsByNamespace[imp.namespace]
                    if (target != null && target !== doc) imports += names.getValue(target)
                }
                doc.dropped.forEach { (construct, line) ->
                    diagnostics += dropped(doc.path, line, "schema", "$construct dropped")
                }
                blockOrFinal(doc)?.let { (path, line) ->
                    diagnostics += dropped(path, line, "schema", "block and final dropped")
                }
                // Every top-level declaration name this namespace's named types already own, so a
                // record lowered from a global element (or hoisted out of a union) can't take one.
                val topLevelNames = mutableMapOf<String, String>()
                doc.complexTypes.forEach { ct ->
                    val original = ct.name ?: return@forEach
                    val info = typeNames.getValue(QName(doc.targetNamespace, original))
                    topLevelNames.putIfAbsent(info.finalName, "complex type '$original'")
                }
                doc.simpleTypes.forEach { st ->
                    val original = st.name?.takeIf { isEnum(st) } ?: return@forEach
                    val info = typeNames.getValue(QName(doc.targetNamespace, original))
                    topLevelNames.putIfAbsent(info.finalName, "simple type '$original'")
                }
                val context =
                    ImportContext(
                        doc,
                        docsByNamespace,
                        names,
                        typeNames,
                        topLevelNames,
                        cycles,
                        heads,
                        diagnostics,
                        doc.unresolvedImports,
                        inherited,
                        propertyElements,
                        elementRecords,
                    )
                context.simpleTypes = SimpleTypes(context)
                context.choiceLowering = ChoiceLowering(context)
                context.complexLowering = ComplexLowering(context)
                val lowering = NamespaceLowering(context)
                val declarations = mutableListOf<UnitDecl>()
                // Complex types and enumerated simple types are declared on one combined list,
                // ordered by source line: the xsd target interleaves a nested record's and a nested
                // enum's flattened types as it encounters them, so matching that order here is what
                // lets a re-exported xsd come out byte for byte the same as the one that was read.
                (doc.complexTypes.map { it.line to lowering.declaration(it) } +
                        doc.simpleTypes
                            .filter { it.name != null && isEnum(it) }
                            .map { it.line to listOf(context.simpleTypes.enumDeclaration(it)) })
                    .sortedBy { it.first }
                    .forEach { (_, decls) -> declarations += decls }
                val roots = mutableSetOf<QName>()
                doc.elements.forEach { el ->
                    if (el.ref == null && el.type == null && el.inlineComplex != null) {
                        declarations += lowering.topLevelRecord(el)
                    } else {
                        lowering.checkRoot(el, roots)
                    }
                }
                declarations += context.choiceLowering.headUnions()
                SchemataUnit(
                    namespace = names.getValue(doc),
                    annotations =
                        listOfNotNull(
                            doc.targetNamespace
                                ?.takeIf { it != "urn:schemata:${names.getValue(doc)}" }
                                ?.let { UnitAnnotation("xsd", "namespace", "\"$it\"") },
                            // The xsd target writes qualified elements and unqualified attributes
                            // unless told otherwise; XSD's own default for both is unqualified.
                            elementForm(doc)
                                .takeIf { it != "qualified" }
                                ?.let { UnitAnnotation("xsd", "element_form", "\"$it\"") },
                            attributeForm(doc)
                                .takeIf { it != "unqualified" }
                                ?.let { UnitAnnotation("xsd", "attribute_form", "\"$it\"") },
                        ),
                    doc = doc.doc,
                    imports = (imports + context.extraImports).distinct(),
                    declarations = declarations,
                    sourcePath = doc.path,
                )
            }
        // Every namespace's elements have claimed their record names by now, so a reference to one,
        // from its own namespace or another, can name the record it was declared under.
        val resolved =
            units.map { unit ->
                unit.copy(declarations = unit.declarations.map(elementRecords::resolve))
            }
        // A note on a component many types share (an attribute flattened into every derived
        // type) is reported once. The note reported while lowering the declaring type wins over
        // a copy made while flattening it into a derived type, since only it names the right
        // type; among equals the first wins, and the survivors keep their emission order.
        val survivors =
            diagnostics
                .withIndex()
                .sortedBy { it.value in inherited }
                .distinctBy {
                    Triple(
                        it.value.code.id,
                        it.value.span?.let { s -> s.file to s.startLine },
                        it.value.message.substringAfter(": "),
                    )
                }
                .sortedBy { it.index }
                .map { it.value }
        return Imported(resolved, survivors)
    }

    /**
     * The file and line where [doc] first restricts derivation or substitution (`block`, `final`,
     * or their schema-wide defaults), which Schemata has no way to say; `null` when it never does.
     * A default counts from the top of the document.
     */
    private fun blockOrFinal(doc: XsdDoc): Pair<String, Int>? {
        if (doc.blockDefault != null || doc.finalDefault != null) return doc.path to 1
        val sites =
            doc.complexTypes
                .filter { it.block != null || it.final != null }
                .map { it.path to it.line } +
                doc.elements
                    .filter { it.block != null || it.final != null }
                    .map { it.path to it.line }
        return sites
            .minByOrNull { it.second }
            ?.let { (path, line) -> path.ifEmpty { doc.path } to line }
    }

    /**
     * [doc]'s namespace name: the `urn:schemata:` suffix of its own `targetNamespace` when it has
     * one that is dotted lower_snake segments; otherwise, one derived from its file name (fixed
     * into a valid segment when the raw stem isn't one), reported every time, since nothing in the
     * xsd chose it — the name only exists because this file happened to be called what it was
     * called.
     */
    private fun deriveNamespaceName(doc: XsdDoc, diagnostics: MutableList<Diagnostic>): String {
        val tn = doc.targetNamespace
        if (tn != null)
            schemataName(tn)?.let {
                return it
            }
        val rawStem = rawStem(doc.path)
        val name =
            if (ImportNames.isNamespaceSegment(rawStem)) rawStem
            else ImportNames.namespaceStem(doc.path)
        diagnostics += renamed(doc.path, 1, "schema name '$name' was derived from the file name")
        return name
    }

    private const val URN = "urn:schemata:"

    /**
     * How a record's mixed-content field, attribute-wildcard field, and wildcards read in a report.
     */
    internal const val MIXED_CONTENT = "mixed content"
    internal const val ANY_ATTRIBUTE = "xs:anyAttribute"
    internal const val ANY_ELEMENT = "xs:any"

    /**
     * Begins the placeholder a synthesised field holds as its name until its record's named fields
     * have claimed theirs; the NUL cannot begin any real name.
     */
    internal const val PLACEHOLDER = "\u0000"

    /**
     * Begins the placeholder a reference to a global element's record holds until every namespace's
     * elements have claimed their record names; see [ElementRecords].
     */
    internal const val ELEMENT_REF_PREFIX = "\u0000element:"

    /** [doc]'s effective `elementFormDefault`: `unqualified` when it does not say. */
    internal fun elementForm(doc: XsdDoc): String = doc.elementFormDefault ?: "unqualified"

    /** [doc]'s effective `attributeFormDefault`: `unqualified` when it does not say. */
    internal fun attributeForm(doc: XsdDoc): String = doc.attributeFormDefault ?: "unqualified"

    /**
     * The Schemata namespace a `urn:schemata:<name>` URI names, or `null` when [uri] is any other
     * URI or `<name>` is not dotted lower_snake segments, which could not be written as a
     * `namespace` declaration and so is treated like any other URI.
     */
    private fun schemataName(uri: String): String? =
        uri.takeIf { it.startsWith(URN) }
            ?.removePrefix(URN)
            ?.takeIf { name -> name.split('.').all(ImportNames::isNamespaceSegment) }

    /** Groups and attribute groups whose own content reaches back to themselves. */
    internal data class Cycles(val groups: Set<QName>, val attributeGroups: Set<QName>)

    /**
     * Every group that reaches itself: through a nested group reference, directly or inside an
     * element's anonymous type. Expanding one in place would never end. An anonymous type's
     * extension base is not followed: lowering it inside a type it extends is cut where it recurs
     * (the inline element then names the base's record), so a group on that path ends too.
     */
    private fun cyclicGroups(docsByNamespace: Map<String?, XsdDoc>): Set<QName> {
        val groups = mutableMapOf<QName, XContent>()
        docsByNamespace.values.forEach { d ->
            d.groups.forEach { groups[QName(d.targetNamespace, it.name)] = it.content }
        }
        // The groups [content] references, in it or in the anonymous types of its elements.
        fun edges(content: XContent): Set<QName> {
            fun particles(ps: List<XParticle>): Set<QName> =
                ps.flatMap { p ->
                        when (p) {
                            is XParticle.GroupRef -> setOf(p.ref)
                            is XParticle.Nested -> edges(p.content)
                            is XParticle.Element ->
                                p.element.inlineComplex?.let { edges(it.content) } ?: emptySet()
                            is XParticle.Any -> emptySet()
                        }
                    }
                    .toSet()
            return when (content) {
                is XContent.Sequence -> particles(content.particles)
                is XContent.Choice -> particles(content.particles)
                is XContent.All -> particles(content.particles)
                is XContent.Extension -> particles(content.particles)
                is XContent.Restriction -> particles(content.particles)
                XContent.Empty -> emptySet()
            }
        }
        fun reachesItself(start: QName): Boolean {
            val seen = mutableSetOf<QName>()
            val queue = ArrayDeque(edges(groups.getValue(start)))
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                if (node == start) return true
                if (!seen.add(node)) continue
                queue += edges(groups[node] ?: continue)
            }
            return false
        }
        return groups.keys.filter(::reachesItself).toSet()
    }

    /** Every attribute group whose own references reach back to itself. */
    private fun cyclicAttributeGroups(docsByNamespace: Map<String?, XsdDoc>): Set<QName> {
        val refs = mutableMapOf<QName, List<QName>>()
        docsByNamespace.values.forEach { d ->
            d.attributeGroups.forEach { g ->
                refs[QName(d.targetNamespace, g.name)] =
                    g.attributes.filterIsInstance<XAttributeUse.GroupRef>().map { it.ref }
            }
        }
        fun reachesItself(start: QName): Boolean {
            val seen = mutableSetOf<QName>()
            val queue = ArrayDeque(refs.getValue(start))
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                if (node == start) return true
                if (seen.add(node)) queue += refs[node].orEmpty()
            }
            return false
        }
        return refs.keys.filter(::reachesItself).toSet()
    }

    private fun rawStem(path: String): String {
        val fileName = path.substringAfterLast('/').substringAfterLast('\\')
        return fileName.substringBeforeLast('.', fileName)
    }

    /**
     * One namespace's named complex and simple types, in declaration order, resolved to their final
     * Schemata names: a type whose own XSD name already equals its candidate claims that name first
     * (so `Order`, needing no fix, always keeps `Order`); everything else falls back to its own
     * full name, UpperCamel-cased, when its candidate is already taken (so a colliding `OrderType`
     * keeps `OrderType`, not `Order`). Then, for each type: no override when its default
     * regeneration already reproduces the XSD name; [TypeNote.Unfixable] when the XSD name has no
     * `Type` suffix to give back; an `@xsd(name)` override when stripping `Type` would fix it and
     * that override's regenerated name is free; [TypeNote.Blocked] when it isn't (some other type's
     * own default regeneration already claims it).
     */
    private fun resolveNamespaceTypeNames(originals: List<String>): Map<String, TypeNameInfo> {
        data class Candidate(val original: String, val hasTypeSuffix: Boolean, val name: String)
        val candidates =
            originals.distinct().map {
                val (name, _) = XsdNames.typeOverride(it)
                Candidate(it, it.length > 4 && it.endsWith("Type"), name)
            }

        val claimed = mutableSetOf<String>()
        val finalName = mutableMapOf<String, String>()
        val (exact, transformed) = candidates.partition { it.original == it.name }
        exact.forEach { c ->
            finalName[c.original] = c.name
            claimed += c.name
        }
        transformed.forEach { c ->
            val chosen = if (c.name !in claimed) c.name else ImportNames.upperCamel(c.original)
            finalName[c.original] = chosen
            claimed += chosen
        }

        // Every "no Type suffix" type's regenerated name is fixed regardless of anything else, and
        // is the only thing a fixable type's own override could ever collide with (its override
        // always regenerates its own XSD name exactly, and XSD type names are already unique).
        val unfixableRegenerated = mutableMapOf<String, String>()
        candidates
            .filterNot { it.hasTypeSuffix }
            .forEach { c ->
                unfixableRegenerated[finalName.getValue(c.original) + "Type"] = c.original
            }

        val result = mutableMapOf<String, TypeNameInfo>()
        candidates.forEach { c ->
            val n = finalName.getValue(c.original)
            result[c.original] =
                when {
                    c.original == n + "Type" -> TypeNameInfo(n, null)
                    !c.hasTypeSuffix -> TypeNameInfo(n, null, TypeNote.Unfixable(n + "Type"))
                    else -> {
                        val blockedBy = unfixableRegenerated[c.original]
                        if (blockedBy != null) TypeNameInfo(n, null, TypeNote.Blocked(blockedBy))
                        else {
                            val override = c.original.removeSuffix("Type")
                            TypeNameInfo(n, UnitAnnotation("xsd", "name", "\"$override\""))
                        }
                    }
                }
        }
        return result
    }

    /**
     * [original]'s fixed Schemata identifier, the `@xsd(name)` override that regenerates [original]
     * exactly (`full-name` → `full_name` with `@xsd(name: "full-name")`), and, when [original] is
     * not even a valid XML name (a bare enumeration value may start with a digit, as `2d` does), no
     * override at all: the xsd target rejects one that isn't a valid name, so offering it would
     * only trade one way of failing to round trip for another. A valid identifier is kept as-is.
     */
    internal fun fieldNameFor(original: String): FieldName {
        if (ImportNames.isLowerSnake(original)) return FieldName(original, null, unfixable = false)
        val fixed = ImportNames.lowerSnake(original)
        return if (XsdNames.isValidOverride(original)) {
            FieldName(fixed, UnitAnnotation("xsd", "name", "\"$original\""), unfixable = false)
        } else {
            FieldName(fixed, null, unfixable = true)
        }
    }

    /** What took a field name in a record, which decides whether a second claimant clashes. */
    internal enum class ClaimKind(val word: String) {
        ELEMENT("element"),
        ATTRIBUTE("attribute"),
        GROUP("group"),
        CHOICE("choice"),
        FIELD("field"),
        MIXED_TEXT("mixed text"),
        WILDCARD("wildcard"),
        WILDCARD_ATTRIBUTE("wildcard attribute"),
    }

    /** A field name's claimant: its [kind], and its [construct] as a report names it. */
    internal data class Claim(val kind: ClaimKind, val construct: String)

    /**
     * A field whose synthesised name waits for its record's named fields: the [kind] and
     * [construct] its claim reports, and where.
     */
    internal data class PendingName(
        val kind: ClaimKind,
        val construct: String,
        val whereCollision: String,
        val line: Int,
        val path: String,
    )

    /**
     * An enumeration value with its signs spelled out, so that values told apart only by them stay
     * apart: `+` is always `plus`, and `-` is `minus` unless it sits between two letters or digits,
     * where it only separates words (`paid-out` stays `paid_out`). `+x-y` gives `plus_x_y`, `-x-y`
     * gives `minus_x_y`.
     */
    internal fun spellSigns(value: String): String = buildString {
        value.forEachIndexed { i, c ->
            val between =
                i > 0 &&
                    i < value.length - 1 &&
                    value[i - 1].isLetterOrDigit() &&
                    value[i + 1].isLetterOrDigit()
            when {
                c == '+' -> append("_plus_")
                c == '-' && !between -> append("_minus_")
                else -> append(c)
            }
        }
    }

    internal data class FieldName(
        val name: String,
        val annotation: UnitAnnotation?,
        val unfixable: Boolean,
    )

    internal fun listRefinements(min: Int, max: Int?): List<Pair<String, String>> =
        listOfNotNull(
            if (min != 0) "min" to min.toString() else null,
            max?.let { "max" to it.toString() },
        )

    /**
     * The values of [st] when it is enumerated, and so becomes a declaration (an enum); `null` for
     * a plain restriction, list, or union, which is inlined at each use and never claims a type
     * name of its own. A union is enumerated when every member is, named or inline, a union of
     * enumerations among them: its values are all of theirs in member order, each kept once.
     */
    internal fun enumFacets(
        st: XSimpleType,
        docsByNamespace: Map<String?, XsdDoc>,
        visiting: Set<QName> = emptySet(),
    ): List<XFacet>? =
        when (val v = st.variety) {
            is XVariety.Restriction ->
                v.facets.filter { it.name == "enumeration" }.takeIf { it.isNotEmpty() }
            is XVariety.ListOf -> null
            is XVariety.Union -> {
                val named =
                    v.memberTypes.map { q ->
                        val member =
                            docsByNamespace[q.namespace]?.simpleTypes?.firstOrNull {
                                it.name == q.local
                            }
                        if (member == null || q in visiting) return null
                        enumFacets(member, docsByNamespace, visiting + q) ?: return null
                    }
                val inline =
                    v.inlineMembers.map { enumFacets(it, docsByNamespace, visiting) ?: return null }
                (named + inline).flatten().distinctBy { it.value }.takeIf { it.isNotEmpty() }
            }
        }

    /** A diagnostic at [line] of [path], with the standard help text for [code]. */
    internal fun diagnostic(code: DiagnosticCode, path: String, line: Int, message: String) =
        Diagnostic(
            code,
            message,
            Span(path, line.coerceAtLeast(1), 1, line.coerceAtLeast(1), 1),
            ImportCodes.helpFor(code),
        )

    internal fun unresolved(path: String, line: Int, message: String) =
        diagnostic(ImportCodes.UNRESOLVED, path, line, "$path: $message")

    internal fun renamed(path: String, line: Int, message: String) =
        diagnostic(ImportCodes.RENAMED, path, line, "$path: $message")

    internal fun dropped(path: String, line: Int, where: String, tail: String) =
        diagnostic(ImportCodes.DROPPED, path, line, "$where: $tail")

    /** The `xs:unique` the xsd target writes on a map wrapper: one `@key` field over `entry`. */
    internal fun isMapUnique(u: XUnique): Boolean =
        u.fields == listOf("@key") && u.selector.substringAfterLast(':') == "entry"

    /**
     * A recognised map-entry shape: a wrapper element holding a repeated `entry`, itself extending
     * [valueType] (or wrapping it in a `value` child when it carries its own refinements) and
     * carrying [keyAttribute].
     */
    internal data class EntryShape(
        val keyAttribute: XAttribute,
        val valueType: UnitType,
        val entry: XElement,
    )

    internal sealed interface MapResult {
        data class AsMap(val type: UnitType.MapOf) : MapResult

        data class Fallback(
            val entryFields: List<UnitField>,
            val entryDoc: String?,
            val listRefinements: List<Pair<String, String>>,
            val nullableElement: Boolean,
        ) : MapResult

        data object NotAMap : MapResult
    }

    /**
     * Lowers one [XsdDoc]'s declarations, mirroring the XSD target's per-file lowering: the type
     * names and their `@xsd(name)` overrides are already resolved (shared across the whole document
     * set), so this only decides fields, nesting, roots, unions, enums, inheritance, groups, inline
     * choices, and the lossy notes that go with them.
     */
    private class NamespaceLowering(private val context: ImportContext) {
        /** A named complex type: a record, or, when its content is a bare choice, a union. */
        fun declaration(ct: XComplexType): List<UnitDecl> =
            context.at(ct.path) { declarationAt(ct) }

        private fun declarationAt(ct: XComplexType): List<UnitDecl> {
            val original = ct.name ?: return emptyList()
            val info = context.typeNames.getValue(QName(context.doc.targetNamespace, original))
            when (val note = info.note) {
                is TypeNote.Unfixable ->
                    context.diagnostics +=
                        context.lossy(
                            ImportCodes.APPROXIMATED,
                            "complex type '$original'",
                            "complex type '$original' has no Schemata equivalent; the regenerated " +
                                "type will be named '${note.regeneratedType}'",
                            ct.line,
                        )
                is TypeNote.Blocked ->
                    context.diagnostics += context.typeCollision(original, note.other, ct.line)
                null -> Unit
            }
            val headQName = QName(context.doc.targetNamespace, original)
            context.heads.types[headQName]?.let { head ->
                return context.choiceLowering.typeHeadDeclaration(ct, original, info, head)
            }
            if (headQName in context.heads.unreferenced) {
                context.diagnostics +=
                    context.lossy(
                        ImportCodes.DROPPED,
                        "complex type '$original'",
                        "abstract type is not referenced; no union written",
                        ct.line,
                    )
            }
            val siblings = mutableListOf<UnitDecl>()
            val content = ct.content
            if (content is XContent.Choice && context.choiceLowering.isUnionType(ct)) {
                val unionWhere = "union '${info.finalName}'"
                context.choiceLowering.unionExtras(ct, unionWhere)
                val union =
                    context.choiceLowering.unionFromChoice(
                        content,
                        info.finalName,
                        unionWhere,
                        ct.doc,
                        siblings,
                        checkMismatch = true,
                        annotations = listOfNotNull(info.annotation),
                    )
                return listOf(union) + siblings
            }
            val rootElement =
                context.doc.elements.firstOrNull {
                    it.ref == null && it.type == QName(context.doc.targetNamespace, original)
                }
            // The type override already serves double duty on the XSD target (it also names the
            // global element): when the type didn't otherwise need one, but adding it would make
            // the element name exact too, it's worth adding for that alone, since it still
            // regenerates the same type name either way. Only a genuinely mismatched element name
            // is unfixable.
            var typeOverride = info.annotation
            val rootAnnotation =
                if (rootElement == null) UnitAnnotation("xsd", "root", "false")
                else {
                    val overrideText = original.removeSuffix("Type")
                    val regenerated =
                        if (typeOverride != null) overrideText else Names.snakeCase(info.finalName)
                    if (rootElement.name != regenerated) {
                        if (typeOverride == null && rootElement.name == overrideText) {
                            typeOverride = UnitAnnotation("xsd", "name", "\"$overrideText\"")
                        } else {
                            context.diagnostics +=
                                context.lossy(
                                    ImportCodes.APPROXIMATED,
                                    "element '${rootElement.name}'",
                                    "element '${rootElement.name}' has no Schemata equivalent; the " +
                                        "regenerated root element will be named '$regenerated'",
                                    rootElement.line,
                                )
                        }
                    }
                    null
                }
            val (fields, nested, recordAnnotations) =
                context.complexLowering.fieldsAndNested(
                    ct,
                    "complex type '$original'",
                    info.finalName,
                    siblings,
                )
            val annotations = listOfNotNull(typeOverride, rootAnnotation) + recordAnnotations
            return listOf(UnitRecord(info.finalName, fields, nested, ct.doc, annotations)) +
                siblings
        }

        /**
         * A global element with its own inline complex type: a top-level record, always a root;
         * dropped, as an error, when its name is already a top-level type's.
         */
        fun topLevelRecord(el: XElement): List<UnitDecl> =
            context.at(el.path) { topLevelRecordAt(el) }

        private fun topLevelRecordAt(el: XElement): List<UnitDecl> {
            val original = el.name ?: return emptyList()
            val ct = el.inlineComplex ?: return emptyList()
            context.reportIdentityConstraints(el, "element '$original'")
            val name = elementRecordName(original, el.line)
            if (!context.claimTopLevel(name, "element '$original'", el.line)) return emptyList()
            val siblings = mutableListOf<UnitDecl>()
            val content = ct.content
            if (content is XContent.Choice && context.choiceLowering.isUnionType(ct)) {
                val unionWhere = "union '$name'"
                context.choiceLowering.unionExtras(ct, unionWhere)
                val union =
                    context.choiceLowering.unionFromChoice(
                        content,
                        name,
                        unionWhere,
                        ct.doc ?: el.doc,
                        siblings,
                        checkMismatch = true,
                    )
                return listOf(union) + siblings
            }
            val regenerated = Names.snakeCase(name)
            if (regenerated != original) {
                context.diagnostics +=
                    context.lossy(
                        ImportCodes.APPROXIMATED,
                        "element '$original'",
                        "the regenerated root element will be named '$regenerated'",
                        el.line,
                    )
            }
            val (fields, nested, annotations) =
                context.complexLowering.fieldsAndNested(ct, "element '$original'", name, siblings)
            return listOf(UnitRecord(name, fields, nested, ct.doc ?: el.doc, annotations)) +
                siblings
        }

        /**
         * The record name for the global element [original]'s own type: its UpperCamel name, or,
         * when another element's record already took that, the name numbered from 2 and noted. A
         * named type owning the name is left to [claimTopLevel] to report.
         */
        private fun elementRecordName(original: String, line: Int): String {
            val base = ImportNames.upperCamel(original)
            val owner = context.topLevelNames[base]
            if (owner == null || !owner.startsWith("element '")) {
                context.elementRecords.declare(QName(context.doc.targetNamespace, original), base)
                return base
            }
            var n = 2
            while (context.complexLowering.numbered(base, n, "") in context.topLevelNames) n++
            val name = context.complexLowering.numbered(base, n, "")
            context.elementRecords.declare(QName(context.doc.targetNamespace, original), name)
            context.diagnostics +=
                context.lossy(
                    ImportCodes.APPROXIMATED,
                    "element '$original'",
                    "$owner already lowers to model '$base'; imported as '$name'",
                    line,
                )
            return name
        }

        /**
         * A global element that is not a record of its own: the first one naming a complex type of
         * this namespace is that record's root (see [declaration]); a second one naming the same
         * type, and one of a simple type or of a type in another namespace, are dropped, since the
         * xsd target writes a global element only for a record of its own namespace.
         */
        fun checkRoot(el: XElement, roots: MutableSet<QName>) =
            context.at(el.path) { checkRootAt(el, roots) }

        private fun checkRootAt(el: XElement, roots: MutableSet<QName>) {
            val name = el.name ?: return
            val where = "element '$name'"
            context.reportIdentityConstraints(el, where)
            val type = el.type
            val simple = "root element of simple type dropped"
            val property = QName(context.doc.targetNamespace, name) in context.propertyElements
            when {
                type == null -> {
                    if (el.inlineSimple != null) {
                        context.diagnostics +=
                            context.lossy(ImportCodes.DROPPED, where, simple, el.line)
                    }
                }
                type.namespace == ImportTypes.XS ->
                    context.diagnostics +=
                        context.lossy(ImportCodes.DROPPED, where, simple, el.line)
                type.namespace != context.doc.targetNamespace ->
                    if (!property) {
                        context.diagnostics +=
                            context.lossy(
                                ImportCodes.DROPPED,
                                where,
                                "root element of a type in another namespace dropped",
                                el.line,
                            )
                    }
                context.doc.complexTypes.any { it.name == type.local } ->
                    if (!roots.add(type) && !property) {
                        context.diagnostics +=
                            context.lossy(
                                ImportCodes.DROPPED,
                                where,
                                "second root element for '${type.local}' dropped",
                                el.line,
                            )
                    }
                context.doc.simpleTypes.any { it.name == type.local } ->
                    context.diagnostics +=
                        context.lossy(ImportCodes.DROPPED, where, simple, el.line)
            }
        }
    }
}

/**
 * The record each global element with an anonymous type is declared under, shared by every
 * namespace's lowering. The name depends on what the lowering before the elements took (a hoisted
 * member, another element lowering to the same name), so a reference to such an element, which may
 * be lowered before the element in its own namespace or in another, holds a placeholder until every
 * namespace has lowered, and [resolve] then puts the declared name in its place.
 */
internal class ElementRecords {
    private val names = mutableMapOf<QName, String>()

    /** Each placeholder's element and, for one in another namespace, that namespace's name. */
    private val references = mutableListOf<Pair<QName, String?>>()

    /** Records that the global element [element]'s type is declared as the record [name]. */
    fun declare(element: QName, name: String) {
        names[element] = name
    }

    /**
     * A placeholder for the record of the global element [element], qualified by [namespace] when
     * that is not the referring unit's own.
     */
    fun reference(element: QName, namespace: String?): UnitType.Ref {
        references += element to namespace
        return UnitType.Ref(ELEMENT_REF_PREFIX + (references.size - 1))
    }

    /** [decl] with each placeholder in it replaced by the name its element's record took. */
    fun resolve(decl: UnitDecl): UnitDecl =
        when (decl) {
            is UnitRecord ->
                decl.copy(
                    fields = decl.fields.map { it.copy(type = resolve(it.type)) },
                    nested = decl.nested.map(::resolve),
                )
            is UnitUnion ->
                decl.copy(members = decl.members.map { it.copy(type = resolve(it.type)) })
            else -> decl
        }

    private fun resolve(type: UnitType): UnitType =
        when (type) {
            is UnitType.Ref -> {
                val index = type.name.removePrefix(ELEMENT_REF_PREFIX).toIntOrNull()
                if (!type.name.startsWith(ELEMENT_REF_PREFIX) || index == null) type
                else {
                    val (element, namespace) = references[index]
                    val name = names[element] ?: ImportNames.upperCamel(element.local)
                    UnitType.Ref(if (namespace == null) name else "$namespace.$name")
                }
            }
            is UnitType.ListOf -> type.copy(element = resolve(type.element))
            is UnitType.MapOf -> type.copy(key = resolve(type.key), value = resolve(type.value))
            is UnitType.Scalar -> type
        }
}

/**
 * The state one namespace's lowering shares across the simple-type, choice, and complex-type
 * concerns: the document set, the name claims, the diagnostics list, the current document, and the
 * heads, with the three concerns that call one another through it.
 */
internal class ImportContext(
    val doc: XsdDoc,
    val docsByNamespace: Map<String?, XsdDoc>,
    val namespaceNames: Map<XsdDoc, String>,
    val typeNames: Map<QName, TypeNameInfo>,
    val topLevelNames: MutableMap<String, String>,
    val cycles: Cycles,
    val heads: Heads,
    val diagnostics: MutableList<Diagnostic>,
    val unresolvedImports: List<String> = emptyList(),
    /**
     * The notes reported while a base's fields were flattened into a derived type, as opposed to
     * while the base itself was lowered: the same note then comes up once per derived type, and
     * only the base's own copy says where the construct is declared. Shared by every document.
     */
    val inherited: MutableSet<Diagnostic> = Collections.newSetFromMap(IdentityHashMap()),
    /**
     * The global elements some other element uses by `ref`: properties of content models, not
     * roots, so that only the first of a type marks a root and the rest are not reported as lost.
     */
    val propertyElements: Set<QName> = emptySet(),
    /** The record names the global elements with anonymous types are declared under. */
    val elementRecords: ElementRecords,
) {
    lateinit var simpleTypes: SimpleTypes
    lateinit var choiceLowering: ChoiceLowering
    lateinit var complexLowering: ComplexLowering

    /** The result of [block], with the diagnostics it reported marked as [inherited]. */
    fun <T> inheriting(block: () -> T): T {
        val start = diagnostics.size
        val result = block()
        if (diagnostics.size > start) inherited += diagnostics.subList(start, diagnostics.size)
        return result
    }

    /**
     * Namespaces a head union's members, or the records of elements referenced from here, live in,
     * which the unit must import.
     */
    val extraImports = linkedSetOf<String>()

    /**
     * Fields whose names are synthesised (a wildcard's, mixed text's, an attribute wildcard's), by
     * the placeholder each holds until its record's named fields have claimed theirs.
     */
    private val pendingNames = mutableMapOf<String, PendingName>()

    private var pendingCount = 0

    /** [st]'s enumeration values, its members' for a union of enumerations; see [enumFacets]. */
    internal fun enumFacets(st: XSimpleType): List<XFacet>? = enumFacets(st, docsByNamespace)

    internal fun isEnum(st: XSimpleType): Boolean = enumFacets(st) != null

    /**
     * The document whose lines a diagnostic points at: [doc], except while a component an include
     * brought in is being lowered, or an extension base declared in another document is being
     * flattened, when it is that component's document.
     */
    internal var sourcePath = doc.path

    /** Runs [block] with diagnostics pointing at [path], or where they were if it is empty. */
    internal inline fun <T> at(path: String, block: () -> T): T {
        val saved = sourcePath
        sourcePath = path.ifEmpty { saved }
        try {
            return block()
        } finally {
            sourcePath = saved
        }
    }

    /**
     * Claims [name] as a top-level declaration for [holder]; `false`, as an error, when a named
     * type or an earlier global or hoisted element already owns it.
     */
    internal fun claimTopLevel(name: String, holder: String, line: Int): Boolean {
        val existing = topLevelNames[name]
        if (existing != null) {
            val l = line.coerceAtLeast(1)
            diagnostics +=
                Diagnostic(
                    ImportCodes.UNRESOLVED,
                    "$holder and $existing both lower to type '$name'",
                    Span(sourcePath, l, 1, l, 1),
                    "rename one of them",
                )
            return false
        }
        topLevelNames[name] = holder
        return true
    }

    /**
     * [fields] with each synthesised name settled, in order, now that every named field of the
     * record has claimed its own: `any`, `any_2`… for wildcards, `text` (else `mixed_text`) for
     * mixed text, `attributes` (else `any_attributes`) for an attribute wildcard, each the first
     * still free. One that still collides is reported and dropped.
     */
    internal fun settleNames(
        fields: List<UnitField>,
        claimed: MutableMap<String, Claim>,
    ): List<UnitField> =
        fields.mapNotNull { f ->
            val pending = pendingNames.remove(f.name) ?: return@mapNotNull f
            claimed.remove(f.name)
            val name =
                when (pending.kind) {
                    ClaimKind.MIXED_TEXT -> if ("text" in claimed) "mixed_text" else "text"
                    ClaimKind.WILDCARD_ATTRIBUTE ->
                        if ("attributes" in claimed) "any_attributes" else "attributes"
                    else -> {
                        var index = 1
                        while (XsdWildcards.anyName(index) in claimed) index++
                        XsdWildcards.anyName(index)
                    }
                }
            val (fieldName, annotations) =
                at(pending.path) {
                    nameAndClaim(
                        name,
                        pending.kind,
                        pending.construct,
                        claimed,
                        pending.whereCollision,
                        pending.line,
                    )
                } ?: return@mapNotNull null
            f.copy(name = fieldName, annotations = annotations + f.annotations)
        }

    /**
     * A placeholder name for a field whose real name is settled by [settleNames], claimed in
     * [claimed] under [construct] meanwhile.
     */
    internal fun pendingName(
        kind: ClaimKind,
        construct: String,
        claimed: MutableMap<String, Claim>,
        whereCollision: String,
        line: Int,
    ): String {
        val placeholder = "$PLACEHOLDER${pendingCount++}"
        pendingNames[placeholder] = PendingName(kind, construct, whereCollision, line, sourcePath)
        claimed[placeholder] = Claim(kind, construct)
        return placeholder
    }

    /** The result of [block], with the diagnostics it reported taken back. */
    internal fun <T> quietly(block: () -> T): T {
        val reported = diagnostics.size
        try {
            return block()
        } finally {
            while (diagnostics.size > reported) diagnostics.removeLast()
        }
    }

    /**
     * Reports [el]'s identity constraints, which Schemata has no way to say: every `xs:key` and
     * `xs:keyref`, and every `xs:unique` but the one a map wrapper would consume (whether or not
     * [el] actually turns out to be a map).
     */
    internal fun reportIdentityConstraints(el: XElement, where: String) {
        val mapUnique = el.uniques.firstOrNull(::isMapUnique)
        val constraints = (el.uniques - listOfNotNull(mapUnique)).map { it.name to it.line }
        (constraints + el.keys.map { it.name to it.line }).forEach { (name, line) ->
            diagnostics +=
                lossy(ImportCodes.DROPPED, where, "identity constraint '$name' dropped", line)
        }
    }

    /**
     * The field's final name, claimed against [claimed]: [ImportCodes.APPROXIMATED] when [original]
     * isn't even a valid XML name (no override could ever regenerate it, so none is offered — a
     * valid override, like a type's, always regenerates the original xsd text exactly, so needing
     * one is never reported), and [ImportCodes.UNRESOLVED] (dropping the field, returning `null`)
     * when it collides with one already claimed in this record.
     */
    internal fun nameAndClaim(
        original: String,
        kind: ClaimKind,
        whereConstruct: String,
        claimed: MutableMap<String, Claim>,
        whereCollision: String,
        line: Int,
    ): Pair<String, List<UnitAnnotation>>? {
        val field = fieldNameFor(original)
        if (field.unfixable) {
            diagnostics +=
                lossy(
                    ImportCodes.APPROXIMATED,
                    whereConstruct,
                    "${kind.word} '$original' has no Schemata equivalent; imported as " +
                        "'${field.name}'",
                    line,
                )
        }
        val existing = claimed[field.name]
        if (existing != null) {
            attributeBeside(
                    field.name,
                    field.annotation,
                    kind,
                    whereConstruct,
                    existing,
                    claimed,
                    whereCollision,
                    line,
                )
                ?.let {
                    return it
                }
            diagnostics +=
                collision(whereCollision, existing.construct, whereConstruct, field.name, line)
            return null
        }
        claimed[field.name] = Claim(kind, whereConstruct)
        return field.name to listOfNotNull(field.annotation)
    }

    /**
     * An element and an attribute lowering to one field [name]: XML keeps the two apart, so the
     * element keeps [name] and the attribute takes `<name>_attribute`, with no override (the xsd
     * target names a record's elements and attributes in one scope, where the original name would
     * collide again), and that is noted. When the attribute claimed [name] first, its field is
     * renamed once the record is complete (see [renameAttributesBesideElements]). `null` when the
     * pair is not an element and an attribute, or the renamed field is taken.
     */
    internal fun attributeBeside(
        name: String,
        override: UnitAnnotation?,
        kind: ClaimKind,
        whereConstruct: String,
        existing: Claim,
        claimed: MutableMap<String, Claim>,
        whereCollision: String,
        line: Int,
    ): Pair<String, List<UnitAnnotation>>? {
        val attributeFirst = kind == ClaimKind.ELEMENT && existing.kind == ClaimKind.ATTRIBUTE
        val attributeSecond = kind == ClaimKind.ATTRIBUTE && existing.kind == ClaimKind.ELEMENT
        val renamed = "${name}_attribute"
        if (!(attributeFirst || attributeSecond) || renamed in claimed) return null
        val (attribute, element) =
            if (attributeFirst) existing.construct to whereConstruct
            else whereConstruct to existing.construct
        diagnostics +=
            lossy(
                ImportCodes.APPROXIMATED,
                whereCollision,
                "$attribute and $element both lower to field '$name'; the attribute is " +
                    "imported as '$renamed' and the regenerated attribute will be named so",
                line,
            )
        claimed[renamed] = Claim(ClaimKind.ATTRIBUTE, attribute)
        if (attributeSecond) return renamed to emptyList()
        claimed[name] = Claim(ClaimKind.ELEMENT, element)
        return name to listOfNotNull(override)
    }

    /**
     * [fields] with every attribute field whose name an element took from it (see
     * [attributeBeside]) renamed `<name>_attribute`, without its name override.
     */
    internal fun renameAttributesBesideElements(
        fields: List<UnitField>,
        claimed: Map<String, Claim>,
    ): List<UnitField> =
        fields.map { f ->
            val isAttribute = f.annotations.any { it.target == "xsd" && it.key == "attribute" }
            val owner = claimed[f.name]
            if (isAttribute && owner != null && owner.kind != ClaimKind.ATTRIBUTE) {
                f.copy(
                    name = "${f.name}_attribute",
                    // The override named the attribute's own text, which the element now owns.
                    annotations = f.annotations.filterNot { it.target == "xsd" && it.key == "name" },
                )
            } else f
        }

    /**
     * `"$where: $tail"` with the standard help text for [code] (shared across every diagnostic).
     */
    fun lossy(code: DiagnosticCode, where: String, tail: String, line: Int): Diagnostic {
        val l = line.coerceAtLeast(1)
        return Diagnostic(
            code,
            "$where: $tail",
            Span(sourcePath, l, 1, l, 1),
            missingImportsHelp(code, tail) ?: ImportCodes.helpFor(code),
        )
    }

    /**
     * For a reference that cannot be resolved while imports were dropped as not found, the likely
     * cause: the help names those imports. `null` when [code] and [tail] are some other problem.
     */
    private fun missingImportsHelp(code: DiagnosticCode, tail: String): String? {
        if (code != ImportCodes.UNRESOLVED || unresolvedImports.isEmpty()) return null
        if (!tail.endsWith("cannot be resolved")) return null
        val names = unresolvedImports.joinToString(", ") { "'$it'" }
        return if (unresolvedImports.size == 1)
            "import $names was not found; add the schema that declares it"
        else "imports $names were not found; add the schemas that declare them"
    }

    /** A facet [Note] at [path] (the simple type's own document, which may differ from [doc]). */
    internal fun noteDiagnostic(path: String, where: String, note: Note): Diagnostic {
        val line = note.line.coerceAtLeast(1)
        return Diagnostic(
            note.code,
            "$where: ${note.tail}",
            Span(path, line, 1, line, 1),
            ImportCodes.helpFor(note.code),
        )
    }

    /** Two constructs lowering to the same field name: [ImportCodes.UNRESOLVED], one dropped. */
    internal fun collision(
        where: String,
        a: String,
        b: String,
        name: String,
        line: Int,
    ): Diagnostic {
        val l = line.coerceAtLeast(1)
        return Diagnostic(
            ImportCodes.UNRESOLVED,
            "$where: $a and $b both lower to field '$name'",
            Span(sourcePath, l, 1, l, 1),
            "rename one of them",
        )
    }

    /**
     * [original]'s only possible override would regenerate a type name [other] already owns by
     * default, so neither can round-trip to [original] exactly: [ImportCodes.UNRESOLVED], no
     * override applied.
     */
    internal fun typeCollision(
        original: String,
        other: String,
        line: Int,
        kind: String = "complex type",
    ): Diagnostic {
        val l = line.coerceAtLeast(1)
        return Diagnostic(
            ImportCodes.UNRESOLVED,
            "$kind '$original' and '$other' both lower to type '$original'",
            Span(doc.path, l, 1, l, 1),
            "rename one of them",
        )
    }
}
