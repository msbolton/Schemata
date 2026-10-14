package io.schemata.importer.openapi

import io.schemata.importer.SchemataUnit
import io.schemata.importer.UnitAnnotation
import io.schemata.importer.UnitDecl
import io.schemata.importer.UnitService

/** One schema as the stages of an import fill it in, until it becomes a [SchemataUnit]. */
internal class UnitDraft(val namespace: String, val sourcePath: String) {
    var doc: String? = null
    val annotations = mutableListOf<UnitAnnotation>()
    /** Sorted, since the declarations and the services each add the schemas they use. */
    val imports = sortedSetOf<String>()
    val declarations = mutableListOf<UnitDecl>()
    val services = mutableListOf<UnitService>()

    fun unit(): SchemataUnit =
        SchemataUnit(
            namespace = namespace,
            annotations = annotations.toList(),
            doc = doc,
            imports = imports.toList(),
            declarations = declarations.toList(),
            services = services.toList(),
            sourcePath = sourcePath,
        )
}

/**
 * What the stages of one import share: the documents read, in input order, each one's schema name,
 * the references, the report, and a draft for each schema. A document's schema has a draft from the
 * start. A schema only components name (`storefront.common.Money`) gets one when a stage first asks
 * for it.
 */
internal class ImportContext(
    val docs: List<OpenApiDoc>,
    val namespaces: Map<String, String>,
    val refs: Refs,
    val report: Report,
) {
    private val drafts = LinkedHashMap<String, UnitDraft>()

    init {
        docs.forEach { draft(namespaceOf(it), it.path) }
    }

    fun namespaceOf(doc: OpenApiDoc): String = namespaces.getValue(doc.path)

    fun draftOf(doc: OpenApiDoc): UnitDraft = draft(namespaceOf(doc), doc.path)

    /**
     * The draft for [namespace], made on first use; [sourcePath] is the file it was first met in.
     */
    fun draft(namespace: String, sourcePath: String): UnitDraft =
        drafts.getOrPut(namespace) { UnitDraft(namespace, sourcePath) }

    fun units(): List<SchemataUnit> = drafts.values.map { it.unit() }
}
