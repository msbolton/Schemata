package io.schemata.importer.xsd

import io.schemata.importer.ImportCodes
import io.schemata.importer.ImportNames
import io.schemata.importer.Imported
import io.schemata.importer.SchemataUnit
import io.schemata.importer.UnionMember
import io.schemata.importer.UnitAnnotation
import io.schemata.importer.UnitDecl
import io.schemata.importer.UnitEnum
import io.schemata.importer.UnitEnumValue
import io.schemata.importer.UnitField
import io.schemata.importer.UnitRecord
import io.schemata.importer.UnitType
import io.schemata.importer.UnitUnion
import io.schemata.lang.Diagnostic
import io.schemata.lang.DiagnosticCode
import io.schemata.lang.Span
import io.schemata.target.Names

/**
 * A type's resolved Schemata name, the `@xsd(name)` annotation it needs (when its name can be
 * fixed), and a lossy note when it can't be: [TypeNote.Unfixable] when the XSD name doesn't end in
 * `Type` at all (no override can ever reproduce it), [TypeNote.Blocked] when an override exists in
 * principle but would regenerate a type name another type in the namespace already owns.
 */
private data class TypeNameInfo(
    val finalName: String,
    val annotation: UnitAnnotation?,
    val note: TypeNote? = null,
)

private sealed interface TypeNote {
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
        val names = LinkedHashMap<XsdDoc, String>()
        val claimed = mutableMapOf<String, XsdDoc>()
        val live = mutableListOf<XsdDoc>()

