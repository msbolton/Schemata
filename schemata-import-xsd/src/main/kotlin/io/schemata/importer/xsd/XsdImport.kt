package io.schemata.importer.xsd

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
                    doc.simpleTypes
                        .filter { it.name != null && hasEnumeration(it) }
                        .map { it.name!! }
            resolveNamespaceTypeNames(originals).forEach { (original, info) ->
                typeNames[QName(doc.targetNamespace, original)] = info
            }
        }

        val units =
            live.map { doc ->
                val imports = mutableListOf<String>()
                doc.imports.forEach { imp ->
                    val target = live.firstOrNull { it.targetNamespace == imp.namespace }
                    if (target == null) {
                        diagnostics +=
                            unresolved(
                                doc.path,
                                imp.line,
                                "import '${imp.namespace}' cannot be resolved",
                            )
                    } else if (target !== doc) {
                        imports += names.getValue(target)
                    }
                }
                doc.dropped.forEach { (construct, line) ->
                    diagnostics += dropped(doc.path, line, "schema", "$construct dropped")
                }
                val lowering = NamespaceLowering(doc, live, names, typeNames, diagnostics)
                val declarations = mutableListOf<UnitDecl>()
                doc.complexTypes.forEach { declarations += lowering.declaration(it) }
                doc.simpleTypes
                    .filter { it.name != null && hasEnumeration(it) }
                    .forEach { declarations += lowering.enumDeclaration(it) }
                doc.elements.forEach { el ->
                    if (el.ref == null && el.type == null && el.inlineComplex != null) {
                        declarations += lowering.topLevelRecord(el)
                    }
                }
                SchemataUnit(
                    namespace = names.getValue(doc),
                    xsdNamespace = doc.targetNamespace,
                    doc = doc.doc,
                    imports = imports.distinct(),
                    declarations = declarations,
                    sourcePath = doc.path,
                )
            }
        return Imported(units, diagnostics)
    }

    private fun deriveNamespaceName(doc: XsdDoc, diagnostics: MutableList<Diagnostic>): String {
        val tn = doc.targetNamespace
        if (tn != null && tn.startsWith("urn:schemata:")) return tn.removePrefix("urn:schemata:")
        val rawStem = rawStem(doc.path)
        if (ImportNames.isNamespaceSegment(rawStem)) return rawStem
        val fixed = ImportNames.namespaceStem(doc.path)
        diagnostics +=
            renamed(
                doc.path,
                1,
                "namespace '$rawStem' is not a Schemata identifier; imported as '$fixed'",
            )
        return fixed
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
                val (name, _) = ImportNames.typeOverride(it)
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

    /** `full-name` → `full_name` with `@xsd(name)`; a valid identifier is kept as-is. */
    private fun fieldNameFor(original: String): Pair<String, UnitAnnotation?> {
        if (ImportNames.isLowerSnake(original)) return original to null
        val fixed = ImportNames.lowerSnake(original)
        return fixed to UnitAnnotation("xsd", "name", "\"$original\"")
    }

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

    private fun diagnostic(
        code: DiagnosticCode,
        path: String,
        line: Int,
        message: String,
        help: String,
    ) =
        Diagnostic(
            code,
            message,
            Span(path, line.coerceAtLeast(1), 1, line.coerceAtLeast(1), 1),
            help,
        )

    private fun unresolved(path: String, line: Int, message: String) =
        diagnostic(
            ImportCodes.UNRESOLVED,
            path,
            line,
            "$path: $message",
            "add the referenced schema to the inputs or fix schemaLocation",
        )

    private fun renamed(path: String, line: Int, message: String) =
        diagnostic(
            ImportCodes.RENAMED,
            path,
            line,
            "$path: $message",
            "keep the annotation so the regenerated XSD uses the original name",
        )

    private fun dropped(path: String, line: Int, where: String, tail: String) =
        diagnostic(
            ImportCodes.DROPPED,
            path,
            line,
            "$where: $tail",
            "add the missing part by hand; Schemata cannot express it",
        )

    /** The one standard help text per `SCH24xx` code, shared across every diagnostic. */
    private fun helpFor(code: DiagnosticCode): String =
        when (code) {
            ImportCodes.UNRESOLVED ->
                "add the referenced schema to the inputs or fix schemaLocation"
            ImportCodes.RENAMED ->
                "keep the annotation so the regenerated XSD uses the original name"
            ImportCodes.APPROXIMATED ->
                "review the imported record; the regenerated XSD will differ here"
            ImportCodes.WIDENED -> "narrow the type by hand if the data needs it"
            else -> "add the missing part by hand; Schemata cannot express it"
        }

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
        val allDocs: List<XsdDoc>,
        val namespaceNames: Map<XsdDoc, String>,
        val typeNames: Map<QName, TypeNameInfo>,
        val diagnostics: MutableList<Diagnostic>,
    ) {
        /** A named complex type: a record, or, when its content is a bare choice, a union. */
        fun declaration(ct: XComplexType): List<UnitDecl> {
            val original = ct.name ?: error("a top-level complex type always has a name")
            val info = typeNames.getValue(QName(doc.targetNamespace, original))
            if (info.annotation != null) {
                diagnostics +=
                    lossy(
                        ImportCodes.RENAMED,
                        "complex type '$original'",
                        "complex type '$original' is not a Schemata identifier; imported as " +
                            "'${info.finalName}' with @xsd(name)",
                        ct.line,
                    )
            }
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
            val siblings = mutableListOf<UnitDecl>()
            val content = ct.content
            if (content is XContent.Choice) {
                val union =
                    unionFromChoice(
                        content,
                        info.finalName,
                        "union '${info.finalName}'",
                        ct.doc,
                        siblings,
                        checkMismatch = true,
                    )
                return listOf(union) + siblings
            }
            val rootElement =
                doc.elements.firstOrNull {
                    it.ref == null && it.type == QName(doc.targetNamespace, original)
                }
            val rootAnnotation =
                if (rootElement == null) UnitAnnotation("xsd", "root", "false")
                else {
                    // The override already serves double duty on the XSD target (it also names the
                    // global element), so a mismatched element name can only be reported, not fixed
                    // by a second, conflicting use of the same annotation.
                    val override =
                        if (info.annotation != null) original.removeSuffix("Type") else null
                    val regenerated = override ?: Names.snakeCase(info.finalName)
                    if (rootElement.name != regenerated) {
                        diagnostics +=
                            lossy(
                                ImportCodes.APPROXIMATED,
                                "element '${rootElement.name}'",
                                "element '${rootElement.name}' has no Schemata equivalent; the " +
                                    "regenerated root element will be named '$regenerated'",
                                rootElement.line,
                            )
                    }
                    null
                }
            val (fields, nested) =
                fieldsAndNested(ct, "complex type '$original'", info.finalName, siblings)
            val annotations = listOfNotNull(info.annotation, rootAnnotation)
            return listOf(UnitRecord(info.finalName, fields, nested, ct.doc, annotations)) +
                siblings
        }

        /** A named, enumerated simple type: an enum. */
        fun enumDeclaration(st: XSimpleType): UnitEnum {
            val original = st.name ?: error("an enumerated top-level simple type always has a name")
            val info = typeNames.getValue(QName(doc.targetNamespace, original))
            if (info.annotation != null) {
                diagnostics +=
                    lossy(
                        ImportCodes.RENAMED,
                        "simple type '$original'",
                        "simple type '$original' is not a Schemata identifier; imported as " +
                            "'${info.finalName}' with @xsd(name)",
                        st.line,
                    )
            }
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

        /** A global element with its own inline complex type: a top-level record, always a root. */
        fun topLevelRecord(el: XElement): List<UnitDecl> {
            val original =
                el.name ?: error("a global element with an inline type always has a name")
            val name = ImportNames.upperCamel(original)
            val ct = el.inlineComplex ?: error("topLevelRecord requires an inline complex type")
            val siblings = mutableListOf<UnitDecl>()
            val content = ct.content
            if (content is XContent.Choice) {
                val union =
                    unionFromChoice(
                        content,
                        name,
                        "union '$name'",
                        ct.doc ?: el.doc,
                        siblings,
                        checkMismatch = true,
                    )
                return listOf(union) + siblings
            }
            val (fields, nested) = fieldsAndNested(ct, "element '$original'", name, siblings)
            return listOf(UnitRecord(name, fields, nested, ct.doc ?: el.doc, emptyList())) +
                siblings
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
            if (ct.abstract) {
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
            return elementFields + attributeFields
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
                    restrictionFields(
                        content,
                        recordName,
                        whereCollision,
                        claimed,
                        nested,
                        siblings,
                    )
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
            val targetDoc =
                allDocs.firstOrNull { it.targetNamespace == qname.namespace } ?: return false
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
                val valueType =
                    resolveSimpleTypeByQName(ext.base, whereCollision, ext.line)
                        ?: UnitType.Scalar("string", emptyList())
                diagnostics +=
                    lossy(
                        ImportCodes.APPROXIMATED,
                        whereCollision,
                        "simpleContent extension of '${ext.base.local}' has no Schemata equivalent; " +
                            "imported as a record with a 'value' field",
                        ext.line,
                    )
                val claim =
                    nameAndClaim(
                        "value",
                        "field",
                        whereCollision,
                        claimed,
                        whereCollision,
                        ext.line,
                    ) ?: return emptyList()
                val (name, annotations) = claim
                return listOf(UnitField(name, valueType, false, null, null, annotations))
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
            diagnostics +=
                lossy(
                    ImportCodes.APPROXIMATED,
                    whereCollision,
                    "extension of '${ext.base.local}' has no Schemata equivalent; base fields " +
                        "flattened into the record",
                    ext.line,
                )
            val ownFields =
                sequenceFields(recordName, ext.particles, whereCollision, claimed, nested, siblings)
            return baseFields + ownFields
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
            val targetDoc = allDocs.firstOrNull { it.targetNamespace == baseQName.namespace }
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
            return allFieldsOf(
                baseCt,
                whereCollision,
                baseCt.name ?: baseQName.local,
                claimed,
                nested,
                siblings,
                visited,
            )
        }

        private fun restrictionFields(
            res: XContent.Restriction,
            recordName: String,
            whereCollision: String,
            claimed: MutableMap<String, String>,
            nested: MutableList<UnitDecl>,
            siblings: MutableList<UnitDecl>,
        ): List<UnitField> {
            diagnostics +=
                lossy(
                    ImportCodes.APPROXIMATED,
                    whereCollision,
                    "restriction of '${res.base.local}' has no Schemata equivalent; its own content " +
                        "is used",
                    res.line,
                )
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
            val members = mutableListOf<UnitType>()
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
                        val (type, stem) = pair
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
                        members += type
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
                val targetDoc = allDocs.firstOrNull { it.targetNamespace == qname.namespace }
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
                    val info = typeNames.getValue(QName(targetDoc.targetNamespace, ct.name!!))
                    return UnitType.Ref(qualifiedTypeName(targetDoc, ct.name)) to
                        regeneratedElementName(ct.name, info)
                }
                val st = targetDoc.simpleTypes.firstOrNull { it.name == qname.local }
                if (st != null && hasEnumeration(st)) {
                    val info = typeNames.getValue(QName(targetDoc.targetNamespace, st.name!!))
                    return UnitType.Ref(qualifiedTypeName(targetDoc, st.name)) to
                        regeneratedElementName(st.name, info)
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
                siblings += buildHoistedRecord(el.inlineComplex, hoistedName, siblings)
                return UnitType.Ref(hoistedName) to ImportNames.lowerSnake(elementName)
            }
            if (el.inlineSimple != null) {
                if (hasEnumeration(el.inlineSimple)) {
                    val elementName = el.name ?: "member"
                    val hoistedName = ImportNames.upperCamel(elementName)
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
                    val (vname, nameAnnotation) = fieldNameFor(f.value)
                    if (nameAnnotation != null) {
                        diagnostics +=
                            lossy(
                                ImportCodes.RENAMED,
                                valueWhere,
                                "$valueWhere is not a Schemata identifier; imported as '$vname' " +
                                    "with @xsd(name)",
                                f.line,
                            )
                    }
                    val existing = claimed[vname]
                    if (existing != null) {
                        diagnostics +=
                            lossy(
                                ImportCodes.UNRESOLVED,
                                where,
                                "enum value '$existing' and '${f.value}' both lower to value '$vname'",
                                f.line,
                            )
                        null
                    } else {
                        claimed[vname] = f.value
                        UnitEnumValue(vname, f.doc, listOfNotNull(nameAnnotation))
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
                            allDocs
                                .firstOrNull { it.targetNamespace == p.ref.namespace }
                                ?.groups
                                ?.firstOrNull { it.name == p.ref.local }
                        if (group == null) {
                            diagnostics +=
                                unresolved(
                                    doc.path,
                                    p.line,
                                    "group '${p.ref.local}' cannot be resolved",
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
                            allDocs
                                .firstOrNull { it.targetNamespace == use.ref.namespace }
                                ?.attributeGroups
                                ?.firstOrNull { it.name == use.ref.local }
                        if (group == null) {
                            diagnostics +=
                                unresolved(
                                    doc.path,
                                    use.line,
                                    "attribute group '${use.ref.local}' cannot be resolved",
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
            val targetDoc = allDocs.firstOrNull { it.targetNamespace == qname.namespace }
            val target = targetDoc?.elements?.firstOrNull { it.name == qname.local }
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
            val targetDoc = allDocs.firstOrNull { it.targetNamespace == qname.namespace }
            val target = targetDoc?.attributes?.firstOrNull { it.name == qname.local }
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

            // The one xs:unique a map wrapper would consume (whether or not this field actually
            // turns out to be a map) is excluded; every other identity constraint has no Schemata
            // equivalent at all.
            val mapUnique =
                el.uniques.firstOrNull {
                    it.fields == listOf("@key") && it.selector.substringAfterLast(':') == "entry"
                }
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
            el.substitutionGroup?.let {
                diagnostics +=
                    lossy(
                        ImportCodes.DROPPED,
                        where,
                        "substitution group '${it.local}' dropped; imported as an independent element",
                        el.line,
                    )
            }
            if (el.abstract) {
                diagnostics += lossy(ImportCodes.DROPPED, where, "abstract dropped", el.line)
            }

            val resolved: Resolved? =
                when {
                    el.maxOccurs == 1 && el.type == null && el.inlineComplex != null -> {
                        val inlineContent = el.inlineComplex.content
                        if (inlineContent is XContent.Choice) {
                            val name = ImportNames.upperCamel(original)
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
                                    defaultLiteralFor(type, it, el.type, where, el.line)
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
            val default = rawDefault?.let { defaultLiteralFor(type, it, a.type, where, a.line) }
            val nullable = a.use != "required" && default == null
            val claim =
                nameAndClaim(original, "attribute", where, claimed, whereCollision, a.line)
                    ?: return null
            val (name, nameAnnotations) = claim
            val annotations = nameAnnotations + UnitAnnotation("xsd", "attribute", null)
            return UnitField(name, type, nullable, default, a.doc, annotations)
        }

        /**
         * The field's final name, claimed against [claimed]: reports [ImportCodes.RENAMED] when
         * [original] needed fixing, and [ImportCodes.UNRESOLVED] (dropping the field, returning
         * `null`) when it collides with one already claimed in this record.
         */
        private fun nameAndClaim(
            original: String,
            kind: String,
            whereConstruct: String,
            claimed: MutableMap<String, String>,
            whereCollision: String,
            line: Int,
        ): Pair<String, List<UnitAnnotation>>? {
            val (name, nameAnnotation) = fieldNameFor(original)
            if (nameAnnotation != null) {
                diagnostics +=
                    lossy(
                        ImportCodes.RENAMED,
                        whereConstruct,
                        "$kind '$original' is not a Schemata identifier; imported as '$name' with @xsd(name)",
                        line,
                    )
            }
            val existing = claimed[name]
            if (existing != null) {
                diagnostics += collision(whereCollision, existing, whereConstruct, name, line)
                return null
            }
            claimed[name] = whereConstruct
            return name to listOfNotNull(nameAnnotation)
        }

        /**
         * Quotes a scalar default; a default on an enum-to-be reference resolves to the imported
         * value's own name, looked up by the XSD enumeration text it was declared with — `null` (no
         * default emitted) when [sourceType] doesn't resolve to one of them.
         */
        private fun defaultLiteralFor(
            type: UnitType,
            raw: String,
            sourceType: QName?,
            where: String,
            line: Int,
        ): String? =
            when (type) {
                is UnitType.Scalar -> ImportTypes.defaultLiteral(type.builtin, raw)
                is UnitType.Ref -> sourceType?.let { enumValueNameFor(it, raw, where, line) }
                else -> raw
            }

        /**
         * The imported name of the enum value [qname] declares with XSD text [raw]: `null` with no
         * diagnostic when [qname] isn't a genuine enumerated simple type at all; `null` with
         * [ImportCodes.APPROXIMATED] at [where] when it is one but [raw] matches none of its
         * values.
         */
        private fun enumValueNameFor(qname: QName, raw: String, where: String, line: Int): String? {
            if (qname.namespace == ImportTypes.XS) return null
            val targetDoc =
                allDocs.firstOrNull { it.targetNamespace == qname.namespace } ?: return null
            val st = targetDoc.simpleTypes.firstOrNull { it.name == qname.local } ?: return null
            if (!hasEnumeration(st)) return null
            val restriction = st.variety as XVariety.Restriction
            val facet =
                restriction.facets.firstOrNull { it.name == "enumeration" && it.value == raw }
            if (facet == null) {
                val enumName =
                    typeNames[QName(targetDoc.targetNamespace, qname.local)]?.finalName
                        ?: qname.local
                diagnostics +=
                    lossy(
                        ImportCodes.APPROXIMATED,
                        where,
                        "default '$raw' is not a value of enum '$enumName'; dropped",
                        line,
                    )
                return null
            }
            return fieldNameFor(facet.value).first
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
            val hasUnique =
                el.uniques.any {
                    it.fields == listOf("@key") && it.selector.substringAfterLast(':') == "entry"
                }
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
                return mapped.type
            }
            val targetDoc =
                allDocs.firstOrNull { it.targetNamespace == qname.namespace } ?: return null
            val ct = targetDoc.complexTypes.firstOrNull { it.name == qname.local }
            if (ct != null) {
                val name = qualifiedTypeName(targetDoc, ct.name!!)
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
            if (hasEnumeration(st)) return UnitType.Ref(qualifiedTypeName(targetDoc, st.name!!))
            return resolveNamedSimpleType(st, targetDoc.path, where)
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

        private fun resolveNamedSimpleType(
            st: XSimpleType,
            path: String,
            where: String,
        ): UnitType.Scalar {
            return when (val variety = st.variety) {
                is XVariety.Restriction -> {
                    val base =
                        when {
                            variety.base != null ->
                                resolveSimpleTypeByQName(variety.base, where, st.line)
                                    ?: UnitType.Scalar("string", emptyList())
                            variety.inlineBase != null ->
                                resolveNamedSimpleType(variety.inlineBase, path, where)
                            else -> UnitType.Scalar("string", emptyList())
                        }
                    val (refined, notes) = ImportTypes.facets(base, variety.facets)
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
        ): UnitType.Scalar? {
            if (qname.namespace == ImportTypes.XS) {
                val mapped = ImportTypes.builtin(qname.local) ?: return null
                mapped.notes.forEach { diagnostics += lossy(ImportCodes.WIDENED, where, it, line) }
                return mapped.type
            }
            val targetDoc =
                allDocs.firstOrNull { it.targetNamespace == qname.namespace } ?: return null
            val st = targetDoc.simpleTypes.firstOrNull { it.name == qname.local } ?: return null
            return resolveNamedSimpleType(st, targetDoc.path, where)
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
            val hasUnique =
                el.uniques.any {
                    it.fields == listOf("@key") && it.selector.substringAfterLast(':') == "entry"
                }
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
            return Diagnostic(code, "$where: $tail", Span(doc.path, l, 1, l, 1), helpFor(code))
        }

        /** The `SCH24xx` id a facet [Note] carries as a [DiagnosticCode]. */
        private fun codeFor(id: String): DiagnosticCode =
            when (id) {
                "SCH2401" -> ImportCodes.UNRESOLVED
                "SCH2402" -> ImportCodes.RENAMED
                "SCH2403" -> ImportCodes.APPROXIMATED
                "SCH2404" -> ImportCodes.WIDENED
                else -> ImportCodes.DROPPED
            }

        /**
         * A facet [Note] at [path] (the simple type's own document, which may differ from [doc]).
         */
        private fun noteDiagnostic(path: String, where: String, note: Note): Diagnostic {
            val line = note.line.coerceAtLeast(1)
            val code = codeFor(note.code)
            return Diagnostic(
                code,
                "$where: ${note.tail}",
                Span(path, line, 1, line, 1),
                helpFor(code),
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
                Span(doc.path, l, 1, l, 1),
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
