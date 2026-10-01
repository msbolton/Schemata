package io.schemata.importer.xsd

import io.schemata.lang.Diagnostic
import io.schemata.lang.DiagnosticCode
import io.schemata.lang.Span

/**
 * Lowers a resolved set of [XsdDoc]s (includes already merged, every referenced namespace present)
 * into [SchemataUnit]s. This task handles namespace naming, imports, and records whose fields are
 * scalars or references to a named complex or simple type; everything else — attributes, choices,
 * extensions, groups, `xs:any`, nested particles, simple types with enumerations, lists and maps —
 * arrives in later tasks and fails loudly with `error` so their tests are not silently skipped.
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
                val declarations =
                    doc.complexTypes.map { declaration(it, doc, live, names, diagnostics) }
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

    private fun declaration(
        ct: XComplexType,
        doc: XsdDoc,
        allDocs: List<XsdDoc>,
        names: Map<XsdDoc, String>,
        diagnostics: MutableList<Diagnostic>,
    ): UnitRecord {
        val original = ct.name ?: error("not yet imported: anonymous complex type")
        val (name, nameAnnotation) = typeName(original)
        if (nameAnnotation != null) {
            diagnostics +=
                renamed(
                    doc.path,
                    ct.line,
                    "complex type '$original' is not a Schemata identifier; imported as '$name' with @xsd(name)",
                )
        }
        if (ct.attributes.isNotEmpty()) error("not yet imported: xs:attribute")
        val fields =
            when (val content = ct.content) {
                is XContent.Sequence ->
                    content.particles.mapNotNull { particle ->
                        when (particle) {
                            is XParticle.Element ->
                                field(particle.element, doc, allDocs, names, diagnostics)
                            is XParticle.Any -> error("not yet imported: xs:any")
                            is XParticle.GroupRef -> error("not yet imported: xs:group reference")
                            is XParticle.Nested -> error("not yet imported: nested particle")
                        }
                    }
                is XContent.Empty -> emptyList()
                is XContent.Choice -> error("not yet imported: xs:choice")
                is XContent.All -> error("not yet imported: xs:all")
                is XContent.Extension -> error("not yet imported: xs:extension")
                is XContent.Restriction -> error("not yet imported: xs:restriction")
            }
        val isRoot =
            doc.elements.any { it.ref == null && it.type == QName(doc.targetNamespace, original) }
        val annotations =
            listOfNotNull(
                nameAnnotation,
                if (!isRoot) UnitAnnotation("xsd", "root", "false") else null,
            )
        return UnitRecord(name, fields, emptyList(), ct.doc, annotations)
    }

    /**
     * `OrderType` → `Order` when it would regenerate; otherwise the UpperCamel name with
     * `@xsd(name)`.
     */
    private fun typeName(original: String): Pair<String, UnitAnnotation?> {
        val stripped = ImportNames.stripType(original)
        if (stripped != null) return stripped to null
        if (ImportNames.isUpperCamel(original)) return original to null
        val fixed = ImportNames.upperCamel(original)
        return fixed to UnitAnnotation("xsd", "name", "\"$original\"")
    }

    /** `full-name` → `full_name` with `@xsd(name)`; a valid identifier is kept as-is. */
    private fun fieldName(original: String): Pair<String, UnitAnnotation?> {
        if (ImportNames.isLowerSnake(original)) return original to null
        val fixed = ImportNames.lowerSnake(original)
        return fixed to UnitAnnotation("xsd", "name", "\"$original\"")
    }

    private fun field(
        el: XElement,
        doc: XsdDoc,
        allDocs: List<XsdDoc>,
        names: Map<XsdDoc, String>,
        diagnostics: MutableList<Diagnostic>,
    ): UnitField? {
        val original = el.name ?: error("not yet imported: element reference")
        if (el.inlineComplex != null) error("not yet imported: nested particle")
        if (el.inlineSimple != null) error("not yet imported: nested particle")
        if (el.maxOccurs != 1) error("not yet imported: maxOccurs > 1")
        val type =
            resolveTypeRef(
                el.type,
                doc,
                allDocs,
                names,
                "element '$original'",
                el.line,
                diagnostics,
            )
        if (type == null) {
            diagnostics +=
                unresolved(
                    doc.path,
                    el.line,
                    "element '$original': type '${el.type?.local}' cannot be resolved",
                )
            return null
        }
        val (name, nameAnnotation) = fieldName(original)
        if (nameAnnotation != null) {
            diagnostics +=
                renamed(
                    doc.path,
                    el.line,
                    "element '$original' is not a Schemata identifier; imported as '$name' with @xsd(name)",
                )
        }
        val default =
            el.default?.let { raw ->
                if (type is UnitType.Scalar) ImportTypes.defaultLiteral(type.builtin, raw) else raw
            }
        val nullable = el.minOccurs == 0 && el.default == null
        return UnitField(name, type, nullable, default, el.doc, listOfNotNull(nameAnnotation))
    }

    private fun resolveTypeRef(
        qname: QName?,
        doc: XsdDoc,
        allDocs: List<XsdDoc>,
        names: Map<XsdDoc, String>,
        where: String,
        line: Int,
        diagnostics: MutableList<Diagnostic>,
    ): UnitType? {
        if (qname == null) error("not yet imported: element without a declared type")
        if (qname.namespace == ImportTypes.XS) {
            val mapped = ImportTypes.builtin(qname.local) ?: return null
            mapped.notes.forEach { diagnostics += widened(doc.path, line, "$where: $it") }
            return mapped.type
        }
        val targetDoc = allDocs.firstOrNull { it.targetNamespace == qname.namespace } ?: return null
        val ct = targetDoc.complexTypes.firstOrNull { it.name == qname.local }
        if (ct != null) {
            val (recordName, _) = typeName(ct.name!!)
            val qualified =
                if (targetDoc === doc) recordName else "${names.getValue(targetDoc)}.$recordName"
            return UnitType.Ref(qualified)
        }
        val st = targetDoc.simpleTypes.firstOrNull { it.name == qname.local } ?: return null
        return resolveNamedSimpleType(st, targetDoc, allDocs, where, diagnostics)
    }

    private fun resolveNamedSimpleType(
        st: XSimpleType,
        doc: XsdDoc,
        allDocs: List<XsdDoc>,
        where: String,
        diagnostics: MutableList<Diagnostic>,
    ): UnitType.Scalar {
        return when (val variety = st.variety) {
            is XVariety.Restriction -> {
                if (variety.facets.any { it.name == "enumeration" }) {
                    error("not yet imported: simple type with enumeration")
                }
                val base =
                    when {
                        variety.base != null ->
                            resolveSimpleTypeByQName(
                                variety.base,
                                doc,
                                allDocs,
                                where,
                                st.line,
                                diagnostics,
                            ) ?: UnitType.Scalar("string", emptyList())
                        variety.inlineBase != null ->
                            resolveNamedSimpleType(
                                variety.inlineBase,
                                doc,
                                allDocs,
                                where,
                                diagnostics,
                            )
                        else -> error("not yet imported: simple type restriction without a base")
                    }
                val (refined, notes) = ImportTypes.facets(base, variety.facets)
                notes.forEach { diagnostics += noteDiagnostic(doc.path, where, it) }
                refined
            }
            is XVariety.ListOf -> error("not yet imported: xs:list simple type")
            is XVariety.Union -> error("not yet imported: xs:union simple type")
        }
    }

    private fun resolveSimpleTypeByQName(
        qname: QName,
        doc: XsdDoc,
        allDocs: List<XsdDoc>,
        where: String,
        line: Int,
        diagnostics: MutableList<Diagnostic>,
    ): UnitType.Scalar? {
        if (qname.namespace == ImportTypes.XS) {
            val mapped = ImportTypes.builtin(qname.local) ?: return null
            mapped.notes.forEach { diagnostics += widened(doc.path, line, "$where: $it") }
            return mapped.type
        }
        val targetDoc = allDocs.firstOrNull { it.targetNamespace == qname.namespace } ?: return null
        val st = targetDoc.simpleTypes.firstOrNull { it.name == qname.local } ?: return null
        return resolveNamedSimpleType(st, targetDoc, allDocs, where, diagnostics)
    }

    private fun noteDiagnostic(path: String, where: String, note: Note): Diagnostic {
        val message = "$where: ${note.tail}"
        return when (note.code) {
            "SCH2402" -> renamed(path, note.line, message)
            "SCH2403" -> approximated(path, note.line, message)
            "SCH2404" -> widened(path, note.line, message)
            else -> dropped(path, note.line, message)
        }
    }

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

    private fun approximated(path: String, line: Int, message: String) =
        diagnostic(
            ImportCodes.APPROXIMATED,
            path,
            line,
            "$path: $message",
            "review the imported record; the regenerated XSD will differ here",
        )

    private fun widened(path: String, line: Int, message: String) =
        diagnostic(
            ImportCodes.WIDENED,
            path,
            line,
            "$path: $message",
            "narrow the type by hand if the data needs it",
        )

    private fun dropped(path: String, line: Int, message: String) =
        diagnostic(
            ImportCodes.DROPPED,
            path,
            line,
            "$path: $message",
            "add the missing part by hand; Schemata cannot express it",
        )
}
