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

        // Type names are resolved once for the whole document set, per namespace, before any field
        // is lowered, so a cross-document reference always sees the final name. Only enumerated
        // simple types (the only simple types that ever become declarations) claim a name here; a
        // plain restriction, inlined at each use, never competes for one.
        val typeNames = mutableMapOf<QName, TypeNameInfo>()
        live.forEach { doc ->
            val originals =
                doc.complexTypes.mapNotNull { it.name } +
                    doc.simpleTypes.mapNotNull { st -> st.name?.takeIf { hasEnumeration(st) } }
            resolveNamespaceTypeNames(originals).forEach { (original, info) ->
                typeNames[QName(doc.targetNamespace, original)] = info
            }
        }

        val docsByNamespace = LinkedHashMap<String?, XsdDoc>()
        live.forEach { docsByNamespace.putIfAbsent(it.targetNamespace, it) }
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
                    val original = st.name?.takeIf { hasEnumeration(st) } ?: return@forEach
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
                            .filter { it.name != null && hasEnumeration(it) }
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
                                ?.let { UnitAnnotation("xsd", "namespace", "\"$it\"") }
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
     * Only an enumerated simple type becomes a declaration (an enum); a plain restriction is
     * inlined at each use, so it never claims a type name of its own.
     */
    private fun hasEnumeration(st: XSimpleType): Boolean =
        (st.variety as? XVariety.Restriction)?.facets?.any { it.name == "enumeration" } == true

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
            if (content is XContent.Choice) {
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
            val (fields, nested) =
                fieldsAndNested(ct, "complex type '$original'", info.finalName, siblings)
            val annotations = listOfNotNull(typeOverride, rootAnnotation)
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
            val built = buildInlineEnum(st, info.finalName, "simple type '$original'")
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
            val name = ImportNames.upperCamel(original)
            if (!claimTopLevel(name, "element '$original'", el.line)) return emptyList()
            val siblings = mutableListOf<UnitDecl>()
            val content = ct.content
            if (content is XContent.Choice) {
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
            val (fields, nested) = fieldsAndNested(ct, "element '$original'", name, siblings)
            return listOf(UnitRecord(name, fields, nested, ct.doc ?: el.doc, emptyList())) +
                siblings
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
         * more member types, unless its type's own union already serves. A member declared with an
         * inline type cannot be named in a union, so it is reported and left out.
         */
        fun headUnions(): List<UnitDecl> {
            val result = mutableListOf<UnitDecl>()
            heads.elements
                .filter { it.key.namespace == doc.targetNamespace && it.value.members.size >= 2 }
                .forEach { (name, head) ->
                    val el = headElement(name) ?: return@forEach
                    val where = "element '${name.local}'"
                    val unionName = elementUnionName(name)
                    val members = heads.memberElements[name].orEmpty()
                    at(el.path) {
                        diagnostics +=
                            lossy(
                                ImportCodes.APPROXIMATED,
                                where,
                                "substitution group '${name.local}' imported as union " +
                                    "'$unionName' of ${head.members.size} member types; the " +
                                    "regenerated XSD uses a choice",
                                el.line,
                            )
                    }
                    members
                        .filter {
                            it.type == null && (it.inlineComplex != null || it.inlineSimple != null)
                        }
                        .forEach { m ->
                            at(m.path) {
                                diagnostics +=
                                    lossy(
                                        ImportCodes.DROPPED,
                                        "element '${m.name}'",
                                        "substitution member with an inline type dropped from " +
                                            "union '$unionName'",
                                        m.line,
                                    )
                            }
                        }
                    if (sharesTypeUnion(name)) return@forEach
                    val claimed = at(el.path) { claimTopLevel(unionName, where, el.line) }
                    if (claimed) result += headUnion(unionName, head, where, members, el.doc)
                }
            return result
        }

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
         * What a choice-only complex type carries that a union cannot: its attributes, mixed
         * content, and abstractness, each reported as dropped.
         */
        private fun unionExtras(ct: XComplexType, where: String) {
            expandAttributeUses(ct.attributes).forEach { use ->
                when (use) {
                    is XAttributeUse.Attribute -> {
                        val a = use.attribute
                        val name = a.name ?: a.ref?.local
                        diagnostics +=
                            lossy(ImportCodes.DROPPED, where, "attribute '$name' dropped", a.line)
                    }
                    is XAttributeUse.AnyAttribute ->
                        diagnostics +=
                            lossy(ImportCodes.DROPPED, where, "xs:anyAttribute dropped", use.line)
                    is XAttributeUse.GroupRef -> Unit // only an unresolved ref survives expansion
                }
            }
            if (ct.mixed) {
                diagnostics += lossy(ImportCodes.DROPPED, where, "mixed content dropped", ct.line)
            }
            if (ct.abstract) {
                diagnostics += lossy(ImportCodes.DROPPED, where, "abstract dropped", ct.line)
            }
        }

        private fun fieldsAndNested(
            ct: XComplexType,
            whereCollision: String,
            recordName: String,
            siblings: MutableList<UnitDecl>,
        ): Pair<List<UnitField>, List<UnitDecl>> {
            val nested = mutableListOf<UnitDecl>()
            val claimed = mutableMapOf<String, String>()
            // Seeds the cycle guard with this type's own identity (when it has one), so a direct
            // self-extension is caught on the first hop, not just a longer cycle back to it.
            val visited = mutableSetOf<QName>()
            if (ct.name != null) visited += QName(doc.targetNamespace, ct.name)
            val fields =
                allFieldsOf(ct, whereCollision, recordName, claimed, nested, siblings, visited)
            return fields to nested
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
        ): List<UnitField> {
            if (ct.mixed) {
                diagnostics +=
                    lossy(
                        ImportCodes.DROPPED,
                        whereCollision,
                        "mixed content dropped; elements kept",
                        ct.line,
                    )
            }
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
                )
            val attributeFields =
                expandAttributeUses(ct.attributes).mapNotNull { use ->
                    when (use) {
                        is XAttributeUse.Attribute ->
                            attribute(use.attribute, claimed, whereCollision, nested)
                        is XAttributeUse.GroupRef -> null
                        is XAttributeUse.AnyAttribute -> {
                            diagnostics +=
                                lossy(
                                    ImportCodes.DROPPED,
                                    whereCollision,
                                    "xs:anyAttribute dropped",
                                    use.line,
                                )
                            null
                        }
                    }
                }
            val inheritedFields =
                (ct.content as? XContent.Restriction)
                    ?.takeIf { !it.simple }
                    ?.let { inheritedAttributes(it.base, ct.attributes) }
                    .orEmpty()
                    .mapNotNull { (path, a) ->
                        at(path) { attribute(a, claimed, whereCollision, nested) }
                    }
            return elementFields + attributeFields + inheritedFields
        }

        /**
         * The attributes a complex restriction of [base] keeps without declaring them: every one
         * [base] has, its own and those it derives, that [own] neither redeclares nor prohibits (a
         * prohibition is itself a redeclaration), each with the file that declares it.
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
                        is XContent.Extension -> content.base.takeIf { !content.simple }
                        is XContent.Restriction -> content.base.takeIf { !content.simple }
                        else -> null
                    }
            }
            return result
        }

        private fun contentFields(
            ct: XComplexType,
            content: XContent,
            whereCollision: String,
            recordName: String,
            claimed: MutableMap<String, String>,
            nested: MutableList<UnitDecl>,
            siblings: MutableList<UnitDecl>,
            visited: MutableSet<QName>,
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
                    diagnostics +=
                        lossy(
                            ImportCodes.APPROXIMATED,
                            whereCollision,
                            "xs:all imported as a sequence",
                            ct.line,
                        )
                    sequenceFields(
                        recordName,
                        content.particles,
                        whereCollision,
                        claimed,
                        nested,
                        siblings,
                    )
                }
                is XContent.Empty -> emptyList()
                is XContent.Choice -> {
                    // An extension base that is itself choice-shaped (a union): not supported, so
                    // its own content is simply dropped rather than flattened.
                    diagnostics +=
                        lossy(
                            ImportCodes.DROPPED,
                            whereCollision,
                            "extension of a choice-shaped type dropped",
                            ct.line,
                        )
                    emptyList()
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
            var choiceCount = 0
            val result = mutableListOf<UnitField>()
            expandParticles(particles, whereCollision).forEach { particle ->
                when (particle) {
                    is XParticle.Element ->
                        field(particle.element, claimed, whereCollision, nested, siblings)?.let {
                            result += it
                        }
                    is XParticle.Any ->
                        diagnostics +=
                            lossy(
                                ImportCodes.DROPPED,
                                whereCollision,
                                "xs:any dropped",
                                particle.line,
                            )
                    is XParticle.GroupRef -> Unit // only an unresolved ref survives expandParticles
                    is XParticle.Nested -> {
                        val content = particle.content
                        when (content) {
                            is XContent.Choice -> {
                                choiceCount++
                                result +=
                                    inlineChoiceFields(
                                        recordName,
                                        content,
                                        particle,
                                        choiceCount,
                                        whereCollision,
                                        claimed,
                                        nested,
                                        siblings,
                                    )
                            }
                            is XContent.Sequence -> {
                                diagnostics +=
                                    lossy(
                                        ImportCodes.APPROXIMATED,
                                        whereCollision,
                                        "nested sequence flattened into the record",
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
         * A bare `xs:choice` found directly inside a sequence, with no wrapping element: a union
         * synthesised as a top-level sibling when every member has a complex type, named
         * `[recordName]Choice` (suffixed by [index] past the first); otherwise every member is
         * flattened to an optional field of the enclosing record.
         */
        private fun inlineChoiceFields(
            recordName: String,
            choice: XContent.Choice,
            particle: XParticle.Nested,
            index: Int,
            whereCollision: String,
            claimed: MutableMap<String, String>,
            nested: MutableList<UnitDecl>,
            siblings: MutableList<UnitDecl>,
        ): List<UnitField> {
            val members =
                expandParticles(choice.particles, whereCollision)
                    .filterIsInstance<XParticle.Element>()
            val allComplex =
                members.isNotEmpty() &&
                    members.all { p ->
                        val el = p.element
                        el.inlineComplex != null || (el.type != null && isComplexTypeRef(el.type))
                    }
            val fieldName = if (index == 1) "choice" else "choice_$index"
            if (allComplex) {
                val unionName =
                    if (index == 1) "${recordName}Choice" else "${recordName}Choice$index"
                val union =
                    unionFromChoice(choice, unionName, "union '$unionName'", null, siblings, false)
                siblings += union
                diagnostics +=
                    lossy(
                        ImportCodes.APPROXIMATED,
                        whereCollision,
                        "inline choice has no Schemata equivalent; imported as union '$unionName' " +
                            "in field '$fieldName'",
                        particle.line,
                    )
                val claim =
                    nameAndClaim(
                        fieldName,
                        "choice",
                        whereCollision,
                        claimed,
                        whereCollision,
                        particle.line,
                    ) ?: return emptyList()
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
            return members.mapNotNull {
                field(it.element.copy(minOccurs = 0), claimed, whereCollision, nested, siblings)
            }
        }

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
                return simpleContentFields("extension", ext.base, ext.line, whereCollision, claimed)
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
         * `value` field of the base's simple type, beside the type's own attributes. A complex
         * base's own value type is not followed: the field is a string.
         */
        private fun simpleContentFields(
            kind: String,
            base: QName,
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
            val valueType =
                if (isComplexTypeRef(base)) {
                    diagnostics +=
                        lossy(
                            ImportCodes.APPROXIMATED,
                            whereCollision,
                            "simpleContent $kind of complex type '${base.local}' imported as string",
                            line,
                        )
                    UnitType.Scalar("string", emptyList())
                } else {
                    resolveSimpleTypeByQName(base, whereCollision, line)
                        ?: UnitType.Scalar("string", emptyList())
                }
            val claim =
                nameAndClaim("value", "field", whereCollision, claimed, whereCollision, line)
                    ?: return emptyList()
            val (name, annotations) = claim
            return listOf(UnitField(name, valueType, false, null, null, annotations))
        }

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
            val (fields, nested) = fieldsAndNested(ct, "complex type '$name'", name, siblings)
            return UnitRecord(name, fields, nested, ct.doc, emptyList())
        }

        /**
         * An anonymous complex type hoisted out of a union member, as a top-level, non-root record.
         */
        private fun buildHoistedRecord(
            ct: XComplexType,
            name: String,
            siblings: MutableList<UnitDecl>,
        ): UnitRecord {
            val (fields, nested) = fieldsAndNested(ct, "complex type '$name'", name, siblings)
            return UnitRecord(
                name,
                fields,
                nested,
                ct.doc,
                listOf(UnitAnnotation("xsd", "root", "false")),
            )
        }

        /**
         * A union from [choice]'s members, in order. [checkMismatch] reports a member element whose
         * name doesn't match what the XSD target would regenerate (SCH2403) and two members
         * colliding on the same regenerated name (SCH2401); turned off for a synthesised,
         * never-named inline choice, where there is nothing for a member name to round-trip
         * against.
         */
        private fun unionFromChoice(
            choice: XContent.Choice,
            name: String,
            unionWhere: String,
            unionDoc: String?,
            siblings: MutableList<UnitDecl>,
            checkMismatch: Boolean,
        ): UnitUnion {
            val members = mutableListOf<UnionMember>()
            val seenStems = mutableMapOf<String, String>()
            expandParticles(choice.particles, unionWhere).forEach { particle ->
                when (particle) {
                    is XParticle.Element -> {
                        val el =
                            if (particle.element.ref != null) {
                                resolveElementRef(particle.element, unionWhere) ?: return@forEach
                            } else particle.element
                        val elementName = el.name ?: "member"
                        val pair = memberTypeAndStem(el, unionWhere, siblings)
                        if (pair == null) return@forEach
                        val (ownType, stem) = pair
                        val type = particle.element.ref?.let(::elementHeadType) ?: ownType
                        val existing = seenStems[stem]
                        if (existing != null) {
                            if (checkMismatch) {
                                diagnostics +=
                                    lossy(
                                        ImportCodes.UNRESOLVED,
                                        unionWhere,
                                        "members '$existing' and '$elementName' both lower to " +
                                            "member '${ImportNames.upperCamel(stem)}'",
                                        el.line,
                                    )
                            }
                            return@forEach
                        }
                        seenStems[stem] = elementName
                        if (checkMismatch && elementName != stem) {
                            diagnostics +=
                                lossy(
                                    ImportCodes.APPROXIMATED,
                                    unionWhere,
                                    "member element '$elementName' has no Schemata equivalent; the " +
                                        "regenerated element will be named '$stem'",
                                    el.line,
                                )
                        }
                        members += UnionMember(type, el.doc)
                    }
                    is XParticle.Any ->
                        diagnostics +=
                            lossy(ImportCodes.DROPPED, unionWhere, "xs:any dropped", particle.line)
                    else -> Unit
                }
            }
            return UnitUnion(name, members, unionDoc, emptyList())
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
                    val mapped = ImportTypes.builtin(qname.local)
                    if (mapped == null) {
                        diagnostics +=
                            lossy(
                                ImportCodes.UNRESOLVED,
                                unionWhere,
                                "type '${qname.local}' cannot be resolved",
                                el.line,
                            )
                        return null
                    }
                    mapped.notes.forEach {
                        diagnostics += lossy(ImportCodes.WIDENED, unionWhere, it, el.line)
                    }
                    return mapped.type to mapped.type.builtin
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
                if (st != null && hasEnumeration(st)) {
                    val info = typeNames.getValue(QName(targetDoc.targetNamespace, qname.local))
                    return UnitType.Ref(qualifiedTypeName(targetDoc, qname.local)) to
                        regeneratedElementName(qname.local, info)
                }
                if (st != null) {
                    val scalar = resolveNamedSimpleType(st, targetDoc.path, unionWhere)
                    return scalar to scalar.builtin
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
                if (hasEnumeration(el.inlineSimple)) {
                    val elementName = el.name ?: "member"
                    val hoistedName = ImportNames.upperCamel(elementName)
                    if (!claimTopLevel(hoistedName, "element '$elementName'", el.line)) return null
                    siblings += buildInlineEnum(el.inlineSimple, hoistedName, unionWhere)
                    return UnitType.Ref(hoistedName) to ImportNames.lowerSnake(elementName)
                }
                val scalar = resolveNamedSimpleType(el.inlineSimple, doc.path, unionWhere)
                return scalar to scalar.builtin
            }
            diagnostics +=
                lossy(
                    ImportCodes.UNRESOLVED,
                    unionWhere,
                    "union member has no declared type",
                    el.line,
                )
            return null
        }

        /**
         * [original]'s regenerated element or union-member name, exactly as the XSD target writes
         * it.
         */
        private fun regeneratedElementName(original: String, info: TypeNameInfo): String =
            if (info.annotation != null) original.removeSuffix("Type")
            else Names.snakeCase(info.finalName)

        /** An anonymous or named enumeration's values, in order, with per-value naming and docs. */
        private fun buildInlineEnum(st: XSimpleType, name: String, where: String): UnitEnum {
            val facets =
                (st.variety as XVariety.Restriction).facets.filter { it.name == "enumeration" }
            val claimed = mutableMapOf<String, String>()
            val values =
                facets.mapNotNull { f ->
                    val valueWhere = "enum value '$name.${f.value}'"
                    // A value's @xsd(name) override, when the xsd target would accept it, always
                    // regenerates the original xsd text exactly, so this is never reported; a value
                    // that is not even a valid XML name gets no override (one would only be
                    // rejected), and that is reported instead.
                    val field = fieldNameFor(f.value)
                    if (field.unfixable) {
                        diagnostics +=
                            lossy(
                                ImportCodes.APPROXIMATED,
                                valueWhere,
                                "$valueWhere has no Schemata equivalent; imported as '${field.name}'",
                                f.line,
                            )
                    }
                    val existing = claimed[field.name]
                    if (existing != null) {
                        diagnostics +=
                            lossy(
                                ImportCodes.UNRESOLVED,
                                where,
                                "enum value '$existing' and '${f.value}' both lower to value '${field.name}'",
                                f.line,
                            )
                        null
                    } else {
                        claimed[field.name] = f.value
                        UnitEnumValue(field.name, f.doc, listOfNotNull(field.annotation))
                    }
                }
            return UnitEnum(name, values, st.doc, emptyList())
        }

        /**
         * Groups referenced by `xs:group ref` expand into their own particles in place,
         * recursively; an unresolved group is reported and dropped. A repeated reference
         * (`minOccurs`/`maxOccurs` other than `1`) to a group whose own content is a sequence loses
         * that repetition when spliced in directly (there is no particle left to carry it), so it
         * is reported and expanded once; a repeated reference to a choice or `xs:all` group keeps
         * its own repetition, carried on the `Nested` particle it becomes.
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
                                is XContent.Sequence -> {
                                    if (p.minOccurs != 1 || p.maxOccurs != 1) {
                                        diagnostics +=
                                            lossy(
                                                ImportCodes.APPROXIMATED,
                                                where,
                                                "repeated group '${p.ref.local}' has no Schemata " +
                                                    "equivalent; expanded once",
                                                p.line,
                                            )
                                    }
                                    expandParticles(c.particles, where)
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

        private fun field(
            el0: XElement,
            claimed: MutableMap<String, String>,
            whereCollision: String,
            nested: MutableList<UnitDecl>,
            siblings: MutableList<UnitDecl>,
        ): UnitField? {
            val el = resolveElementRef(el0, whereCollision) ?: return null
            val original = el.name ?: return null
            val where = "element '$original'"
            if (el.maxOccurs == 0) {
                diagnostics += lossy(ImportCodes.DROPPED, where, "maxOccurs 0 dropped", el.line)
                return null
            }

            // The one xs:unique a map wrapper would consume (whether or not this field actually
            // turns out to be a map) is excluded; every other identity constraint has no Schemata
            // equivalent at all.
            val mapUnique = el.uniques.firstOrNull(::isMapUnique)
            (el.uniques - listOfNotNull(mapUnique)).forEach {
                diagnostics +=
                    lossy(
                        ImportCodes.DROPPED,
                        where,
                        "identity constraint '${it.name}' dropped",
                        it.line,
                    )
            }
            el.keys.forEach {
                diagnostics +=
                    lossy(
                        ImportCodes.DROPPED,
                        where,
                        "identity constraint '${it.name}' dropped",
                        it.line,
                    )
            }
            // An abstract head of a substitution group is not dropped: it is a union of its
            // members.
            val elementQName = el0.ref ?: QName(doc.targetNamespace, original)
            if (el.abstract && heads.elements[elementQName]?.members.isNullOrEmpty()) {
                diagnostics += lossy(ImportCodes.DROPPED, where, "abstract dropped", el.line)
            }
            val headType = el0.ref?.let(::elementHeadType)

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
                        val inlineContent = el.inlineComplex.content
                        if (inlineContent is XContent.Choice) {
                            val name = ImportNames.upperCamel(original)
                            unionExtras(el.inlineComplex, "union '$name'")
                            val union =
                                unionFromChoice(
                                    inlineContent,
                                    name,
                                    "union '$name'",
                                    el.inlineComplex.doc,
                                    siblings,
                                    checkMismatch = true,
                                )
                            nested += union
                            Resolved(UnitType.Ref(name), el.minOccurs == 0, null)
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
                                    val name = ImportNames.upperCamel(original)
                                    nested += buildNestedRecord(el.inlineComplex, name, siblings)
                                    Resolved(UnitType.Ref(name), el.minOccurs == 0, null)
                                }
                            }
                        }
                    }
                    el.maxOccurs != 1 -> {
                        // resolveParticleType sees el's own maxOccurs != 1 and wraps the per-
                        // occurrence type in the ListOf itself, recursing through an `item` wrapper
                        // for a nested list or map (`list<list<T>>`, `list<map<K, V>>`, …).
                        val type = resolveParticleType(el, where, nested)
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
                            Resolved(type, false, null)
                        }
                    }
                    else -> {
                        val type = resolveElementScalarOrRef(el, original, where, nested)
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
            val (name, annotations) = claim
            return UnitField(
                name,
                resolved.type,
                resolved.nullable,
                resolved.default,
                el.doc,
                annotations,
            )
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
            val annotations = nameAnnotations + UnitAnnotation("xsd", "attribute", null)
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
                diagnostics += collision(whereCollision, existing, whereConstruct, field.name, line)
                return null
            }
            claimed[field.name] = whereConstruct
            return field.name to listOfNotNull(field.annotation)
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
                            inlineSimple != null && hasEnumeration(inlineSimple) ->
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
            (st.variety as XVariety.Restriction)
                .facets
                .firstOrNull { it.name == "enumeration" && it.value == raw }
                ?.let { fieldNameFor(it.value).name }

        /** The enumerated simple type [qname] names, with its declaring document. */
        private fun namedEnum(qname: QName): Pair<XsdDoc, XSimpleType>? {
            if (qname.namespace == ImportTypes.XS) return null
            val targetDoc = docsByNamespace[qname.namespace] ?: return null
            val st = targetDoc.simpleTypes.firstOrNull { it.name == qname.local } ?: return null
            return if (hasEnumeration(st)) targetDoc to st else null
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
                resolveAttributeType(shape.keyAttribute, "attribute 'key'", nested) ?: return null
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
            if (ct != null) {
                val name = qualifiedTypeName(targetDoc, qname.local)
                val choice = ct.content as? XContent.Choice
                if (choice != null && choice.maxOccurs != 1) {
                    diagnostics +=
                        lossy(
                            ImportCodes.APPROXIMATED,
                            where,
                            "type '${ct.name}' is a repeated choice; imported as list<$name>",
                            line,
                        )
                    return UnitType.ListOf(
                        UnitType.Ref(name),
                        false,
                        listRefinements(choice.minOccurs, choice.maxOccurs),
                    )
                }
                return UnitType.Ref(name)
            }
            val st = targetDoc.simpleTypes.firstOrNull { it.name == qname.local } ?: return null
            if (hasEnumeration(st)) return UnitType.Ref(qualifiedTypeName(targetDoc, qname.local))
            return resolveNamedSimpleType(st, targetDoc.path, where, setOf(qname))
        }

        private fun qualifiedTypeName(targetDoc: XsdDoc, original: String): String {
            val info = typeNames.getValue(QName(targetDoc.targetNamespace, original))
            return if (targetDoc === doc) info.finalName
            else "${namespaceNames.getValue(targetDoc)}.${info.finalName}"
        }

        /**
         * An inline (anonymous) simple type: an enumeration becomes a nested enum; else a scalar.
         */
        private fun resolveInlineSimpleType(
            st: XSimpleType,
            elementOrAttributeName: String,
            where: String,
            nested: MutableList<UnitDecl>,
        ): UnitType {
            if (hasEnumeration(st)) {
                val name = ImportNames.upperCamel(elementOrAttributeName)
                nested += buildInlineEnum(st, name, where)
                return UnitType.Ref(name)
            }
            return resolveNamedSimpleType(st, doc.path, where)
        }

        /**
         * [st] as a scalar. [visiting] holds the named simple types already on the way here, so a
         * restriction chain that reaches one of them again is reported rather than followed.
         */
        private fun resolveNamedSimpleType(
            st: XSimpleType,
            path: String,
            where: String,
            visiting: Set<QName> = emptySet(),
        ): UnitType.Scalar {
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
                    val (refined, notes) = ImportTypes.facets(base, variety.facets, st.line)
                    notes.forEach { diagnostics += noteDiagnostic(path, where, it) }
                    refined
                }
                is XVariety.ListOf -> {
                    diagnostics +=
                        lossy(
                            ImportCodes.DROPPED,
                            where,
                            "list simple type imported as string",
                            st.line,
                        )
                    UnitType.Scalar("string", emptyList())
                }
                is XVariety.Union -> {
                    diagnostics +=
                        lossy(
                            ImportCodes.DROPPED,
                            where,
                            "union simple type imported as string",
                            st.line,
                        )
                    UnitType.Scalar("string", emptyList())
                }
            }
        }

        private fun resolveSimpleTypeByQName(
            qname: QName,
            where: String,
            line: Int,
            visiting: Set<QName> = emptySet(),
        ): UnitType.Scalar? {
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
                resolveAttributeType(shape.keyAttribute, "attribute 'key'", nested)
                    ?: return MapResult.NotAMap
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
