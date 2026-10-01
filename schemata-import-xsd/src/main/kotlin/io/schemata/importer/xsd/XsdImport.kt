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
 * documentation, and the full type- and field-naming rules. A `xs:choice`-shaped complex type (a
 * union) and other not-yet-handled constructs still fail loudly with `error` so their tests are not
 * silently skipped.
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
        // is lowered, so a cross-document reference always sees the final name.
        val typeNames = mutableMapOf<QName, TypeNameInfo>()
        live.forEach { doc ->
            val originals =
                doc.complexTypes.mapNotNull { it.name } + doc.simpleTypes.mapNotNull { it.name }
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
                val lowering = NamespaceLowering(doc, live, names, typeNames, diagnostics)
                val declarations = mutableListOf<UnitDecl>()
                doc.complexTypes.forEach { declarations += lowering.declaration(it) }
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

    private fun noteDiagnostic(path: String, where: String, note: Note): Diagnostic {
        val line = note.line.coerceAtLeast(1)
        val span = Span(path, line, 1, line, 1)
        val message = "$where: ${note.tail}"
        val (code, help) =
            when (note.code) {
                "SCH2402" ->
                    ImportCodes.RENAMED to
                        "keep the annotation so the regenerated XSD uses the original name"
                "SCH2403" ->
                    ImportCodes.APPROXIMATED to
                        "review the imported record; the regenerated XSD will differ here"
                "SCH2404" -> ImportCodes.WIDENED to "narrow the type by hand if the data needs it"
                else ->
                    ImportCodes.DROPPED to
                        "add the missing part by hand; Schemata cannot express it"
            }
        return Diagnostic(code, message, span, help)
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
     * set), so this only decides fields, nesting, roots, and the lossy notes that go with them.
     */
    private class NamespaceLowering(
        val doc: XsdDoc,
        val allDocs: List<XsdDoc>,
        val namespaceNames: Map<XsdDoc, String>,
        val typeNames: Map<QName, TypeNameInfo>,
        val diagnostics: MutableList<Diagnostic>,
    ) {
        fun declaration(ct: XComplexType): UnitRecord {
            val original = ct.name ?: error("not yet imported: anonymous complex type")
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
            val (fields, nested) = fieldsAndNested(ct, "complex type '$original'")
            val annotations = listOfNotNull(info.annotation, rootAnnotation)
            return UnitRecord(info.finalName, fields, nested, ct.doc, annotations)
        }

        /** A global element with its own inline complex type: a top-level record, always a root. */
        fun topLevelRecord(el: XElement): UnitRecord {
            val original = el.name ?: error("not yet imported: anonymous global element")
            val name = ImportNames.upperCamel(original)
            val ct = el.inlineComplex ?: error("not yet imported: anonymous global element")
            val (fields, nested) = fieldsAndNested(ct, "element '$original'")
            return UnitRecord(name, fields, nested, ct.doc ?: el.doc, emptyList())
        }

        private fun fieldsAndNested(
            ct: XComplexType,
            whereCollision: String,
        ): Pair<List<UnitField>, List<UnitDecl>> {
            val nested = mutableListOf<UnitDecl>()
            val claimed = mutableMapOf<String, String>()
            val elementFields =
                when (val content = ct.content) {
                    is XContent.Sequence ->
                        content.particles.map { particle ->
                            when (particle) {
                                is XParticle.Element ->
                                    field(particle.element, claimed, whereCollision, nested)
                                is XParticle.Any -> error("not yet imported: xs:any")
                                is XParticle.GroupRef ->
                                    error("not yet imported: xs:group reference")
                                is XParticle.Nested -> error("not yet imported: nested particle")
                            }
                        }
                    is XContent.Empty -> emptyList()
                    is XContent.Choice -> error("not yet imported: xs:choice")
                    is XContent.All -> error("not yet imported: xs:all")
                    is XContent.Extension -> error("not yet imported: xs:extension")
                    is XContent.Restriction -> error("not yet imported: xs:restriction")
                }
            val attributeFields =
                ct.attributes.map { use ->
                    when (use) {
                        is XAttributeUse.Attribute ->
                            attribute(use.attribute, claimed, whereCollision)
                        is XAttributeUse.GroupRef ->
                            error("not yet imported: xs:attributeGroup reference")
                        is XAttributeUse.AnyAttribute -> error("not yet imported: xs:anyAttribute")
                    }
                }
            return (elementFields + attributeFields).filterNotNull() to nested
        }

        private fun buildNestedRecord(ct: XComplexType, name: String): UnitRecord {
            val (fields, nested) = fieldsAndNested(ct, "complex type '$name'")
            return UnitRecord(name, fields, nested, ct.doc, emptyList())
        }

        private data class Resolved(
            val type: UnitType,
            val nullable: Boolean,
            val default: String?,
        )

        private fun field(
            el: XElement,
            claimed: MutableMap<String, String>,
            whereCollision: String,
            nested: MutableList<UnitDecl>,
        ): UnitField? {
            if (el.ref != null || el.name == null) error("not yet imported: element reference")
            val original = el.name
            val where = "element '$original'"

            val resolved: Resolved? =
                when {
                    el.maxOccurs == 1 && el.type == null && el.inlineComplex != null -> {
                        when (val m = mapWrapper(el, where)) {
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
                                nested += buildNestedRecord(el.inlineComplex, name)
                                Resolved(UnitType.Ref(name), el.minOccurs == 0, null)
                            }
                        }
                    }
                    el.maxOccurs != 1 -> {
                        val itemType = resolveElementItemType(el, where)
                        if (itemType == null) {
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
                            val refinements = listRefinements(el.minOccurs, el.maxOccurs)
                            Resolved(
                                UnitType.ListOf(itemType, el.nillable, refinements),
                                false,
                                null,
                            )
                        }
                    }
                    else -> {
                        val type = resolveElementScalarOrRef(el, where)
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
                            val default = rawDefault?.let { defaultLiteralFor(type, it) }
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
            a: XAttribute,
            claimed: MutableMap<String, String>,
            whereCollision: String,
        ): UnitField? {
            if (a.ref != null || a.name == null) error("not yet imported: attribute reference")
            val original = a.name
            val where = "attribute '$original'"
            val type = resolveAttributeType(a, where)
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
            val default = rawDefault?.let { defaultLiteralFor(type, it) }
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
         * Quotes a scalar default; a default on an enum-to-be reference keeps the bare value name.
         */
        private fun defaultLiteralFor(type: UnitType, raw: String): String =
            when (type) {
                is UnitType.Scalar -> ImportTypes.defaultLiteral(type.builtin, raw)
                is UnitType.Ref -> ImportNames.lowerSnake(raw)
                else -> raw
            }

        private fun resolveElementScalarOrRef(el: XElement, where: String): UnitType? {
            if (el.inlineSimple != null)
                return resolveNamedSimpleType(el.inlineSimple, doc.path, where)
            if (el.type != null) return resolveTypeRef(el.type, where, el.line)
            error("not yet imported: element without a declared type")
        }

        private fun resolveElementItemType(el: XElement, where: String): UnitType? {
            if (el.inlineSimple != null)
                return resolveNamedSimpleType(el.inlineSimple, doc.path, where)
            if (el.type != null) return resolveTypeRef(el.type, where, el.line)
            error("not yet imported: list item without a declared type")
        }

        private fun resolveAttributeType(a: XAttribute, where: String): UnitType? {
            if (a.inlineSimple != null)
                return resolveNamedSimpleType(a.inlineSimple, doc.path, where)
            if (a.type != null) return resolveTypeRef(a.type, where, a.line)
            error("not yet imported: attribute without a declared type")
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
            if (ct != null) return UnitType.Ref(qualifiedTypeName(targetDoc, ct.name!!))
            val st = targetDoc.simpleTypes.firstOrNull { it.name == qname.local } ?: return null
            if (hasEnumeration(st)) return UnitType.Ref(qualifiedTypeName(targetDoc, st.name!!))
            return resolveNamedSimpleType(st, targetDoc.path, where)
        }

        private fun qualifiedTypeName(targetDoc: XsdDoc, original: String): String {
            val info = typeNames.getValue(QName(targetDoc.targetNamespace, original))
            return if (targetDoc === doc) info.finalName
            else "${namespaceNames.getValue(targetDoc)}.${info.finalName}"
        }

        private fun hasEnumeration(st: XSimpleType): Boolean =
            (st.variety as? XVariety.Restriction)?.facets?.any { it.name == "enumeration" } == true

        private fun resolveNamedSimpleType(
            st: XSimpleType,
            path: String,
            where: String,
        ): UnitType.Scalar {
            return when (val variety = st.variety) {
                is XVariety.Restriction -> {
                    if (variety.facets.any { it.name == "enumeration" }) {
                        error("not yet imported: simple type with enumeration")
                    }
                    val base =
                        when {
                            variety.base != null ->
                                resolveSimpleTypeByQName(variety.base, where, st.line)
                                    ?: UnitType.Scalar("string", emptyList())
                            variety.inlineBase != null ->
                                resolveNamedSimpleType(variety.inlineBase, path, where)
                            else ->
                                error("not yet imported: simple type restriction without a base")
                        }
                    val (refined, notes) = ImportTypes.facets(base, variety.facets)
                    notes.forEach { diagnostics += noteDiagnostic(path, where, it) }
                    refined
                }
                is XVariety.ListOf -> error("not yet imported: xs:list simple type")
                is XVariety.Union -> error("not yet imported: xs:union simple type")
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
         * extension for a plain value, or a `value` child for a value with its own refinements.
         * `null` when the shape doesn't match at all, in which case the caller falls back to an
         * ordinary nested record.
         */
        private fun recognizeMapShape(el: XElement): EntryShape? {
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
                    if (valueEl.name != "value") return null
                    val key = singleKeyAttribute(ec) ?: return null
                    val valueType =
                        resolveElementScalarOrRef(valueEl, "element 'value'") ?: return null
                    EntryShape(key, valueType, entry)
                }
                else -> null
            }
        }

        private fun singleKeyAttribute(ec: XComplexType): XAttribute? {
            val use = ec.attributes.singleOrNull() as? XAttributeUse.Attribute ?: return null
            return use.attribute.takeIf { it.name == "key" }
        }

        private fun mapWrapper(el: XElement, where: String): MapResult {
            val shape = recognizeMapShape(el) ?: return MapResult.NotAMap
            val keyType =
                resolveAttributeType(shape.keyAttribute, "attribute 'key'")
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

        /** `"$where: $tail"` with the standard help text for [code]. */
        fun lossy(code: DiagnosticCode, where: String, tail: String, line: Int): Diagnostic {
            val help =
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
            val l = line.coerceAtLeast(1)
            return Diagnostic(code, "$where: $tail", Span(doc.path, l, 1, l, 1), help)
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
        private fun typeCollision(original: String, other: String, line: Int): Diagnostic {
            val l = line.coerceAtLeast(1)
            return Diagnostic(
                ImportCodes.UNRESOLVED,
                "complex type '$original' and '$other' both lower to type '$original'",
                Span(doc.path, l, 1, l, 1),
                "rename one of them",
            )
        }
    }
}