        docs.forEachIndexed { index, doc ->
            // Two documents claiming one foreign namespace without including each other would
            // declare it twice; a urn:schemata: one is caught below, as a namespace name collision.
            val tn = doc.targetNamespace
            val sameUri =
                live.firstOrNull {
                    tn != null && schemataName(tn) == null && it.targetNamespace == tn
                }
            if (sameUri != null) {
                diagnostics +=
                    unresolved(doc.path, 1, "namespace '$tn' is also declared by ${sameUri.path}")
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
                        "${existing.path} and ${doc.path} both lower to namespace '$name'",
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

        val units =
            live.map { doc ->
                val imports = mutableListOf<String>()
                doc.imports.forEach { imp ->
                    val target = docsByNamespace[imp.namespace]
                    if (target == null) {
                        val what =
                            imp.namespace?.let { "import '$it'" } ?: "import with no namespace"
                        diagnostics += unresolved(doc.path, imp.line, "$what cannot be resolved")
                    } else if (target !== doc) {
                        imports += names.getValue(target)
                    }
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
                val lowering =
                    NamespaceLowering(
                        doc,
                        docsByNamespace,
                        names,
                        typeNames,
                        topLevelNames,
                        cycles,
                        heads,
                        diagnostics,
                    )
                val declarations = mutableListOf<UnitDecl>()
                // Complex types and enumerated simple types are declared on one combined list,
                // ordered by source line: the xsd target interleaves a nested record's and a nested
                // enum's flattened types as it encounters them, so matching that order here is what
                // lets a re-exported xsd come out byte for byte the same as the one that was read.
                (doc.complexTypes.map { it.line to lowering.declaration(it) } +
                        doc.simpleTypes
                            .filter { it.name != null && isEnum(it) }
                            .map { it.line to listOf(lowering.enumDeclaration(it)) })
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
                declarations += lowering.headUnions()
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
                    imports = (imports + lowering.extraImports).distinct(),
                    declarations = declarations,
                    sourcePath = doc.path,
                )
            }
        return Imported(units, diagnostics)
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
        diagnostics += renamed(doc.path, 1, "namespace '$name' was derived from the file name")
        return name
    }

    private const val URN = "urn:schemata:"

    /** How a record's mixed-content field and attribute-wildcard field are held in its claims. */
    private const val MIXED_TEXT = "mixed content"
    private const val ANY_ATTRIBUTE = "xs:anyAttribute"

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
    private data class Cycles(val groups: Set<QName>, val attributeGroups: Set<QName>)

    /**
     * Every group that reaches itself: through a nested group reference, an element's anonymous
     * type, or a complex type's derivation base. Expanding one in place would never end.
     */
    private fun cyclicGroups(docsByNamespace: Map<String?, XsdDoc>): Set<QName> {
        val groups = mutableMapOf<QName, XContent>()
        val types = mutableMapOf<QName, XContent>()
        docsByNamespace.values.forEach { d ->
            d.groups.forEach { groups[QName(d.targetNamespace, it.name)] = it.content }
            d.complexTypes.forEach { ct ->
                ct.name?.let { types[QName(d.targetNamespace, it)] = ct.content }
            }
        }
        // A node is a group ("g") or a named complex type ("t"); edges follow content.
        fun edges(content: XContent): Set<Pair<Char, QName>> {
            fun particles(ps: List<XParticle>): Set<Pair<Char, QName>> =
                ps.flatMap { p ->
                        when (p) {
                            is XParticle.GroupRef -> setOf('g' to p.ref)
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
                is XContent.Extension -> particles(content.particles) + ('t' to content.base)
                is XContent.Restriction -> particles(content.particles)
                XContent.Empty -> emptySet()
            }
        }
        fun reachesItself(start: QName): Boolean {
            val seen = mutableSetOf<Pair<Char, QName>>()
            val queue = ArrayDeque(edges(groups.getValue(start)))
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                if (node == ('g' to start)) return true
                if (!seen.add(node)) continue
                val content = (if (node.first == 'g') groups else types)[node.second] ?: continue
                queue += edges(content)
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
     * exactly (`full-name` → `full_name` with `@xsd(name = "full-name")`), and, when [original] is
     * not even a valid XML name (a bare enumeration value may start with a digit, as `2d` does), no
     * override at all: the xsd target rejects one that isn't a valid name, so offering it would
     * only trade one way of failing to round trip for another. A valid identifier is kept as-is.
     */
    private fun fieldNameFor(original: String): FieldName {
        if (ImportNames.isLowerSnake(original)) return FieldName(original, null, unfixable = false)
        val fixed = ImportNames.lowerSnake(original)
        return if (XsdNames.isValidOverride(original)) {
            FieldName(fixed, UnitAnnotation("xsd", "name", "\"$original\""), unfixable = false)
        } else {
            FieldName(fixed, null, unfixable = true)
        }
    }

    /**
     * A field whose synthesised name waits for its record's named fields: the [kind] and
     * [construct] its claim reports, and where.
     */
    private data class PendingName(
        val kind: String,
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
    private fun spellSigns(value: String): String = buildString {
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

    private data class FieldName(
        val name: String,
        val annotation: UnitAnnotation?,
        val unfixable: Boolean,
    )

    private fun listRefinements(min: Int, max: Int?): List<Pair<String, String>> =
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
    private fun enumFacets(
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
    private fun diagnostic(code: DiagnosticCode, path: String, line: Int, message: String) =
        Diagnostic(
            code,
            message,
            Span(path, line.coerceAtLeast(1), 1, line.coerceAtLeast(1), 1),
            ImportCodes.helpFor(code),
        )

    private fun unresolved(path: String, line: Int, message: String) =
        diagnostic(ImportCodes.UNRESOLVED, path, line, "$path: $message")

    private fun renamed(path: String, line: Int, message: String) =
        diagnostic(ImportCodes.RENAMED, path, line, "$path: $message")

    private fun dropped(path: String, line: Int, where: String, tail: String) =
        diagnostic(ImportCodes.DROPPED, path, line, "$where: $tail")

    /** The `xs:unique` the xsd target writes on a map wrapper: one `@key` field over `entry`. */
    private fun isMapUnique(u: XUnique): Boolean =
        u.fields == listOf("@key") && u.selector.substringAfterLast(':') == "entry"

    /**
     * A recognised map-entry shape: a wrapper element holding a repeated `entry`, itself extending
     * [valueType] (or wrapping it in a `value` child when it carries its own refinements) and
     * carrying [keyAttribute].
     */
    private data class EntryShape(
        val keyAttribute: XAttribute,
        val valueType: UnitType,
        val entry: XElement,
    )

    private sealed interface MapResult {
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
    private class NamespaceLowering(
        val doc: XsdDoc,
        val docsByNamespace: Map<String?, XsdDoc>,
        val namespaceNames: Map<XsdDoc, String>,
        val typeNames: Map<QName, TypeNameInfo>,
        val topLevelNames: MutableMap<String, String>,
        val cycles: Cycles,
        val heads: Heads,
        val diagnostics: MutableList<Diagnostic>,
    ) {
        /** Namespaces a head union's members live in, which the unit must import. */
        val extraImports = linkedSetOf<String>()

        /** How many inline choices each record has had so far, which numbers the next one. */
        private val choiceCounts = mutableMapOf<String, Int>()

        /**
         * Fields whose names are synthesised (a wildcard's, mixed text's, an attribute wildcard's),
         * by the placeholder each holds until its record's named fields have claimed theirs.
         */
        private val pendingNames = mutableMapOf<String, PendingName>()
        private var pendingCount = 0

        /**
         * [st]'s enumeration values, its members' for a union of enumerations; see [enumFacets].
         */
        private fun enumFacets(st: XSimpleType): List<XFacet>? = enumFacets(st, docsByNamespace)

        private fun isEnum(st: XSimpleType): Boolean = enumFacets(st) != null

        /**
         * The document whose lines a diagnostic points at: [doc], except while a component an
         * include brought in is being lowered, or an extension base declared in another document is
         * being flattened, when it is that component's document.
         */
        private var sourcePath = doc.path

        /** Runs [block] with diagnostics pointing at [path], or where they were if it is empty. */
        private inline fun <T> at(path: String, block: () -> T): T {
            val saved = sourcePath
            sourcePath = path.ifEmpty { saved }
            try {
                return block()
            } finally {
                sourcePath = saved
            }
        }

        /** A named complex type: a record, or, when its content is a bare choice, a union. */
        fun declaration(ct: XComplexType): List<UnitDecl> = at(ct.path) { declarationAt(ct) }

        private fun declarationAt(ct: XComplexType): List<UnitDecl> {
            val original = ct.name ?: return emptyList()
            val info = typeNames.getValue(QName(doc.targetNamespace, original))
            when (val note = info.note) {
                is TypeNote.Unfixable ->
                    diagnostics +=
                        lossy(
                            ImportCodes.APPROXIMATED,
                            "complex type '$original'",
                            "complex type '$original' has no Schemata equivalent; the regenerated " +
                                "type will be named '${note.regeneratedType}'",
                            ct.line,
                        )
                is TypeNote.Blocked -> diagnostics += typeCollision(original, note.other, ct.line)
                null -> Unit
            }
            val headQName = QName(doc.targetNamespace, original)
            heads.types[headQName]?.let { head ->
                return typeHeadDeclaration(ct, original, info, head)
            }
            val siblings = mutableListOf<UnitDecl>()
            val content = ct.content
            if (content is XContent.Choice && isUnionType(ct)) {
                val unionWhere = "union '${info.finalName}'"
                unionExtras(ct, unionWhere)
                val union =
                    unionFromChoice(
                        content,
                        info.finalName,
                        unionWhere,
                        ct.doc,
                        siblings,
                        checkMismatch = true,
                    )
                return listOf(union.copy(annotations = listOfNotNull(info.annotation))) + siblings
            }
            val rootElement =
                doc.elements.firstOrNull {
                    it.ref == null && it.type == QName(doc.targetNamespace, original)
                }
            // The type override already serves double duty on the XSD target (it also names the
            // global element): when the type didn't otherwise need one, but adding it would make
            // the
            // element name exact too, it's worth adding for that alone, since it still regenerates
            // the same type name either way. Only a genuinely mismatched element name is unfixable.
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
                            diagnostics +=
                                lossy(
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
                fieldsAndNested(ct, "complex type '$original'", info.finalName, siblings)
            val annotations = listOfNotNull(typeOverride, rootAnnotation) + recordAnnotations
            return listOf(UnitRecord(info.finalName, fields, nested, ct.doc, annotations)) +
                siblings
        }

        /**
         * An abstract complex type with concrete descendants: nothing when it has exactly one,
         * which every use of it names instead; otherwise a union of them, under the type's own
         * name, which also serves any substitution group headed by an element of this type with the
         * same members.
         */
        private fun typeHeadDeclaration(
            ct: XComplexType,
            original: String,
            info: TypeNameInfo,
            head: HeadMembers,
        ): List<UnitDecl> {
            val where = "complex type '$original'"
            val n = head.members.size
            if (n == 1) {
                val only = typeNames.getValue(head.members[0]).finalName
                diagnostics +=
                    lossy(
                        ImportCodes.APPROXIMATED,
                        where,
                        "abstract type '$original' imported as its one concrete type '$only'",
                        ct.line,
                    )
                return emptyList()
            }
            diagnostics +=
                lossy(
                    ImportCodes.APPROXIMATED,
                    where,
                    "abstract type '$original' imported as union '${info.finalName}' of $n " +
                        "concrete types; the regenerated XSD uses a choice",
                    ct.line,
                )
            val memberElements =
                heads.memberElements
                    .filterKeys {
                        headElement(it)?.type == QName(doc.targetNamespace, original) &&
                            sharesTypeUnion(it)
                    }
                    .values
                    .flatten()
            val union = headUnion(info.finalName, head, where, memberElements, ct.doc)
            return listOf(union.copy(annotations = listOfNotNull(info.annotation)))
        }

        /** A named, enumerated simple type: an enum. */
        fun enumDeclaration(st: XSimpleType): UnitEnum = at(st.path) { enumDeclarationAt(st) }

        private fun enumDeclarationAt(st: XSimpleType): UnitEnum {
            val original = st.name ?: error("an enumerated top-level simple type always has a name")
            val info = typeNames.getValue(QName(doc.targetNamespace, original))
            when (val note = info.note) {
                is TypeNote.Unfixable ->
                    diagnostics +=
                        lossy(
                            ImportCodes.APPROXIMATED,
                            "simple type '$original'",
                            "simple type '$original' has no Schemata equivalent; the regenerated " +
                                "type will be named '${note.regeneratedType}'",
                            st.line,
                        )
                is TypeNote.Blocked ->
                    diagnostics += typeCollision(original, note.other, st.line, "simple type")
                null -> Unit
            }
            val where = "simple type '$original'"
            if (st.variety is XVariety.Union) unionNote(where, "enum '${info.finalName}'", st.line)
            val built = buildInlineEnum(st, info.finalName, where)
            return built.copy(annotations = listOfNotNull(info.annotation))
        }

        /**
         * A global element with its own inline complex type: a top-level record, always a root;
         * dropped, as an error, when its name is already a top-level type's.
         */
        fun topLevelRecord(el: XElement): List<UnitDecl> = at(el.path) { topLevelRecordAt(el) }

        private fun topLevelRecordAt(el: XElement): List<UnitDecl> {
            val original = el.name ?: return emptyList()
            val ct = el.inlineComplex ?: return emptyList()
            reportIdentityConstraints(el, "element '$original'")
            val name = elementRecordName(original, el.line)
            if (!claimTopLevel(name, "element '$original'", el.line)) return emptyList()
            val siblings = mutableListOf<UnitDecl>()
            val content = ct.content
            if (content is XContent.Choice && isUnionType(ct)) {
                val unionWhere = "union '$name'"
                unionExtras(ct, unionWhere)
                val union =
                    unionFromChoice(
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
                diagnostics +=
                    lossy(
                        ImportCodes.APPROXIMATED,
                        "element '$original'",
                        "the regenerated root element will be named '$regenerated'",
                        el.line,
                    )
            }
            val (fields, nested, annotations) =
                fieldsAndNested(ct, "element '$original'", name, siblings)
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
            val owner = topLevelNames[base]
            if (owner == null || !owner.startsWith("element '")) return base
            var n = 2
            while (numbered(base, n, "") in topLevelNames) n++
            val name = numbered(base, n, "")
            diagnostics +=
                lossy(
                    ImportCodes.APPROXIMATED,
                    "element '$original'",
                    "$owner already lowers to record '$base'; imported as '$name'",
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
            at(el.path) { checkRootAt(el, roots) }

        private fun checkRootAt(el: XElement, roots: MutableSet<QName>) {
            val name = el.name ?: return
            val where = "element '$name'"
            reportIdentityConstraints(el, where)
            val type = el.type
            val simple = "root element of simple type dropped"
            when {
                type == null -> {
                    if (el.inlineSimple != null) {
                        diagnostics += lossy(ImportCodes.DROPPED, where, simple, el.line)
                    }
                }
                type.namespace == ImportTypes.XS ->
                    diagnostics += lossy(ImportCodes.DROPPED, where, simple, el.line)
                type.namespace != doc.targetNamespace ->
                    diagnostics +=
                        lossy(
                            ImportCodes.DROPPED,
                            where,
                            "root element of a type in another namespace dropped",
                            el.line,
                        )
                doc.complexTypes.any { it.name == type.local } ->
                    if (!roots.add(type)) {
                        diagnostics +=
                            lossy(
                                ImportCodes.DROPPED,
                                where,
                                "second root element for '${type.local}' dropped",
                                el.line,
                            )
                    }
                doc.simpleTypes.any { it.name == type.local } ->
                    diagnostics += lossy(ImportCodes.DROPPED, where, simple, el.line)
            }
        }

        /**
         * Claims [name] as a top-level declaration for [holder]; `false`, as an error, when a named
         * type or an earlier global or hoisted element already owns it.
         */
        private fun claimTopLevel(name: String, holder: String, line: Int): Boolean {
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

        /** The global element [qname] names, in whichever document declares it. */
        private fun headElement(qname: QName): XElement? =
            docsByNamespace[qname.namespace]?.elements?.firstOrNull { it.name == qname.local }

        /**
         * Whether the substitution group headed by [head] is served by its type's own union: the
         * head element's type is an abstract type of the same namespace whose union has exactly the
         * group's members.
         */
        private fun sharesTypeUnion(head: QName): Boolean {
            val members = heads.elements[head]?.members ?: return false
            val type = headElement(head)?.type ?: return false
            val typeHead = heads.types[type] ?: return false
            return type.namespace == head.namespace &&
                members.size >= 2 &&
                typeHead.members == members
        }

        /**
         * The union a substitution group headed by [head] lowers to, unqualified, in the head's own
         * namespace: its type's union when [sharesTypeUnion]; otherwise the head's own name, or
         * that name suffixed `Choice` when a type or a global element's record of that namespace
         * already lowers to it. Decided from the namespace's type names alone, never from what has
         * been claimed so far, so that every use site and the declaration agree whatever order they
         * are lowered in.
         */
        private fun elementUnionName(head: QName): String {
            if (sharesTypeUnion(head)) {
                return typeNames.getValue(headElement(head)!!.type!!).finalName
            }
            val plain = ImportNames.upperCamel(head.local)
            val typeTaken =
                typeNames.any { (q, info) ->
                    q.namespace == head.namespace && info.finalName == plain
                }
            val recordTaken =
                docsByNamespace[head.namespace]?.elements.orEmpty().any {
                    it.ref == null &&
                        it.type == null &&
                        it.inlineComplex != null &&
                        it.name?.let(ImportNames::upperCamel) == plain
                }
            return if (typeTaken || recordTaken) "${plain}Choice" else plain
        }

        /**
         * A reference to the type [name] declared in [targetDoc], qualified (and imported) when
         * that is another namespace.
         */
        private fun headRef(targetDoc: XsdDoc, name: String): UnitType.Ref {
            if (targetDoc === doc) return UnitType.Ref(name)
            val namespace = namespaceNames.getValue(targetDoc)
            extraImports += namespace
            return UnitType.Ref("$namespace.$name")
        }

        /** What a reference to [member], one head's only member, lowers to. */
        private fun memberRef(member: QName): UnitType.Ref =
            headRef(
                docsByNamespace.getValue(member.namespace),
                typeNames.getValue(member).finalName,
            )

        /**
         * What a use of the abstract complex type [qname] lowers to: its one concrete type, or its
         * union; `null` when [qname] is not an abstract type with concrete descendants.
         */
        private fun headType(qname: QName): UnitType? {
            val head = heads.types[qname] ?: return null
            if (head.members.size == 1) return memberRef(head.members[0])
            return headRef(
                docsByNamespace.getValue(qname.namespace),
                typeNames.getValue(qname).finalName,
            )
        }

        /**
         * What a reference to the substitution-group head element [ref] lowers to: its one member
         * type, or its union; `null` when [ref] heads no group with a member type.
         */
        private fun elementHeadType(ref: QName): UnitType? {
            val head = heads.elements[ref]?.takeIf { it.members.isNotEmpty() } ?: return null
            if (head.members.size == 1) return memberRef(head.members[0])
            return headRef(docsByNamespace.getValue(ref.namespace), elementUnionName(ref))
        }

        /**
         * The unions this namespace's substitution-group heads declare: one per head with two or
         * more member types, unless its type's own union already serves. A head with exactly one
         * member type declares nothing (every use names that type) and is noted; a head with none
         * stays its own element. A member declared with an inline type cannot be named in place of
         * its head, nor can one of a simple or unresolved type be a union member, so each is
         * reported and left out.
         */
        fun headUnions(): List<UnitDecl> {
            val result = mutableListOf<UnitDecl>()
            heads.elements
                .filter { it.key.namespace == doc.targetNamespace }
                .forEach { (name, head) ->
                    val el = headElement(name) ?: return@forEach
                    val where = "element '${name.local}'"
                    val members = heads.memberElements[name].orEmpty()
                    val n = head.members.size
                    val unionName = if (n >= 2) elementUnionName(name) else null
                    when {
                        unionName != null ->
                            at(el.path) {
                                diagnostics +=
                                    lossy(
                                        ImportCodes.APPROXIMATED,
                                        where,
                                        "substitution group '${name.local}' imported as union " +
                                            "'$unionName' of $n member types; the regenerated " +
                                            "XSD uses a choice",
                                        el.line,
                                    )
                            }
                        n == 1 -> {
                            val only = typeNames.getValue(head.members[0]).finalName
                            at(el.path) {
                                diagnostics +=
                                    lossy(
                                        ImportCodes.APPROXIMATED,
                                        where,
                                        "substitution group '${name.local}' imported as its one " +
                                            "member type '$only'",
                                        el.line,
                                    )
                            }
                        }
                    }
                    val droppedFrom =
                        unionName?.let { "union '$it'" } ?: "substitution group '${name.local}'"
                    members.forEach { m ->
                        val what =
                            when {
                                m in head.dropped -> "of ${memberTypeKind(m.type!!)}"
                                m.type == null &&
                                    (m.inlineComplex != null || m.inlineSimple != null) ->
                                    "with an inline type"
                                else -> return@forEach
                            }
                        at(m.path) {
                            diagnostics +=
                                lossy(
                                    ImportCodes.DROPPED,
                                    "element '${m.name}'",
                                    "substitution member $what dropped from $droppedFrom",
                                    m.line,
                                )
                        }
                    }
                    if (unionName == null || sharesTypeUnion(name)) return@forEach
                    val claimed = at(el.path) { claimTopLevel(unionName, where, el.line) }
                    if (claimed) result += headUnion(unionName, head, where, members, el.doc)
                }
            return result
        }

        /**
         * What a substitution member's [type], not a complex type of the inputs, is: a simple type
         * (an XSD builtin, `xs:anyType` read as a string, or a declared simple type) or else an
         * unresolved one.
         */
        private fun memberTypeKind(type: QName): String =
            if (
                type.namespace == ImportTypes.XS ||
                    docsByNamespace[type.namespace]?.simpleTypes?.any { it.name == type.local } ==
                        true
            )
                "simple type"
            else "unresolved type"

        /**
         * A head's union of its member types, reporting each member element whose name the
         * regenerated choice will not reproduce.
         */
        private fun headUnion(
            name: String,
            head: HeadMembers,
            where: String,
            memberElements: List<XElement>,
            unionDoc: String?,
        ): UnitUnion {
            val members = head.members.map { UnionMember(memberRef(it)) }
            memberElements.forEach { el ->
                val t = el.type?.takeIf { it in head.members } ?: return@forEach
                val stem = memberStem(t, typeNames.getValue(t))
                if (el.name != stem) {
                    at(el.path) {
                        diagnostics +=
                            lossy(
                                ImportCodes.APPROXIMATED,
                                where,
                                "member element '${el.name}' has no Schemata equivalent; the " +
                                    "regenerated element will be named '$stem'",
                                el.line,
                            )
                    }
                }
            }
            return UnitUnion(name, members, unionDoc, emptyList())
        }

        /**
         * The element name a union member of the named complex type [type] regenerates as: the
         * type's override when it has one, or the one its root element earns it (see
         * [declarationAt]); otherwise its snake_case name.
         */
        private fun memberStem(type: QName, info: TypeNameInfo): String {
            val overrideText = type.local.removeSuffix("Type")
            if (info.annotation != null) return overrideText
            val root =
                docsByNamespace[type.namespace]?.elements?.firstOrNull {
                    it.ref == null && it.type == type
                }
            return if (root?.name == overrideText) overrideText else Names.snakeCase(info.finalName)
        }

        /**
         * What a choice-only complex type lowering to a union carries that a union cannot: its
         * abstractness, reported as dropped. (One with attributes or mixed content is a record, see
         * [isUnionType].)
         */
        private fun unionExtras(ct: XComplexType, where: String) {
            if (ct.abstract) {
                diagnostics += lossy(ImportCodes.DROPPED, where, "abstract dropped", ct.line)
            }
        }

        /**
         * [ct]'s fields, the declarations nested in its record, and the annotations the record
         * itself takes from its content (`@xsd(all)`).
         */
        private fun fieldsAndNested(
            ct: XComplexType,
            whereCollision: String,
            recordName: String,
            siblings: MutableList<UnitDecl>,
        ): Triple<List<UnitField>, List<UnitDecl>, List<UnitAnnotation>> {
            val nested = mutableListOf<UnitDecl>()
            val claimed = mutableMapOf<String, String>()
            val recordAnnotations = mutableListOf<UnitAnnotation>()
            // Seeds the cycle guard with this type's own identity (when it has one), so a direct
            // self-extension is caught on the first hop, not just a longer cycle back to it.
            val visited = mutableSetOf<QName>()
            if (ct.name != null) visited += QName(doc.targetNamespace, ct.name)
            val fields =
                allFieldsOf(
                    ct,
                    whereCollision,
                    recordName,
                    claimed,
                    nested,
                    siblings,
                    visited,
                    recordAnnotations = recordAnnotations,
                )
            val settled = renameAttributesBesideElements(settleNames(fields, claimed), claimed)
            return Triple(settled, nested, recordAnnotations)
        }

        /**
         * [fields] with each synthesised name settled, in order, now that every named field of the
         * record has claimed its own: `any`, `any_2`… for wildcards, `text` (else `mixed_text`) for
         * mixed text, `attributes` (else `any_attributes`) for an attribute wildcard, each the
         * first still free. One that still collides is reported and dropped.
         */
        private fun settleNames(
            fields: List<UnitField>,
            claimed: MutableMap<String, String>,
        ): List<UnitField> =
            fields.mapNotNull { f ->
                val pending = pendingNames.remove(f.name) ?: return@mapNotNull f
                claimed.remove(f.name)
                val name =
                    when (pending.construct) {
                        MIXED_TEXT -> if ("text" in claimed) "mixed_text" else "text"
                        ANY_ATTRIBUTE ->
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
        private fun pendingName(
            kind: String,
            construct: String,
            claimed: MutableMap<String, String>,
            whereCollision: String,
            line: Int,
        ): String {
            val placeholder = "\u0000${pendingCount++}"
            pendingNames[placeholder] =
                PendingName(kind, construct, whereCollision, line, sourcePath)
            claimed[placeholder] = construct
            return placeholder
        }

        /**
         * Every one of [ct]'s own fields (elements then attributes), resolved through its own
         * extension/restriction chain recursively; used both as a record's own top-level entry and,
         * for an extension, recursively for its base. [visited] guards that chain against a cycle.
         */
        private fun allFieldsOf(
            ct: XComplexType,
            whereCollision: String,
            recordName: String,
            claimed: MutableMap<String, String>,
            nested: MutableList<UnitDecl>,
            siblings: MutableList<UnitDecl>,
            visited: MutableSet<QName>,
            namespace: String? = doc.targetNamespace,
            recordAnnotations: MutableList<UnitAnnotation>? = null,
        ): List<UnitField> {
            // An abstract type with concrete descendants is not dropped: it is a union of them.
            if (ct.abstract && ct.name?.let { heads.types[QName(namespace, it)] } == null) {
                diagnostics +=
                    lossy(ImportCodes.DROPPED, whereCollision, "abstract dropped", ct.line)
            }
            val elementFields =
                contentFields(
                    ct,
                    ct.content,
                    whereCollision,
                    recordName,
                    claimed,
                    nested,
                    siblings,
                    visited,
                    recordAnnotations,
                )
            val mixedFields =
                listOfNotNull(if (ct.mixed) mixedText(claimed, whereCollision, ct.line) else null)
            val attributeFields =
                expandAttributeUses(ct.attributes).mapNotNull { use ->
                    when (use) {
                        is XAttributeUse.Attribute ->
                            attribute(use.attribute, claimed, whereCollision, nested)
                        is XAttributeUse.GroupRef -> null
                        is XAttributeUse.AnyAttribute -> anyAttributes(use, claimed, whereCollision)
                    }
                }
            // A complex restriction keeps the base's attributes it does not redeclare, and so
            // does simple content derived from a complex type, whose base declares attributes too.
            val inheritedFrom =
                when (val c = ct.content) {
                    is XContent.Restriction ->
                        c.base.takeIf { !c.simple || isComplexTypeRef(c.base) }
                    is XContent.Extension -> c.base.takeIf { c.simple && isComplexTypeRef(c.base) }
                    else -> null
                }
            val inheritedFields =
                inheritedFrom
                    ?.let { inheritedAttributes(it, ct.attributes) }
                    .orEmpty()
                    .mapNotNull { (path, a) ->
                        at(path) { attribute(a, claimed, whereCollision, nested) }
                    }
            return elementFields + mixedFields + attributeFields + inheritedFields
        }

        /**
         * The character data of a mixed type as `text: string?` (`mixed_text` when a named field
         * takes `text`, see [settleNames]), marked `@xsd(mixed)`. A record has one, however many
         * types along its extension chain are mixed.
         */
        private fun mixedText(
            claimed: MutableMap<String, String>,
            whereCollision: String,
            line: Int,
        ): UnitField? {
            if (MIXED_TEXT in claimed.values) return null
            return UnitField(
                pendingName("mixed text", MIXED_TEXT, claimed, whereCollision, line),
                UnitType.Scalar("string", emptyList()),
                true,
                null,
                null,
                listOf(UnitAnnotation("xsd", "mixed", null)),
            )
        }

        /**
         * An attribute wildcard as `attributes: map<string, string>` (`any_attributes` when a named
         * field takes `attributes`, see [settleNames]), marked `@xsd(any_attribute)`. A record has
         * one, however many types along its extension chain declare a wildcard.
         */
        private fun anyAttributes(
            use: XAttributeUse.AnyAttribute,
            claimed: MutableMap<String, String>,
            whereCollision: String,
        ): UnitField? {
            if (ANY_ATTRIBUTE in claimed.values) return null
            val string = UnitType.Scalar("string", emptyList())
            return UnitField(
                pendingName("wildcard attribute", ANY_ATTRIBUTE, claimed, whereCollision, use.line),
                UnitType.MapOf(string, string, false, emptyList()),
                false,
                null,
                null,
                XsdWildcards.anyAttribute(use),
            )
        }

        /**
         * An element wildcard as `any` (then `any_2`, `any_3`… past the first name the record's
         * named fields leave free, see [settleNames]), marked `@xsd(any)`: `list<string>` when it
         * repeats, else `string`, nullable when optional.
         */
        private fun anyField(
            particle: XParticle.Any,
            claimed: MutableMap<String, String>,
            whereCollision: String,
        ): UnitField? {
            val string = UnitType.Scalar("string", emptyList())
            val repeated = particle.maxOccurs != 1
            return UnitField(
                pendingName("wildcard", "xs:any", claimed, whereCollision, particle.line),
                if (repeated)
                    UnitType.ListOf(
                        string,
                        false,
                        listRefinements(particle.minOccurs, particle.maxOccurs),
                    )
                else string,
                !repeated && particle.minOccurs == 0,
                null,
                null,
                XsdWildcards.any(particle),
            )
        }

        /**
         * The attributes a type derived from the complex type [base] (a complex restriction, or
         * simple content) keeps without declaring them: every one [base] has, its own and those it
         * derives, that [own] neither redeclares nor prohibits (a prohibition is itself a
         * redeclaration), each with the file that declares it.
         */
        private fun inheritedAttributes(
            base: QName,
            own: List<XAttributeUse>,
        ): List<Pair<String, XAttribute>> {
            val redeclared =
                expandAttributeUses(own).filterIsInstance<XAttributeUse.Attribute>().mapNotNull {
                    it.attribute.name ?: it.attribute.ref?.local
                }
            val result = mutableListOf<Pair<String, XAttribute>>()
            val seen = mutableSetOf<QName>()
            var next: QName? = base
            while (next != null && seen.add(next)) {
                val targetDoc = docsByNamespace[next.namespace] ?: break
                val ct = targetDoc.complexTypes.firstOrNull { it.name == next!!.local } ?: break
                val path = ct.path.ifEmpty { targetDoc.path }
                expandAttributeUses(ct.attributes)
                    .filterIsInstance<XAttributeUse.Attribute>()
                    .forEach {
                        val name = it.attribute.name ?: it.attribute.ref?.local
                        if (
                            name !in redeclared &&
                                result.none { (_, a) -> (a.name ?: a.ref?.local) == name }
                        ) {
                            result += path to it.attribute
                        }
                    }
                next =
                    when (val content = ct.content) {
                        is XContent.Extension -> content.base
                        is XContent.Restriction -> content.base
                        else -> null
                    }
            }
            return result
        }

        /**
         * The fields [content] declares. An `xs:all` that is the record's own content (when
         * [recordAnnotations] collects the record's annotations) gives the record `@xsd(all)`; one
         * reached through an extension base is imported as a sequence and noted.
         */
        private fun contentFields(
            ct: XComplexType,
            content: XContent,
            whereCollision: String,
            recordName: String,
            claimed: MutableMap<String, String>,
            nested: MutableList<UnitDecl>,
            siblings: MutableList<UnitDecl>,
            visited: MutableSet<QName>,
            recordAnnotations: MutableList<UnitAnnotation>?,
        ): List<UnitField> =
            when (content) {
                is XContent.Sequence ->
                    sequenceFields(
                        recordName,
                        content.particles,
                        whereCollision,
                        claimed,
                        nested,
                        siblings,
                    )
                is XContent.All -> {
                    if (recordAnnotations != null) {
                        recordAnnotations += UnitAnnotation("xsd", "all", null)
                    } else {
                        diagnostics +=
                            lossy(
                                ImportCodes.APPROXIMATED,
                                whereCollision,
                                "xs:all imported as a sequence",
                                ct.line,
                            )
                    }
                    // xs:all holds each element at most once, so an element is required or not.
                    val particles =
                        content.particles.map { p ->
                            if (p !is XParticle.Element) p
                            else
                                XParticle.Element(
                                    p.element.copy(
                                        minOccurs = if (p.element.minOccurs == 0) 0 else 1
                                    )
                                )
                        }
                    sequenceFields(recordName, particles, whereCollision, claimed, nested, siblings)
                }
                is XContent.Empty -> emptyList()
                is XContent.Choice ->
                    if (isUnionType(ct)) {
                        // An extension base that is itself a union: not supported, so its own
                        // content is simply dropped rather than flattened.
                        diagnostics +=
                            lossy(
                                ImportCodes.DROPPED,
                                whereCollision,
                                "extension of a choice-shaped type dropped",
                                ct.line,
                            )
                        emptyList()
                    } else {
                        // A record whose content is a bare choice holds it as a sequence would.
                        sequenceFields(
                            recordName,
                            listOf(
                                XParticle.Nested(
                                    content,
                                    content.minOccurs,
                                    content.maxOccurs,
                                    ct.line,
                                )
                            ),
                            whereCollision,
                            claimed,
                            nested,
                            siblings,
                        )
                    }
                is XContent.Extension ->
                    extensionFields(
                        ct,
                        content,
                        whereCollision,
                        recordName,
                        claimed,
                        nested,
                        siblings,
                        visited,
                    )
                is XContent.Restriction ->
                    if (content.simple) {
                        simpleContentFields(
                            "restriction",
                            content.base,
                            content.facets,
                            content.line,
                            whereCollision,
                            claimed,
                        )
                    } else {
                        restrictionFields(
                            content,
                            recordName,
                            whereCollision,
                            claimed,
                            nested,
                            siblings,
                        )
                    }
            }

        private fun sequenceFields(
            recordName: String,
            particles: List<XParticle>,
            whereCollision: String,
            claimed: MutableMap<String, String>,
            nested: MutableList<UnitDecl>,
            siblings: MutableList<UnitDecl>,
        ): List<UnitField> {
            val result = mutableListOf<UnitField>()
            expandParticles(particles, whereCollision).forEach { particle ->
                when (particle) {
                    is XParticle.Element ->
                        field(particle.element, claimed, whereCollision, nested, siblings)?.let {
                            result += it
                        }
                    is XParticle.Any ->
                        anyField(particle, claimed, whereCollision)?.let { result += it }
                    is XParticle.GroupRef -> Unit // only an unresolved ref survives expandParticles
                    is XParticle.Nested -> {
                        val content = particle.content
                        when (content) {
                            is XContent.Choice ->
                                result +=
                                    inlineChoiceFields(
                                        recordName,
                                        content,
                                        particle,
                                        whereCollision,
                                        claimed,
                                        nested,
                                        siblings,
                                    )
                            is XContent.Sequence ->
                                if (particle.minOccurs == 1 && particle.maxOccurs == 1) {
                                    result +=
                                        sequenceFields(
                                            recordName,
                                            content.particles,
                                            whereCollision,
                                            claimed,
                                            nested,
                                            siblings,
                                        )
                                } else {
                                    groupField(
                                            content,
                                            particle,
                                            whereCollision,
                                            claimed,
                                            nested,
                                            siblings,
                                        )
                                        ?.let { result += it }
                                }
                            is XContent.All -> {
                                diagnostics +=
                                    lossy(
                                        ImportCodes.APPROXIMATED,
                                        whereCollision,
                                        "xs:all flattened into the record",
                                        particle.line,
                                    )
                                result +=
                                    sequenceFields(
                                        recordName,
                                        content.particles,
                                        whereCollision,
                                        claimed,
                                        nested,
                                        siblings,
                                    )
                            }
                            else -> Unit
                        }
                    }
                }
            }
            return result
        }

        /**
         * A sequence nested in another that occurs other than exactly once, or a repeated reference
         * to a sequence group: a record nested in the enclosing one, named for the group or for the
         * sequence's first element (`LatGroup`), held by a field of the same stem (`lat_group`)
         * that is a list, optional, or plain as the sequence occurs. `null` when the field's name
         * is already taken, which is reported.
         */
        private fun groupField(
            content: XContent.Sequence,
            particle: XParticle.Nested,
            whereCollision: String,
            claimed: MutableMap<String, String>,
            nested: MutableList<UnitDecl>,
            siblings: MutableList<UnitDecl>,
        ): UnitField? {
            val first =
                content.particles.firstNotNullOfOrNull {
                    (it as? XParticle.Element)?.element?.let { el -> el.name ?: el.ref?.local }
                }
            val base =
                particle.name?.let(ImportNames::upperCamel)
                    ?: ((first?.let(ImportNames::upperCamel) ?: "") + "Group")
            val stem =
                particle.name?.let(ImportNames::lowerSnake)
                    ?: (ImportNames.lowerSnake(first ?: "group") + "_group")
            // A second group of the same stem is numbered, record and field together.
            var n = 1
            while (
                nested.any { it.name == numbered(base, n, "") } ||
                    fieldNameFor(numbered(stem, n, "_")).name in claimed
            ) n++
            val recordName = numbered(base, n, "")
            val original = numbered(stem, n, "_")
            val construct = particle.name?.let { "group '$it'" } ?: "nested sequence"
            val (name, annotations) =
                nameAndClaim(original, "group", construct, claimed, whereCollision, particle.line)
                    ?: return null
            diagnostics +=
                lossy(
                    ImportCodes.APPROXIMATED,
                    whereCollision,
                    (particle.name?.let { "repeated group '$it'" } ?: "nested sequence") +
                        " imported as record '$recordName' in field '$name'",
                    particle.line,
                )
            val ownClaimed = mutableMapOf<String, String>()
            val ownNested = mutableListOf<UnitDecl>()
            val fields =
                sequenceFields(
                    recordName,
                    content.particles,
                    whereCollision,
                    ownClaimed,
                    ownNested,
                    siblings,
                )
            nested +=
                UnitRecord(
                    recordName,
                    settleNames(fields, ownClaimed),
                    ownNested,
                    null,
                    emptyList(),
                )
            val ref = UnitType.Ref(recordName)
            return if (particle.maxOccurs != 1) {
                UnitField(
                    name,
                    UnitType.ListOf(
                        ref,
                        false,
                        listRefinements(particle.minOccurs, particle.maxOccurs),
                    ),
                    false,
                    null,
                    null,
                    annotations,
                )
            } else UnitField(name, ref, particle.minOccurs == 0, null, null, annotations)
        }

        /** [base] for the first of a kind, then [base] numbered from 2 after [separator]. */
        private fun numbered(base: String, n: Int, separator: String): String =
            if (n == 1) base else "$base$separator$n"

        /**
         * A bare `xs:choice` found directly inside a sequence, with no wrapping element: a union
         * synthesised as a top-level sibling when every member has a complex type or is itself a
         * model group, named `[recordName]Choice` (numbered past the record's first inline choice,
         * however deeply nested); otherwise every member is flattened to an optional field of the
         * enclosing record.
         */
        private fun inlineChoiceFields(
            recordName: String,
            choice: XContent.Choice,
            particle: XParticle.Nested,
            whereCollision: String,
            claimed: MutableMap<String, String>,
            nested: MutableList<UnitDecl>,
            siblings: MutableList<UnitDecl>,
        ): List<UnitField> {
            if (choice.particles.all { it is XParticle.Any }) {
                return wildcardChoiceFields(
                    choice,
                    particle.minOccurs,
                    particle.maxOccurs,
                    claimed,
                    whereCollision,
                )
            }
            val index = choiceCounts.merge(recordName, 1, Int::plus)!!
            val branches = expandParticles(choice.particles, whereCollision)
            val members = branches.filter { it is XParticle.Element || it is XParticle.Nested }
            val allComplex =
                members.isNotEmpty() &&
                    members.all { p ->
                        val el = (p as? XParticle.Element)?.element ?: return@all true
                        el.inlineComplex != null || (el.type != null && isComplexTypeRef(el.type))
                    }
            val fieldName = if (index == 1) "choice" else "choice_$index"
            if (allComplex) {
                val unionName =
                    if (index == 1) "${recordName}Choice" else "${recordName}Choice$index"
                val claim =
                    nameAndClaim(
                        fieldName,
                        "choice",
                        whereCollision,
                        claimed,
                        whereCollision,
                        particle.line,
                    ) ?: return emptyList()
                diagnostics +=
                    lossy(
                        ImportCodes.APPROXIMATED,
                        whereCollision,
                        "inline choice has no Schemata equivalent; imported as union '$unionName' " +
                            "in field '$fieldName'",
                        particle.line,
                    )
                val union =
                    unionFromChoice(choice, unionName, "union '$unionName'", null, siblings, false)
                siblings += union
                val (name, annotations) = claim
                val type: UnitType =
                    if (particle.maxOccurs != 1) {
                        UnitType.ListOf(
                            UnitType.Ref(unionName),
                            false,
                            listRefinements(particle.minOccurs, particle.maxOccurs),
                        )
                    } else UnitType.Ref(unionName)
                val nullable = particle.maxOccurs == 1 && particle.minOccurs == 0
                return listOf(UnitField(name, type, nullable, null, null, annotations))
            }
            diagnostics +=
                lossy(
                    ImportCodes.APPROXIMATED,
                    whereCollision,
                    "inline choice has no Schemata equivalent; members imported as optional fields",
                    particle.line,
                )
            // Each branch, a wildcard among them, is an optional field; a repeated choice repeats
            // every branch, so a branch's occurrence is multiplied by the choice's and a branch
            // that may repeat is a list.
            return branches.flatMap {
                when (it) {
                    is XParticle.Element ->
                        listOfNotNull(
                            field(
                                it.element.copy(
                                    minOccurs = 0,
                                    maxOccurs = times(it.element.maxOccurs, particle.maxOccurs),
                                ),
                                claimed,
                                whereCollision,
                                nested,
                                siblings,
                            )
                        )
                    is XParticle.Any ->
                        listOfNotNull(
                            anyField(
                                it.copy(
                                    minOccurs = 0,
                                    maxOccurs = times(it.maxOccurs, particle.maxOccurs),
                                ),
                                claimed,
                                whereCollision,
                            )
                        )
                    is XParticle.Nested ->
                        sequenceFields(
                            recordName,
                            listOf(
                                it.copy(
                                    minOccurs = 0,
                                    maxOccurs = times(it.maxOccurs, particle.maxOccurs),
                                )
                            ),
                            whereCollision,
                            claimed,
                            nested,
                            siblings,
                        )
                    is XParticle.GroupRef ->
                        emptyList() // only an unresolved ref survives expansion
                }
            }
        }

        /** Two occurrence bounds multiplied, `null` (unbounded) when either is. */
        private fun times(a: Int?, b: Int?): Int? = if (a == null || b == null) null else a * b

        private fun isComplexTypeRef(qname: QName): Boolean {
            if (qname.namespace == ImportTypes.XS) return false
            val targetDoc = docsByNamespace[qname.namespace] ?: return false
            return targetDoc.complexTypes.any { it.name == qname.local }
        }

        private fun extensionFields(
            ct: XComplexType,
            ext: XContent.Extension,
            whereCollision: String,
            recordName: String,
            claimed: MutableMap<String, String>,
            nested: MutableList<UnitDecl>,
            siblings: MutableList<UnitDecl>,
            visited: MutableSet<QName>,
        ): List<UnitField> {
            if (ext.simple) {
                return simpleContentFields(
                    "extension",
                    ext.base,
                    ext.facets,
                    ext.line,
                    whereCollision,
                    claimed,
                )
            }
            // A base already on the chain (a direct self-extension, or a longer cycle back to it):
            // reported once, here, where the cycle closes; the type still imports with its own
            // content only, as if the (unresolvable) extension weren't there.
            if (!visited.add(ext.base)) {
                diagnostics +=
                    lossy(
                        ImportCodes.UNRESOLVED,
                        whereCollision,
                        "extension of '${ext.base.local}' cannot be resolved; the base chain is cyclic",
                        ext.line,
                    )
                return sequenceFields(
                    recordName,
                    ext.particles,
                    whereCollision,
                    claimed,
                    nested,
                    siblings,
                )
            }
            val anyType = ext.base == QName(ImportTypes.XS, "anyType")
            val baseFields =
                resolveExtensionBase(
                    ext.base,
                    whereCollision,
                    ext.line,
                    claimed,
                    nested,
                    siblings,
                    visited,
                )
            if (!anyType) {
                diagnostics +=
                    lossy(
                        ImportCodes.APPROXIMATED,
                        whereCollision,
                        "extension of '${ext.base.local}' has no Schemata equivalent; base fields " +
                            "flattened into the record",
                        ext.line,
                    )
            }
            val ownFields =
                sequenceFields(recordName, ext.particles, whereCollision, claimed, nested, siblings)
            return baseFields + ownFields
        }

        /**
         * A `simpleContent` [kind] (`extension` or `restriction`) of [base]: a record with one
         * `value` field of the base's simple type narrowed by [facets], beside the type's own
         * attributes (and, for a complex base, those it inherits; see [allFieldsOf]). A complex
         * base is followed down its own simple content to the simple type at the root of the chain,
         * see [simpleContentValue].
         */
        private fun simpleContentFields(
            kind: String,
            base: QName,
            facets: List<XFacet>,
            line: Int,
            whereCollision: String,
            claimed: MutableMap<String, String>,
        ): List<UnitField> {
            diagnostics +=
                lossy(
                    ImportCodes.APPROXIMATED,
                    whereCollision,
                    "simpleContent $kind of '${base.local}' has no Schemata equivalent; " +
                        "imported as a record with a 'value' field",
                    line,
                )
            val refined = simpleContentValue(kind, base, facets, line, whereCollision)
            val claim =
                nameAndClaim("value", "field", whereCollision, claimed, whereCollision, line)
                    ?: return emptyList()
            val (name, annotations) = claim
            val list = listOfNotNull(listAnnotation(refined))
            return listOf(UnitField(name, refined, false, null, null, annotations + list))
        }

        /**
         * The `value` type of a simple content [kind] of [base] narrowed by [facets]. A complex
         * [base] with simple content of its own is followed, through as many types as the chain
         * has, to the simple type at its root; the facets along the way all apply, the nearest
         * type's winning where two levels set the same one. A chain that reaches a complex type
         * with element content, or a cycle, is noted and imported as a string.
         */
        private fun simpleContentValue(
            kind: String,
            base: QName,
            facets: List<XFacet>,
            line: Int,
            whereCollision: String,
        ): UnitType {
            val string = UnitType.Scalar("string", emptyList())
            val levels = mutableListOf(facets)
            val seen = mutableSetOf<QName>()
            var next = base
            while (isComplexTypeRef(next)) {
                val ct =
                    docsByNamespace.getValue(next.namespace).complexTypes.first {
                        it.name == next.local
                    }
                val (nextBase, nextFacets) =
                    when (val c = ct.content) {
                        is XContent.Extension -> if (c.simple) c.base to c.facets else null
                        is XContent.Restriction -> if (c.simple) c.base to c.facets else null
                        else -> null
                    } ?: (null to emptyList())
                if (nextBase == null || !seen.add(next)) {
                    diagnostics +=
                        lossy(
                            ImportCodes.APPROXIMATED,
                            whereCollision,
                            "simpleContent $kind of complex type '${base.local}' imported as string",
                            line,
                        )
                    return restrict(string, facets, line, sourcePath, whereCollision)
                }
                levels += nextFacets
                next = nextBase
            }
            val root = resolveSimpleTypeByQName(next, whereCollision, line) ?: string
            // Each facet kind from the nearest level that sets it.
            val combined =
                levels
                    .flatMap { level -> level.map { it.name }.distinct().map { it to level } }
                    .distinctBy { it.first }
                    .flatMap { (name, level) -> level.filter { it.name == name } }
            return restrict(root, combined, line, sourcePath, whereCollision)
        }

        /** `@xsd(list)` for a field whose value is a list simple type's; `null` otherwise. */
        private fun listAnnotation(type: UnitType): UnitAnnotation? =
            if (type is UnitType.ListOf) UnitAnnotation("xsd", "list", null) else null

        private fun resolveExtensionBase(
            baseQName: QName,
            whereCollision: String,
            line: Int,
            claimed: MutableMap<String, String>,
            nested: MutableList<UnitDecl>,
            siblings: MutableList<UnitDecl>,
            visited: MutableSet<QName>,
        ): List<UnitField> {
            // xs:anyType, the root of every derivation, contributes no fields of its own.
            if (baseQName == QName(ImportTypes.XS, "anyType")) return emptyList()
            val targetDoc = docsByNamespace[baseQName.namespace]
            val baseCt = targetDoc?.complexTypes?.firstOrNull { it.name == baseQName.local }
            if (baseCt == null) {
                diagnostics +=
                    lossy(
                        ImportCodes.UNRESOLVED,
                        whereCollision,
                        "type '${baseQName.local}' cannot be resolved",
                        line,
                    )
                return emptyList()
            }
            // The base's own lines are in its own document, so its diagnostics point there.
            val saved = sourcePath
            sourcePath = targetDoc.path
            try {
                return allFieldsOf(
                    baseCt,
                    whereCollision,
                    baseQName.local,
                    claimed,
                    nested,
                    siblings,
                    visited,
                    baseQName.namespace,
                )
            } finally {
                sourcePath = saved
            }
        }

        private fun restrictionFields(
            res: XContent.Restriction,
            recordName: String,
            whereCollision: String,
            claimed: MutableMap<String, String>,
            nested: MutableList<UnitDecl>,
            siblings: MutableList<UnitDecl>,
        ): List<UnitField> {
            // A restriction of xs:anyType is exactly its own content.
            if (res.base != QName(ImportTypes.XS, "anyType")) {
                diagnostics +=
                    lossy(
                        ImportCodes.APPROXIMATED,
                        whereCollision,
                        "restriction of '${res.base.local}' has no Schemata equivalent; its own " +
                            "content is used",
                        res.line,
                    )
            }
            return sequenceFields(
                recordName,
                res.particles,
                whereCollision,
                claimed,
                nested,
                siblings,
            )
        }

        /** A plain anonymous complex type, nested inside its declaring record. */
        private fun buildNestedRecord(
            ct: XComplexType,
            name: String,
            siblings: MutableList<UnitDecl>,
        ): UnitRecord {
            val (fields, nested, annotations) =
                fieldsAndNested(ct, "complex type '$name'", name, siblings)
            return UnitRecord(name, fields, nested, ct.doc, annotations)
        }

        /**
         * An element's anonymous complex type, nested in the enclosing record under the element's
         * UpperCamel name: a union when its content is a bare choice, a record otherwise.
         */
        private fun inlineDeclaration(
            ct: XComplexType,
            elementName: String,
            nested: MutableList<UnitDecl>,
            siblings: MutableList<UnitDecl>,
        ): UnitType.Ref {
            val name = ImportNames.upperCamel(elementName)
            val content = ct.content
            if (content is XContent.Choice && isUnionType(ct)) {
                unionExtras(ct, "union '$name'")
                nested +=
                    unionFromChoice(
                        content,
                        name,
                        "union '$name'",
                        ct.doc,
                        siblings,
                        checkMismatch = true,
                    )
            } else {
                nested += buildNestedRecord(ct, name, siblings)
            }
            return UnitType.Ref(name)
        }

        /**
         * An anonymous complex type hoisted out of a union member, as a top-level, non-root record.
         */
        private fun buildHoistedRecord(
            ct: XComplexType,
            name: String,
            siblings: MutableList<UnitDecl>,
        ): UnitRecord {
            val (fields, nested, annotations) =
                fieldsAndNested(ct, "complex type '$name'", name, siblings)
            return UnitRecord(
                name,
                fields,
                nested,
                ct.doc,
                listOf(UnitAnnotation("xsd", "root", "false")) + annotations,
            )
        }

        /**
         * Whether [ct], whose content is a bare choice, lowers to a union: only when the choice
         * occurs once, has a branch that can be a member (a union member carries no `@xsd(any)`),
         * and the type has no attributes, attribute wildcard, or mixed content for a union to lose.
         * Otherwise the type is a record holding the choice as a sequence would.
         */
        private fun isUnionType(ct: XComplexType): Boolean {
            val choice = ct.content as? XContent.Choice ?: return false
            return choice.maxOccurs == 1 &&
                ct.attributes.isEmpty() &&
                !ct.mixed &&
                choice.particles.any { it !is XParticle.Any }
        }

        /**
         * The fields of a choice of nothing but wildcards occurring [minOccurs] to [maxOccurs]
         * times: one `@xsd(any)` field per wildcard, its occurrence the wildcard's times the
         * choice's, as the sequence it amounts to.
         */
        private fun wildcardChoiceFields(
            choice: XContent.Choice,
            minOccurs: Int,
            maxOccurs: Int?,
            claimed: MutableMap<String, String>,
            whereCollision: String,
        ): List<UnitField> =
            choice.particles.filterIsInstance<XParticle.Any>().mapNotNull { any ->
                anyField(
                    any.copy(
                        minOccurs = any.minOccurs * minOccurs,
                        maxOccurs = times(any.maxOccurs, maxOccurs),
                    ),
                    claimed,
                    whereCollision,
                )
            }

        /** A choice's branch on its way to a union member. */
        private sealed interface ChoiceEntry

        /** An element branch: the element, the type it lowers to, and its stem. */
        private class Branch(
            val el: XElement,
            val name: String,
            val type: UnitType,
            val stem: String,
        ) : ChoiceEntry

        /** A branch whose member is already settled, such as a sequence branch's record. */
        private class Settled(val member: UnionMember) : ChoiceEntry

        /**
         * A union from [choice]'s members, in order. [checkMismatch] reports a member element whose
         * name doesn't match what the XSD target would regenerate (SCH2403); turned off for a
         * synthesised, never-named inline choice, where there is nothing for a member name to
         * round-trip against. Two or more element branches lowering to one member (the same type)
         * cannot be told apart by type, so each of them is wrapped in a record of its own (see
         * [sharedBranchRecord]) and the union lists those.
         */
        private fun unionFromChoice(
            choice: XContent.Choice,
            name: String,
            unionWhere: String,
            unionDoc: String?,
            siblings: MutableList<UnitDecl>,
            checkMismatch: Boolean,
        ): UnitUnion {
            val entries = mutableListOf<ChoiceEntry>()
            fun member(particle: XParticle) {
                when (particle) {
                    is XParticle.Element -> {
                        val el =
                            withHeadType(
                                if (particle.element.ref != null) {
                                    resolveElementRef(particle.element, unionWhere) ?: return
                                } else particle.element
                            )
                        val elementName = el.name ?: "member"
                        val (ownType, stem) = memberTypeAndStem(el, unionWhere, siblings) ?: return
                        val type = particle.element.ref?.let(::elementHeadType) ?: ownType
                        entries += Branch(el, elementName, type, stem)
                    }
                    is XParticle.Any ->
                        diagnostics +=
                            lossy(ImportCodes.DROPPED, unionWhere, "xs:any dropped", particle.line)
                    is XParticle.Nested ->
                        when (val content = particle.content) {
                            // A choice inside a choice offers its branches as the outer one's.
                            is XContent.Choice ->
                                expandParticles(content.particles, unionWhere).forEach(::member)
                            is XContent.Sequence,
                            is XContent.All -> {
                                val ref =
                                    branchRecord(content, particle, unionWhere, siblings) ?: return
                                entries += Settled(UnionMember(ref, null))
                            }
                            else -> Unit
                        }
                    is XParticle.GroupRef -> Unit // only an unresolved ref survives expansion
                }
            }
            expandParticles(choice.particles, unionWhere).forEach(::member)
            val shared =
                entries.filterIsInstance<Branch>().groupBy { it.stem }.filterValues { it.size > 1 }
            val members =
                entries.map { e ->
                    if (e !is Branch) return@map (e as Settled).member
                    val group = shared[e.stem]
                    val (type, stem) =
                        if (group == null) e.type to e.stem
                        else {
                            val ref = sharedBranchRecord(e, group, unionWhere, siblings)
                            ref to Names.snakeCase(ref.name)
                        }
                    if (checkMismatch && e.name != stem) {
                        diagnostics +=
                            lossy(
                                ImportCodes.APPROXIMATED,
                                unionWhere,
                                "member element '${e.name}' has no Schemata equivalent; the " +
                                    "regenerated element will be named '$stem'",
                                e.el.line,
                            )
                    }
                    UnionMember(type, e.el.doc)
                }
            return UnitUnion(name, members, unionDoc, emptyList())
        }

        /**
         * [branch], one of the [group] of a choice's branches sharing a member type, as a
         * top-level, non-root record named after its element (numbered past a name already taken,
         * as a branch record is) holding the type in a `value` field, and noted.
         */
        private fun sharedBranchRecord(
            branch: Branch,
            group: List<Branch>,
            unionWhere: String,
            siblings: MutableList<UnitDecl>,
        ): UnitType.Ref {
            val base = ImportNames.upperCamel(branch.name)
            var n = 1
            while (numbered(base, n, "") in topLevelNames) n++
            val name = numbered(base, n, "")
            claimTopLevel(name, "element '${branch.name}'", branch.el.line)
            val other = if (branch === group[0]) group[1] else branch
            val typeName =
                when (val t = branch.type) {
                    is UnitType.Ref -> t.name
                    is UnitType.Scalar -> t.builtin
                    else -> branch.stem
                }
            diagnostics +=
                lossy(
                    ImportCodes.APPROXIMATED,
                    unionWhere,
                    "members '${group[0].name}' and '${other.name}' share type '$typeName'; each " +
                        "imported as a record holding it",
                    branch.el.line,
                )
            siblings +=
                UnitRecord(
                    name,
                    listOf(UnitField("value", branch.type, false, null, null, emptyList())),
                    emptyList(),
                    null,
                    listOf(UnitAnnotation("xsd", "root", "false")),
                )
            return UnitType.Ref(name)
        }

        /**
         * A choice branch that is a sequence or an `xs:all`: a top-level, non-root record named for
         * its first element (`WGroup`), referenced as the union's member. `null` when that name is
         * already a top-level declaration's, which is reported.
         */
        private fun branchRecord(
            content: XContent,
            particle: XParticle.Nested,
            unionWhere: String,
            siblings: MutableList<UnitDecl>,
        ): UnitType.Ref? {
            val particles =
                when (content) {
                    is XContent.Sequence -> content.particles
                    is XContent.All -> content.particles
                    else -> return null
                }
            val first =
                particles.firstNotNullOfOrNull {
                    (it as? XParticle.Element)?.element?.let { el -> el.name ?: el.ref?.local }
                }
            // A second branch record of the same name, in this union or another, is numbered.
            val base = (first?.let(ImportNames::upperCamel) ?: "") + "Group"
            var n = 1
            while (numbered(base, n, "") in topLevelNames) n++
            val name = numbered(base, n, "")
            if (!claimTopLevel(name, "choice branch of $unionWhere", particle.line)) return null
            diagnostics +=
                lossy(
                    ImportCodes.APPROXIMATED,
                    unionWhere,
                    "choice branch imported as record '$name'",
                    particle.line,
                )
            val ct =
                XComplexType(
                    name = null,
                    doc = null,
                    content = content,
                    attributes = emptyList(),
                    mixed = false,
                    abstract = false,
                    line = particle.line,
                )
            siblings += buildHoistedRecord(ct, name, siblings)
            return UnitType.Ref(name)
        }

        /**
         * A union member's type and its regenerated-name stem (the XSD target's own element name).
         */
        private fun memberTypeAndStem(
            el: XElement,
            unionWhere: String,
            siblings: MutableList<UnitDecl>,
        ): Pair<UnitType, String>? {
            if (el.type != null) {
                val qname = el.type
                if (qname.namespace == ImportTypes.XS) {
                    // A builtin member is typed as a field of it would be, a bare decimal taking
                    // the default precision and scale.
                    val type = resolveTypeRef(qname, unionWhere, el.line) as? UnitType.Scalar
                    if (type == null) {
                        diagnostics +=
                            lossy(
                                ImportCodes.UNRESOLVED,
                                unionWhere,
                                "type '${qname.local}' cannot be resolved",
                                el.line,
                            )
                        return null
                    }
                    return type to type.builtin
                }
                val targetDoc = docsByNamespace[qname.namespace]
                if (targetDoc == null) {
                    diagnostics +=
                        lossy(
                            ImportCodes.UNRESOLVED,
                            unionWhere,
                            "type '${qname.local}' cannot be resolved",
                            el.line,
                        )
                    return null
                }
                val ct = targetDoc.complexTypes.firstOrNull { it.name == qname.local }
                if (ct != null) {
                    val info = typeNames.getValue(QName(targetDoc.targetNamespace, qname.local))
                    val type =
                        headType(qname) ?: UnitType.Ref(qualifiedTypeName(targetDoc, qname.local))
                    return type to regeneratedElementName(qname.local, info)
                }
                val st = targetDoc.simpleTypes.firstOrNull { it.name == qname.local }
                if (st != null && isEnum(st)) {
                    val info = typeNames.getValue(QName(targetDoc.targetNamespace, qname.local))
                    return UnitType.Ref(qualifiedTypeName(targetDoc, qname.local)) to
                        regeneratedElementName(qname.local, info)
                }
                if (st != null) {
                    val type = resolveNamedSimpleType(st, targetDoc.path, unionWhere, setOf(qname))
                    return simpleMember(type, unionWhere, el.line)
                }
                diagnostics +=
                    lossy(
                        ImportCodes.UNRESOLVED,
                        unionWhere,
                        "type '${qname.local}' cannot be resolved",
                        el.line,
                    )
                return null
            }
            if (el.inlineComplex != null) {
                val elementName = el.name ?: "member"
                val hoistedName = ImportNames.upperCamel(elementName)
                if (!claimTopLevel(hoistedName, "element '$elementName'", el.line)) return null
                siblings += buildHoistedRecord(el.inlineComplex, hoistedName, siblings)
                return UnitType.Ref(hoistedName) to ImportNames.lowerSnake(elementName)
            }
            if (el.inlineSimple != null) {
                if (isEnum(el.inlineSimple)) {
                    val elementName = el.name ?: "member"
                    val hoistedName = ImportNames.upperCamel(elementName)
                    if (!claimTopLevel(hoistedName, "element '$elementName'", el.line)) return null
                    if (el.inlineSimple.variety is XVariety.Union) {
                        unionNote(unionWhere, "enum '$hoistedName'", el.line)
                    }
                    siblings += buildInlineEnum(el.inlineSimple, hoistedName, unionWhere)
                    return UnitType.Ref(hoistedName) to ImportNames.lowerSnake(elementName)
                }
                val type = resolveNamedSimpleType(el.inlineSimple, doc.path, unionWhere)
                return simpleMember(type, unionWhere, el.line)
            }
            // An element with no type holds anything, as one typed xs:anyType does; a union
            // member has nowhere to carry the field's `@xsd(any_type)`, so it is a plain string.
            val string = implicitAnyType(unionWhere, el.line)
            return string to string.builtin
        }

        /**
         * A union member of a simple type and its stem: a scalar is named for its builtin; a list,
         * which a union member cannot be, is reported and imported as a string.
         */
        private fun simpleMember(
            type: UnitType,
            unionWhere: String,
            line: Int,
        ): Pair<UnitType, String> =
            when (type) {
                is UnitType.Scalar -> type to type.builtin
                is UnitType.Ref -> type to Names.snakeCase(type.name.substringAfterLast('.'))
                else -> {
                    diagnostics +=
                        lossy(
                            ImportCodes.DROPPED,
                            unionWhere,
                            "list simple type imported as string",
                            line,
                        )
                    UnitType.Scalar("string", emptyList()) to "string"
                }
            }

        /**
         * [original]'s regenerated element or union-member name, exactly as the XSD target writes
         * it.
         */
        private fun regeneratedElementName(original: String, info: TypeNameInfo): String =
            if (info.annotation != null) original.removeSuffix("Type")
            else Names.snakeCase(info.finalName)

        /**
         * An anonymous or named enumeration's values (a union of enumerations' values, see
         * [enumFacets]), in order, with per-value naming and docs.
         */
        private fun buildInlineEnum(st: XSimpleType, name: String, where: String): UnitEnum {
            val values =
                enumValueNames(st).map { (f, field) ->
                    val valueWhere = "enum value '$name.${f.value}'"
                    // A value's @xsd(name) override, when the xsd target would accept it, always
                    // regenerates the original xsd text exactly, so this is never reported; a value
                    // that is not even a valid XML name gets no override (one would only be
                    // rejected), and that is reported instead.
                    if (field.unfixable) {
                        diagnostics +=
                            lossy(
                                ImportCodes.APPROXIMATED,
                                valueWhere,
                                "$valueWhere has no Schemata equivalent; imported as '${field.name}'",
                                f.line,
                            )
                    }
                    UnitEnumValue(field.name, f.doc, listOfNotNull(field.annotation))
                }
            return UnitEnum(name, values, st.doc, emptyList())
        }

        /**
         * [st]'s enumeration values with the names they import as, in order: each lowered as a
         * field name is, its signs spelled first (see [spellSigns]); a value whose name an earlier
         * one already took is numbered (`v_2`), with an override back to its text when that is a
         * valid name.
         */
        private fun enumValueNames(st: XSimpleType): List<Pair<XFacet, FieldName>> {
            val taken = mutableSetOf<String>()
            return enumFacets(st).orEmpty().map { f ->
                val own = fieldNameFor(f.value)
                val spelled = spellSigns(f.value)
                val base =
                    if (spelled == f.value) own else own.copy(name = fieldNameFor(spelled).name)
                var name = base.name
                var n = 2
                while (name in taken) name = "${base.name}_${n++}"
                taken += name
                f to
                    if (name == base.name) base
                    else if (XsdNames.isValidOverride(f.value))
                        FieldName(name, UnitAnnotation("xsd", "name", "\"${f.value}\""), false)
                    else FieldName(name, null, unfixable = true)
            }
        }

        /**
         * Groups referenced by `xs:group ref` expand into their own particles in place,
         * recursively; an unresolved group is reported and dropped. A reference to a sequence group
         * that occurs other than exactly once keeps its occurrence on the `Nested` particle it
         * becomes, named for the group, which lowers to a record of its own; so does a reference to
         * a choice or `xs:all` group, whatever its occurrence.
         */
        private fun expandParticles(particles: List<XParticle>, where: String): List<XParticle> =
            particles.flatMap { p ->
                when (p) {
                    is XParticle.GroupRef -> {
                        val group =
                            docsByNamespace[p.ref.namespace]?.groups?.firstOrNull {
                                it.name == p.ref.local
                            }
                        if (group == null) {
                            diagnostics +=
                                unresolved(
                                    sourcePath,
                                    p.line,
                                    "group '${p.ref.local}' cannot be resolved",
                                )
                            emptyList()
                        } else if (p.ref in cycles.groups) {
                            diagnostics +=
                                unresolved(
                                    sourcePath,
                                    p.line,
                                    "group '${p.ref.local}' cannot be resolved; the reference " +
                                        "chain is cyclic",
                                )
                            emptyList()
                        } else {
                            when (val c = group.content) {
                                is XContent.Sequence ->
                                    if (p.minOccurs == 1 && p.maxOccurs == 1) {
                                        expandParticles(c.particles, where)
                                    } else {
                                        listOf(
                                            XParticle.Nested(
                                                c,
                                                p.minOccurs,
                                                p.maxOccurs,
                                                p.line,
                                                name = p.ref.local,
                                            )
                                        )
                                    }
                                else ->
                                    listOf(XParticle.Nested(c, p.minOccurs, p.maxOccurs, p.line))
                            }
                        }
                    }
                    else -> listOf(p)
                }
            }

        /**
         * Attribute groups referenced by `xs:attributeGroup ref` expand into their own attribute
         * uses in place, recursively; an unresolved group is reported and dropped.
         */
        private fun expandAttributeUses(uses: List<XAttributeUse>): List<XAttributeUse> =
            uses.flatMap { use ->
                when (use) {
                    is XAttributeUse.GroupRef -> {
                        val group =
                            docsByNamespace[use.ref.namespace]?.attributeGroups?.firstOrNull {
                                it.name == use.ref.local
                            }
                        if (group == null) {
                            diagnostics +=
                                unresolved(
                                    sourcePath,
                                    use.line,
                                    "attribute group '${use.ref.local}' cannot be resolved",
                                )
                            emptyList()
                        } else if (use.ref in cycles.attributeGroups) {
                            diagnostics +=
                                unresolved(
                                    sourcePath,
                                    use.line,
                                    "attribute group '${use.ref.local}' cannot be resolved; the " +
                                        "reference chain is cyclic",
                                )
                            emptyList()
                        } else expandAttributeUses(group.attributes)
                    }
                    else -> listOf(use)
                }
            }

        /**
         * The global element [el] refers to, merged with [el]'s own occurrence, nillability, and
         * documentation (when it carries its own); `null` (reported) when the reference can't be
         * resolved.
         */
        private fun resolveElementRef(el: XElement, whereCollision: String): XElement? {
            val qname = el.ref ?: return el
            val target =
                docsByNamespace[qname.namespace]?.elements?.firstOrNull { it.name == qname.local }
            if (target == null) {
                diagnostics +=
                    lossy(
                        ImportCodes.UNRESOLVED,
                        whereCollision,
                        "element '${qname.local}' cannot be resolved",
                        el.line,
                    )
                return null
            }
            return target.copy(
                minOccurs = el.minOccurs,
                maxOccurs = el.maxOccurs,
                nillable = el.nillable || target.nillable,
                doc = el.doc ?: target.doc,
            )
        }

        /** The top-level attribute [a] refers to, merged with [a]'s own use, default, and docs. */
        private fun resolveAttributeRef(a: XAttribute, whereCollision: String): XAttribute? {
            val qname = a.ref ?: return a
            val target =
                docsByNamespace[qname.namespace]?.attributes?.firstOrNull { it.name == qname.local }
            if (target == null) {
                diagnostics +=
                    lossy(
                        ImportCodes.UNRESOLVED,
                        whereCollision,
                        "attribute '${qname.local}' cannot be resolved",
                        a.line,
                    )
                return null
            }
            return target.copy(
                use = a.use,
                default = a.default ?: target.default,
                fixed = a.fixed ?: target.fixed,
                doc = a.doc ?: target.doc,
            )
        }

        private data class Resolved(
            val type: UnitType,
            val nullable: Boolean,
            val default: String?,
        )

        /**
         * Reports [el]'s identity constraints, which Schemata has no way to say: every `xs:key` and
         * `xs:keyref`, and every `xs:unique` but the one a map wrapper would consume (whether or
         * not [el] actually turns out to be a map).
         */
        private fun reportIdentityConstraints(el: XElement, where: String) {
            val mapUnique = el.uniques.firstOrNull(::isMapUnique)
            val constraints = (el.uniques - listOfNotNull(mapUnique)).map { it.name to it.line }
            (constraints + el.keys.map { it.name to it.line }).forEach { (name, line) ->
                diagnostics +=
                    lossy(ImportCodes.DROPPED, where, "identity constraint '$name' dropped", line)
            }
        }

        /**
         * [el] with its substitution-group head's type when it declares none of its own, as XSD
         * gives it, following the chain of heads until one has a type.
         */
        private fun withHeadType(el: XElement): XElement {
            var head = el.substitutionGroup
            val seen = mutableSetOf<QName>()
            while (
                el.type == null &&
                    el.inlineComplex == null &&
                    el.inlineSimple == null &&
                    head != null &&
                    seen.add(head)
            ) {
                val h = headElement(head) ?: return el
                if (h.type != null || h.inlineComplex != null || h.inlineSimple != null) {
                    return el.copy(
                        type = h.type,
                        inlineComplex = h.inlineComplex,
                        inlineSimple = h.inlineSimple,
                    )
                }
                head = h.substitutionGroup
            }
            return el
        }

        private fun field(
            el0: XElement,
            claimed: MutableMap<String, String>,
            whereCollision: String,
            nested: MutableList<UnitDecl>,
            siblings: MutableList<UnitDecl>,
        ): UnitField? {
            val el = withHeadType(resolveElementRef(el0, whereCollision) ?: return null)
            val original = el.name ?: return null
            val where = "element '$original'"
            if (el.maxOccurs == 0) {
                diagnostics += lossy(ImportCodes.DROPPED, where, "maxOccurs 0 dropped", el.line)
                return null
            }
            // A global element's constraints are reported once, where it is declared.
            if (el0.ref == null) reportIdentityConstraints(el, where)
            // An abstract head of a substitution group is not dropped: it is a union of its
            // members.
            val elementQName = el0.ref ?: QName(doc.targetNamespace, original)
            if (el.abstract && heads.elements[elementQName]?.members.isNullOrEmpty()) {
                diagnostics += lossy(ImportCodes.DROPPED, where, "abstract dropped", el.line)
            }
            val headType = el0.ref?.let(::elementHeadType)
            el.form?.let { checkForm(it, elementForm(formDoc()), where, el.line) }
            // An element typed xs:anyType, or not typed at all, holds any content: a string the
            // xsd target writes back as xs:anyType.
            val anyType =
                headType == null &&
                    (el.type == QName(ImportTypes.XS, "anyType") ||
                        (el.type == null && el.inlineComplex == null && el.inlineSimple == null))

            val resolved: Resolved? =
                when {
                    headType != null ->
                        if (el.maxOccurs != 1) {
                            Resolved(
                                UnitType.ListOf(
                                    headType,
                                    el.nillable,
                                    listRefinements(el.minOccurs, el.maxOccurs),
                                ),
                                false,
                                null,
                            )
                        } else Resolved(headType, el.minOccurs == 0 || el.nillable, null)
                    el.maxOccurs == 1 && el.type == null && el.inlineComplex != null -> {
                        (el.fixed ?: el.default)?.let { noLiteral(it, where, el.line) }
                        if (
                            (el.inlineComplex.content as? XContent.Choice)?.let {
                                isUnionType(el.inlineComplex)
                            } == true
                        ) {
                            val ref =
                                inlineDeclaration(el.inlineComplex, original, nested, siblings)
                            Resolved(ref, el.minOccurs == 0, null)
                        } else {
                            when (val m = mapWrapper(el, where, nested)) {
                                is MapResult.AsMap -> Resolved(m.type, el.minOccurs == 0, null)
                                is MapResult.Fallback -> {
                                    val wrapperName = ImportNames.upperCamel(original)
                                    val entry =
                                        UnitRecord(
                                            "Entry",
                                            m.entryFields,
                                            emptyList(),
                                            null,
                                            emptyList(),
                                        )
                                    val listField =
                                        UnitField(
                                            "entry",
                                            UnitType.ListOf(
                                                UnitType.Ref("Entry"),
                                                m.nullableElement,
                                                m.listRefinements,
                                            ),
                                            false,
                                            null,
                                            m.entryDoc,
                                            emptyList(),
                                        )
                                    nested +=
                                        UnitRecord(
                                            wrapperName,
                                            listOf(listField),
                                            listOf(entry),
                                            null,
                                            emptyList(),
                                        )
                                    Resolved(UnitType.Ref(wrapperName), el.minOccurs == 0, null)
                                }
                                MapResult.NotAMap -> {
                                    val ref =
                                        inlineDeclaration(
                                            el.inlineComplex,
                                            original,
                                            nested,
                                            siblings,
                                        )
                                    Resolved(ref, el.minOccurs == 0, null)
                                }
                            }
                        }
                    }
                    el.maxOccurs != 1 -> {
                        // resolveParticleType sees el's own maxOccurs != 1 and wraps the per-
                        // occurrence type in the ListOf itself, recursing through an `item` wrapper
                        // for a nested list or map (`list<list<T>>`, `list<map<K, V>>`, …).
                        // A repeated element of an anonymous complex type that is neither the
                        // `item` nor the map wrapper the xsd target writes is a list of the record
                        // (or union) nested under the element's name, as a single one would be.
                        val type =
                            if (anyType)
                                UnitType.ListOf(
                                    UnitType.Scalar("string", emptyList()),
                                    el.nillable,
                                    listRefinements(el.minOccurs, el.maxOccurs),
                                )
                            else
                                resolveParticleType(el, where, nested)
                                    ?: el.inlineComplex
                                        ?.takeIf { el.type == null }
                                        ?.let { ic ->
                                            UnitType.ListOf(
                                                inlineDeclaration(ic, original, nested, siblings),
                                                el.nillable,
                                                listRefinements(el.minOccurs, el.maxOccurs),
                                            )
                                        }
                        if (type == null) {
                            diagnostics +=
                                lossy(
                                    ImportCodes.UNRESOLVED,
                                    where,
                                    "type '${el.type?.local}' cannot be resolved",
                                    el.line,
                                )
                            null
                        } else {
                            if (el.default != null) {
                                diagnostics +=
                                    lossy(
                                        ImportCodes.APPROXIMATED,
                                        where,
                                        "default on repeated element '$original' dropped",
                                        el.line,
                                    )
                            }
                            if (el.fixed != null) {
                                diagnostics +=
                                    lossy(
                                        ImportCodes.DROPPED,
                                        where,
                                        "fixed value dropped",
                                        el.line,
                                    )
                            }
                            if (
                                !anyType &&
                                    (type as? UnitType.ListOf)?.element is UnitType.ListOf &&
                                    isListValued(el)
                            ) {
                                diagnostics +=
                                    lossy(
                                        ImportCodes.APPROXIMATED,
                                        where,
                                        "repeated element of a list simple type imported as a " +
                                            "list of lists",
                                        el.line,
                                    )
                            }
                            Resolved(type, false, null)
                        }
                    }
                    else -> {
                        val type =
                            if (anyType) UnitType.Scalar("string", emptyList())
                            else resolveElementScalarOrRef(el, original, where, nested)
                        if (type == null) {
                            diagnostics +=
                                lossy(
                                    ImportCodes.UNRESOLVED,
                                    where,
                                    "type '${el.type?.local}' cannot be resolved",
                                    el.line,
                                )
                            null
                        } else {
                            val rawDefault = el.fixed ?: el.default
                            if (el.fixed != null) {
                                diagnostics +=
                                    lossy(
                                        ImportCodes.DROPPED,
                                        where,
                                        "fixed value imported as a default",
                                        el.line,
                                    )
                            }
                            val default =
                                rawDefault?.let {
                                    defaultLiteralFor(
                                        type,
                                        it,
                                        el.type,
                                        el.inlineSimple,
                                        where,
                                        el.line,
                                    )
                                }
                            val nullable = (el.minOccurs == 0 || el.nillable) && default == null
                            Resolved(type, nullable, default)
                        }
                    }
                }
            if (resolved == null) return null
            val claim =
                nameAndClaim(original, "element", where, claimed, whereCollision, el.line)
                    ?: return null
            val (name, nameAnnotations) = claim
            val annotations =
                when {
                    anyType -> nameAnnotations + UnitAnnotation("xsd", "any_type", null)
                    el.maxOccurs == 1 && isListValued(el) ->
                        nameAnnotations + listOfNotNull(listAnnotation(resolved.type))
                    else -> nameAnnotations
                }
            return UnitField(
                name,
                resolved.type,
                resolved.nullable,
                resolved.default,
                el.doc,
                annotations,
            )
        }

        /**
         * Whether [el]'s own type is a simple type that may be a list: an inline simple type, or a
         * named one other than a builtin.
         */
        private fun isListValued(el: XElement): Boolean {
            if (el.inlineSimple != null) return true
            val type = el.type ?: return false
            if (type.namespace == ImportTypes.XS) return false
            return docsByNamespace[type.namespace]?.simpleTypes?.any { it.name == type.local } ==
                true
        }

        /**
         * The document whose form defaults govern what is being lowered: the one [sourcePath]
         * names, or [doc] for a component an include brought in.
         */
        private fun formDoc(): XsdDoc =
            docsByNamespace.values.firstOrNull { it.path == sourcePath } ?: doc

        /**
         * A local element's or attribute's own `form`, which Schemata cannot say per field: noted
         * when it differs from [default], the document's.
         */
        private fun checkForm(form: String, default: String, where: String, line: Int) {
            if (form != default) {
                diagnostics +=
                    lossy(
                        ImportCodes.APPROXIMATED,
                        where,
                        "form '$form' differs from the schema default; dropped",
                        line,
                    )
            }
        }

        private fun attribute(
            a0: XAttribute,
            claimed: MutableMap<String, String>,
            whereCollision: String,
            nested: MutableList<UnitDecl>,
        ): UnitField? {
            // A prohibited attribute is one a restriction removes from its base.
            if (a0.use == "prohibited") return null
            val a = resolveAttributeRef(a0, whereCollision) ?: return null
            val original = a.name ?: return null
            val where = "attribute '$original'"
            a.form?.let { checkForm(it, attributeForm(formDoc()), where, a.line) }
            val type = resolveAttributeType(a, where, nested)
            if (type == null) {
                diagnostics +=
                    lossy(
                        ImportCodes.UNRESOLVED,
                        where,
                        "type '${a.type?.local}' cannot be resolved",
                        a.line,
                    )
                return null
            }
            val rawDefault = a.fixed ?: a.default
            if (a.fixed != null) {
                diagnostics +=
                    lossy(ImportCodes.DROPPED, where, "fixed value imported as a default", a.line)
            }
            val default =
                rawDefault?.let {
                    defaultLiteralFor(type, it, a.type, a.inlineSimple, where, a.line)
                }
            val nullable = a.use != "required" && default == null
            val claim =
                nameAndClaim(original, "attribute", where, claimed, whereCollision, a.line)
                    ?: return null
            val (name, nameAnnotations) = claim
            val annotations =
                nameAnnotations +
                    listOfNotNull(listAnnotation(type)) +
                    UnitAnnotation("xsd", "attribute", null)
            return UnitField(name, type, nullable, default, a.doc, annotations)
        }

        /**
         * The field's final name, claimed against [claimed]: [ImportCodes.APPROXIMATED] when
         * [original] isn't even a valid XML name (no override could ever regenerate it, so none is
         * offered — a valid override, like a type's, always regenerates the original xsd text
         * exactly, so needing one is never reported), and [ImportCodes.UNRESOLVED] (dropping the
         * field, returning `null`) when it collides with one already claimed in this record.
         */
        private fun nameAndClaim(
            original: String,
            kind: String,
            whereConstruct: String,
            claimed: MutableMap<String, String>,
            whereCollision: String,
            line: Int,
        ): Pair<String, List<UnitAnnotation>>? {
            val field = fieldNameFor(original)
            if (field.unfixable) {
                diagnostics +=
                    lossy(
                        ImportCodes.APPROXIMATED,
                        whereConstruct,
                        "$kind '$original' has no Schemata equivalent; imported as '${field.name}'",
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
                diagnostics += collision(whereCollision, existing, whereConstruct, field.name, line)
                return null
            }
            claimed[field.name] = whereConstruct
            return field.name to listOfNotNull(field.annotation)
        }

        /**
         * An element and an attribute lowering to one field [name]: XML keeps the two apart, so the
         * element keeps [name] and the attribute takes `<name>_attribute`, with no override (the
         * xsd target names a record's elements and attributes in one scope, where the original name
         * would collide again), and that is noted. When the attribute claimed [name] first, its
         * field is renamed once the record is complete (see [renameAttributesBesideElements]).
         * `null` when the pair is not an element and an attribute, or the renamed field is taken.
         */
        private fun attributeBeside(
            name: String,
            override: UnitAnnotation?,
            kind: String,
            whereConstruct: String,
            existing: String,
            claimed: MutableMap<String, String>,
            whereCollision: String,
            line: Int,
        ): Pair<String, List<UnitAnnotation>>? {
            val attributeFirst = kind == "element" && existing.startsWith("attribute '")
            val attributeSecond = kind == "attribute" && existing.startsWith("element '")
            val renamed = "${name}_attribute"
            if (!(attributeFirst || attributeSecond) || renamed in claimed) return null
            val (attribute, element) =
                if (attributeFirst) existing to whereConstruct else whereConstruct to existing
            diagnostics +=
                lossy(
                    ImportCodes.APPROXIMATED,
                    whereCollision,
                    "$attribute and $element both lower to field '$name'; the attribute is " +
                        "imported as '$renamed' and the regenerated attribute will be named so",
                    line,
                )
            claimed[renamed] = attribute
            if (attributeSecond) return renamed to emptyList()
            claimed[name] = element
            return name to listOfNotNull(override)
        }

        /**
         * [fields] with every attribute field whose name an element took from it (see
         * [attributeBeside]) renamed `<name>_attribute`, without its name override.
         */
        private fun renameAttributesBesideElements(
            fields: List<UnitField>,
            claimed: Map<String, String>,
        ): List<UnitField> =
            fields.map { f ->
                val isAttribute = f.annotations.any { it.target == "xsd" && it.key == "attribute" }
                val owner = claimed[f.name]
                if (isAttribute && owner != null && !owner.startsWith("attribute '")) {
                    f.copy(
                        name = "${f.name}_attribute",
                        annotations = f.annotations.filterNot { it.key == "name" },
                    )
                } else f
            }

        /**
         * [raw] as the default literal for a field of [type]: a scalar's own literal; for an enum,
         * named by [sourceType] or declared inline as [inlineSimple], the imported value's name,
         * looked up by the XSD enumeration text it was declared with. `null`, reported, when no
         * Schemata literal can stand for it (a scalar with no literal form, a complex type, a list,
         * or a value the enum does not have).
         */
        private fun defaultLiteralFor(
            type: UnitType,
            raw: String,
            sourceType: QName?,
            inlineSimple: XSimpleType?,
            where: String,
            line: Int,
        ): String? {
            val literal =
                when (type) {
                    is UnitType.Scalar -> ImportTypes.defaultLiteral(type.builtin, raw)
                    is UnitType.Ref ->
                        when {
                            inlineSimple != null && isEnum(inlineSimple) ->
                                enumValueName(inlineSimple, raw)
                            sourceType != null -> {
                                val named = namedEnum(sourceType)
                                if (named != null) return enumDefault(named, raw, where, line)
                                null
                            }
                            else -> null
                        }
                    is UnitType.ListOf,
                    is UnitType.MapOf -> null
                }
            return literal ?: noLiteral(raw, where, line)
        }

        private fun noLiteral(raw: String, where: String, line: Int): String? {
            diagnostics +=
                lossy(
                    ImportCodes.APPROXIMATED,
                    where,
                    "default '$raw' has no Schemata literal; dropped",
                    line,
                )
            return null
        }

        /** The imported name of [st]'s enumeration value [raw], or `null` when it has none. */
        private fun enumValueName(st: XSimpleType, raw: String): String? =
            enumValueNames(st).firstOrNull { it.first.value == raw }?.second?.name

        /** The enumerated simple type [qname] names, with its declaring document. */
        private fun namedEnum(qname: QName): Pair<XsdDoc, XSimpleType>? {
            if (qname.namespace == ImportTypes.XS) return null
            val targetDoc = docsByNamespace[qname.namespace] ?: return null
            val st = targetDoc.simpleTypes.firstOrNull { it.name == qname.local } ?: return null
            return if (isEnum(st)) targetDoc to st else null
        }

        /**
         * The imported name of the named enum's value [raw]: `null` with [ImportCodes.APPROXIMATED]
         * at [where] when [raw] matches none of its values.
         */
        private fun enumDefault(
            named: Pair<XsdDoc, XSimpleType>,
            raw: String,
            where: String,
            line: Int,
        ): String? {
            val (targetDoc, st) = named
            enumValueName(st, raw)?.let {
                return it
            }
            val enumName =
                st.name?.let { typeNames[QName(targetDoc.targetNamespace, it)]?.finalName }
                    ?: st.name
            diagnostics +=
                lossy(
                    ImportCodes.APPROXIMATED,
                    where,
                    "default '$raw' is not a value of enum '$enumName'; dropped",
                    line,
                )
            return null
        }

        private fun implicitAnyType(where: String, line: Int): UnitType.Scalar {
            diagnostics +=
                lossy(
                    ImportCodes.WIDENED,
                    where,
                    "no declared type; treated as xs:anyType, imported as string",
                    line,
                )
            return UnitType.Scalar("string", emptyList())
        }

        private fun resolveElementScalarOrRef(
            el: XElement,
            originalName: String,
            where: String,
            nested: MutableList<UnitDecl>,
        ): UnitType? {
            if (el.inlineSimple != null)
                return resolveInlineSimpleType(el.inlineSimple, originalName, where, nested)
            if (el.type != null) return resolveTypeRef(el.type, where, el.line)
            return implicitAnyType(where, el.line)
        }

        private fun resolveElementItemType(
            el: XElement,
            where: String,
            nested: MutableList<UnitDecl>,
        ): UnitType? {
            if (el.inlineSimple != null) {
                return resolveInlineSimpleType(el.inlineSimple, el.name ?: "item", where, nested)
            }
            if (el.type != null) return resolveTypeRef(el.type, where, el.line)
            return implicitAnyType(where, el.line)
        }

        /**
         * An element's own type, treating any `maxOccurs != 1` on it as one level of `list<…>` (the
         * most common case: a plain field, where `el` is the field's own element). Recurses through
         * an anonymous complex type matching the `item`-wrapper shape the XSD target writes for a
         * collection nested inside another (`list<list<T>>`, `list<map<K, V>>`); falls through to
         * the `entry`/`@key` map-wrapper shape otherwise (`map<K, list<V>>`, `map<K, map<K2,
         * V2>>`).
         */
        private fun resolveParticleType(
            el: XElement,
            where: String,
            nested: MutableList<UnitDecl>,
        ): UnitType? {
            if (el.maxOccurs != 1) {
                val itemType =
                    resolveParticleType(el.copy(minOccurs = 1, maxOccurs = 1), where, nested)
                        ?: return null
                return UnitType.ListOf(
                    itemType,
                    el.nillable,
                    listRefinements(el.minOccurs, el.maxOccurs),
                )
            }
            if (el.type != null || el.inlineSimple != null)
                return resolveElementItemType(el, where, nested)
            val ic = el.inlineComplex ?: return implicitAnyType(where, el.line)
            singleItemElement(ic)?.let {
                return resolveParticleType(it, "element 'item'", nested)
            }
            val shape = recognizeMapShape(el, nested) ?: return null
            val hasUnique = el.uniques.any(::isMapUnique)
            // No missing-xs:unique fallback at this depth (only the top-level field gets the
            // Counts/Entry nested-record treatment); an unverifiable map nested this deep is simply
            // unresolved.
            if (!hasUnique) return null
            val keyType =
                resolveAttributeType(shape.keyAttribute, "attribute 'key'", nested)?.takeIf {
                    it !is UnitType.ListOf
                } ?: return null
            val refinements = listRefinements(shape.entry.minOccurs, shape.entry.maxOccurs)
            return UnitType.MapOf(keyType, shape.valueType, shape.entry.nillable, refinements)
        }

        /**
         * The lone `item` particle of an otherwise-empty `item`-wrapper complex type: a sequence of
         * exactly one element named `item`, no attributes. `null` when [ct] doesn't match (so the
         * caller can try the `entry`/`@key` map-wrapper shape instead).
         */
        private fun singleItemElement(ct: XComplexType): XElement? {
            if (ct.attributes.isNotEmpty()) return null
            val seq = ct.content as? XContent.Sequence ?: return null
            if (seq.particles.size != 1) return null
            val particle = seq.particles[0] as? XParticle.Element ?: return null
            return particle.element.takeIf { it.name == "item" && it.ref == null }
        }

        private fun resolveAttributeType(
            a: XAttribute,
            where: String,
            nested: MutableList<UnitDecl>,
        ): UnitType? {
            if (a.inlineSimple != null)
                return resolveInlineSimpleType(a.inlineSimple, a.name ?: "attribute", where, nested)
            if (a.type != null) return resolveTypeRef(a.type, where, a.line)
            return implicitAnyType(where, a.line)
        }

        private fun resolveTypeRef(qname: QName, where: String, line: Int): UnitType? {
            if (qname.namespace == ImportTypes.XS) {
                val mapped = ImportTypes.builtin(qname.local) ?: return null
                mapped.notes.forEach { diagnostics += lossy(ImportCodes.WIDENED, where, it, line) }
                val type = mapped.type
                // A bare `xs:decimal` reference carries no facets at all, so, exactly like a
                // restriction that omits totalDigits and fractionDigits, it needs the same
                // precision-and-scale default Schemata requires.
                if (
                    type is UnitType.Scalar &&
                        type.builtin == "decimal" &&
                        type.refinements.isEmpty()
                ) {
                    val (refined, notes) = ImportTypes.facets(type, emptyList(), line)
                    notes.forEach { diagnostics += noteDiagnostic(sourcePath, where, it) }
                    return refined
                }
                return type
            }
            val targetDoc = docsByNamespace[qname.namespace] ?: return null
            headType(qname)?.let {
                return it
            }
            val ct = targetDoc.complexTypes.firstOrNull { it.name == qname.local }
            if (ct != null) return UnitType.Ref(qualifiedTypeName(targetDoc, qname.local))
            val st = targetDoc.simpleTypes.firstOrNull { it.name == qname.local } ?: return null
            if (isEnum(st)) return UnitType.Ref(qualifiedTypeName(targetDoc, qname.local))
            return resolveNamedSimpleType(st, targetDoc.path, where, setOf(qname))
        }

        private fun qualifiedTypeName(targetDoc: XsdDoc, original: String): String {
            val info = typeNames.getValue(QName(targetDoc.targetNamespace, original))
            return if (targetDoc === doc) info.finalName
            else "${namespaceNames.getValue(targetDoc)}.${info.finalName}"
        }

        /**
         * An inline (anonymous) simple type: an enumeration, or a union of them, becomes a nested
         * enum; anything else is resolved in place.
         */
        private fun resolveInlineSimpleType(
            st: XSimpleType,
            elementOrAttributeName: String,
            where: String,
            nested: MutableList<UnitDecl>,
        ): UnitType {
            if (isEnum(st)) {
                val name = ImportNames.upperCamel(elementOrAttributeName)
                if (st.variety is XVariety.Union) unionNote(where, "enum '$name'", st.line)
                nested += buildInlineEnum(st, name, where)
                return UnitType.Ref(name)
            }
            return resolveNamedSimpleType(st, doc.path, where)
        }

        /**
         * [st] as a type: a restriction as its refined base, a list as `list<T>` of its item type,
         * a union as described at [unionType]. [visiting] holds the named simple types already on
         * the way here, so a chain that reaches one of them again is reported rather than followed.
         */
        private fun resolveNamedSimpleType(
            st: XSimpleType,
            path: String,
            where: String,
            visiting: Set<QName> = emptySet(),
        ): UnitType {
            return when (val variety = st.variety) {
                is XVariety.Restriction -> {
                    val base =
                        when {
                            variety.base != null ->
                                resolveSimpleTypeByQName(variety.base, where, st.line, visiting)
                                    ?: UnitType.Scalar("string", emptyList())
                            variety.inlineBase != null ->
                                resolveNamedSimpleType(variety.inlineBase, path, where, visiting)
                            else -> UnitType.Scalar("string", emptyList())
                        }
                    restrict(base, variety.facets, st.line, path, where)
                }
                is XVariety.ListOf ->
                    UnitType.ListOf(
                        listItem(variety, path, where, st.line, visiting),
                        false,
                        emptyList(),
                    )
                is XVariety.Union -> unionType(variety, path, where, st.line, visiting)
            }
        }

        /**
         * A list's item type: a builtin, an enum, or any other simple type resolved in place. An
         * item that is itself a list has no Schemata equivalent and is imported as a string.
         */
        private fun listItem(
            list: XVariety.ListOf,
            path: String,
            where: String,
            line: Int,
            visiting: Set<QName>,
        ): UnitType {
            val string = UnitType.Scalar("string", emptyList())
            val q = list.itemType
            val item =
                when {
                    q != null -> {
                        val named = namedEnum(q)
                        if (named != null) UnitType.Ref(qualifiedTypeName(named.first, q.local))
                        else resolveSimpleTypeByQName(q, where, line, visiting) ?: string
                    }
                    list.inlineItem != null ->
                        resolveNamedSimpleType(list.inlineItem, path, where, visiting)
                    else -> string
                }
            return when {
                item is UnitType.ListOf -> {
                    diagnostics +=
                        lossy(
                            ImportCodes.APPROXIMATED,
                            where,
                            "list item that is itself a list imported as string",
                            line,
                        )
                    string
                }
                // A bare xs:decimal carries no digits, which Schemata requires.
                item is UnitType.Scalar &&
                    item.builtin == "decimal" &&
                    item.refinements.isEmpty() -> restrict(item, emptyList(), line, path, where)
                else -> item
            }
        }

        /**
         * A union simple type that is not enumerated (an enumerated one is an enum declaration):
         * the builtin its members share when they are all one scalar builtin, without their
         * refinements; a string otherwise. Noted either way.
         */
        private fun unionType(
            union: XVariety.Union,
            path: String,
            where: String,
            line: Int,
            visiting: Set<QName>,
        ): UnitType {
            val string = UnitType.Scalar("string", emptyList())
            val members =
                union.memberTypes.map { q ->
                    if (namedEnum(q) != null) string
                    else resolveSimpleTypeByQName(q, where, line, visiting) ?: string
                } +
                    union.inlineMembers.map {
                        if (isEnum(it)) string
                        else resolveNamedSimpleType(it, path, where, visiting)
                    }
            val builtins = members.map { (it as? UnitType.Scalar)?.builtin }.distinct()
            val builtin = builtins.singleOrNull() ?: "string"
            unionNote(where, builtin, line)
            val type = UnitType.Scalar(builtin, emptyList())
            return if (builtin == "decimal") restrict(type, emptyList(), line, path, where)
            else type
        }

        /** Notes that a union simple type at [where] was imported as [what]. */
        private fun unionNote(where: String, what: String, line: Int) {
            diagnostics +=
                lossy(ImportCodes.APPROXIMATED, where, "union simple type imported as $what", line)
        }

        /**
         * [base] narrowed by [facets]: a scalar's as [ImportTypes.facets] reads them; a list's
         * length facets bound the list, and any other facet on a list or an enum is dropped. Notes
         * point into [path], the declaring document.
         */
        private fun restrict(
            base: UnitType,
            facets: List<XFacet>,
            line: Int,
            path: String,
            where: String,
        ): UnitType {
            if (base is UnitType.Scalar) {
                val (refined, notes) = ImportTypes.facets(base, facets, line)
                notes.forEach { diagnostics += noteDiagnostic(path, where, it) }
                return refined
            }
            val bounds = (base as? UnitType.ListOf)?.refinements.orEmpty().toMap(LinkedHashMap())
            facets.forEach { f ->
                val keys =
                    when (f.name) {
                        "length" -> listOf("min", "max")
                        "minLength" -> listOf("min")
                        "maxLength" -> listOf("max")
                        else -> emptyList()
                    }
                val count = f.value.trim().takeIf { it.matches(Regex("[0-9]+")) }
                val note =
                    when {
                        base !is UnitType.ListOf || keys.isEmpty() -> "facet ${f.name} dropped"
                        count == null -> "facet ${f.name} value '${f.value}' dropped"
                        else -> null
                    }
                if (note != null) {
                    diagnostics +=
                        noteDiagnostic(path, where, Note(ImportCodes.WIDENED, note, f.line))
                } else {
                    keys.forEach { bounds[it] = count!!.toBigInteger().toString() }
                }
            }
            if (base !is UnitType.ListOf) return base
            val refinements = listOf("min", "max").mapNotNull { k -> bounds[k]?.let { k to it } }
            return base.copy(refinements = refinements)
        }

        private fun resolveSimpleTypeByQName(
            qname: QName,
            where: String,
            line: Int,
            visiting: Set<QName> = emptySet(),
        ): UnitType? {
            if (qname.namespace == ImportTypes.XS) {
                val mapped = ImportTypes.builtin(qname.local) ?: return null
                mapped.notes.forEach { diagnostics += lossy(ImportCodes.WIDENED, where, it, line) }
                return mapped.type
            }
            if (qname in visiting) {
                diagnostics +=
                    lossy(
                        ImportCodes.UNRESOLVED,
                        where,
                        "simple type '${qname.local}' cannot be resolved; the reference chain is " +
                            "cyclic",
                        line,
                    )
                return UnitType.Scalar("string", emptyList())
            }
            val targetDoc = docsByNamespace[qname.namespace] ?: return null
            val st = targetDoc.simpleTypes.firstOrNull { it.name == qname.local } ?: return null
            // An enumeration, or a union of them, is an enum declaration of its own: a simple
            // content value or a restriction based on one names the enum.
            if (isEnum(st)) {
                return UnitType.Ref(qualifiedTypeName(targetDoc, qname.local))
            }
            return resolveNamedSimpleType(st, targetDoc.path, where, visiting + qname)
        }

        /**
         * Recognises the `entry`/`@key` map wrapper shapes: a simpleContent or complexContent
         * extension for a plain value, a `value` child for a value with its own refinements, or an
         * `item` child for a value that is itself a nested list or map. `null` when the shape
         * doesn't match at all, in which case the caller falls back to an ordinary nested record.
         */
        private fun recognizeMapShape(el: XElement, nested: MutableList<UnitDecl>): EntryShape? {
            val wrapper = el.inlineComplex ?: return null
            if (wrapper.attributes.isNotEmpty()) return null
            val seq = wrapper.content as? XContent.Sequence ?: return null
            if (seq.particles.size != 1) return null
            val entryParticle = seq.particles[0] as? XParticle.Element ?: return null
            val entry = entryParticle.element
            if (entry.name != "entry" || entry.ref != null || entry.type != null) return null
            val ec = entry.inlineComplex ?: return null
            return when (val content = ec.content) {
                is XContent.Extension -> {
                    if (content.particles.isNotEmpty()) return null
                    val key = singleKeyAttribute(ec) ?: return null
                    val valueType =
                        resolveTypeRef(content.base, "element 'entry'", content.line) ?: return null
                    EntryShape(key, valueType, entry)
                }
                is XContent.Sequence -> {
                    if (content.particles.size != 1) return null
                    val valueParticle = content.particles[0] as? XParticle.Element ?: return null
                    val valueEl = valueParticle.element
                    val key = singleKeyAttribute(ec) ?: return null
                    val valueType =
                        when (valueEl.name) {
                            "value" ->
                                resolveElementScalarOrRef(
                                    valueEl,
                                    "value",
                                    "element 'value'",
                                    nested,
                                )
                            "item" -> resolveParticleType(valueEl, "element 'item'", nested)
                            else -> null
                        } ?: return null
                    EntryShape(key, valueType, entry)
                }
                else -> null
            }
        }

        private fun singleKeyAttribute(ec: XComplexType): XAttribute? {
            val use = ec.attributes.singleOrNull() as? XAttributeUse.Attribute ?: return null
            return use.attribute.takeIf { it.name == "key" }
        }

        private fun mapWrapper(
            el: XElement,
            where: String,
            nested: MutableList<UnitDecl>,
        ): MapResult {
            val shape = recognizeMapShape(el, nested) ?: return MapResult.NotAMap
            val keyType =
                resolveAttributeType(shape.keyAttribute, "attribute 'key'", nested)?.takeIf {
                    it !is UnitType.ListOf
                } ?: return MapResult.NotAMap
            val hasUnique = el.uniques.any(::isMapUnique)
            val refinements = listRefinements(shape.entry.minOccurs, shape.entry.maxOccurs)
            if (!hasUnique) {
                diagnostics +=
                    lossy(
                        ImportCodes.APPROXIMATED,
                        where,
                        "map wrapper without xs:unique imported as a nested record",
                        el.line,
                    )
                val keyField =
                    UnitField(
                        "key",
                        keyType,
                        shape.keyAttribute.use != "required",
                        null,
                        shape.keyAttribute.doc,
                        listOf(UnitAnnotation("xsd", "attribute", null)),
                    )
                val valueField = UnitField("value", shape.valueType, false, null, null, emptyList())
                return MapResult.Fallback(
                    listOf(valueField, keyField),
                    shape.entry.doc,
                    refinements,
                    shape.entry.nillable,
                )
            }
            return MapResult.AsMap(
                UnitType.MapOf(keyType, shape.valueType, shape.entry.nillable, refinements)
            )
        }

        /**
         * `"$where: $tail"` with the standard help text for [code] (shared across every
         * diagnostic).
         */
        fun lossy(code: DiagnosticCode, where: String, tail: String, line: Int): Diagnostic {
            val l = line.coerceAtLeast(1)
            return Diagnostic(
                code,
                "$where: $tail",
                Span(sourcePath, l, 1, l, 1),
                ImportCodes.helpFor(code),
            )
        }

        /**
         * A facet [Note] at [path] (the simple type's own document, which may differ from [doc]).
         */
        private fun noteDiagnostic(path: String, where: String, note: Note): Diagnostic {
            val line = note.line.coerceAtLeast(1)
            return Diagnostic(
                note.code,
                "$where: ${note.tail}",
                Span(path, line, 1, line, 1),
                ImportCodes.helpFor(note.code),
            )
        }

        /**
         * Two constructs lowering to the same field name: [ImportCodes.UNRESOLVED], one dropped.
         */
        private fun collision(
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
        private fun typeCollision(
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
}
