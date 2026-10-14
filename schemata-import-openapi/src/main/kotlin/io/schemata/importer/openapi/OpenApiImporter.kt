package io.schemata.importer.openapi

import io.schemata.importer.ImportCodes
import io.schemata.importer.ImportInput
import io.schemata.importer.ImportNames
import io.schemata.importer.ImportResult
import io.schemata.importer.Importer
import io.schemata.importer.Roots
import io.schemata.importer.importResult
import io.schemata.lang.Diagnostic
import io.swagger.v3.parser.util.DeserializationUtils

/**
 * Imports OpenAPI 3.0 and 3.1 documents, YAML or JSON. Each document is one schema. The import runs
 * in stages over an [ImportContext]: read every document, name each one's schema, check every
 * reference, lower the components, the operations, then security and `info`, and emit. Nothing is
 * written when any stage reports an error.
 */
object OpenApiImporter : Importer {
    override fun import(
        inputs: List<ImportInput>,
        namespace: String?,
        locate: (String) -> ImportInput?,
    ): ImportResult {
        val diagnostics = mutableListOf<Diagnostic>()
        val report = Report(diagnostics)
        val docs = readAll(inputs, report)
        val namespaces = namespaces(docs, inputs.firstOrNull(), namespace, report)
        val refs = Refs(docs.associateBy { it.path }, locate, report)
        docs.forEach(refs::checkAll)
        val context = ImportContext(docs, namespaces, refs, report)
        val schemas = lowerSchemas(context)
        lowerOperations(context)
        lowerSecurityAndInfo(context)
        // read last: lowering an operation adds the models it synthesizes
        addDeclarations(context, schemas)
        return importResult(context.units(), diagnostics)
    }

    /** True when [content] parses as YAML or JSON with a top-level `openapi` or `swagger` key. */
    fun isDocument(content: String): Boolean {
        val tree =
            try {
                DeserializationUtils.deserializeIntoTree(content, "document")
            } catch (e: RuntimeException) {
                return false
            }
        return tree != null && tree.isObject && (tree.has("openapi") || tree.has("swagger"))
    }

    /** Every input that reads, in input order; one that does not is reported and left out. */
    private fun readAll(inputs: List<ImportInput>, report: Report): List<OpenApiDoc> =
        inputs.mapNotNull { input ->
            when (val outcome = OpenApiReader.read(input)) {
                is ReadOutcome.Read -> {
                    outcome.warnings.forEach(report::add)
                    outcome.doc
                }
                is ReadOutcome.Unreadable -> {
                    outcome.diagnostics.forEach(report::add)
                    null
                }
            }
        }

    /**
     * Each document's schema name, by path. [namespace] names the [first] input. Any other input is
     * named by its path under the root it was found in, otherwise by its file name, with a
     * `.openapi` before the extension left out in either case. A name derived from a file name is
     * reported. Two documents with one name are an error, since each document is its own schema.
     */
    private fun namespaces(
        docs: List<OpenApiDoc>,
        first: ImportInput?,
        namespace: String?,
        report: Report,
    ): Map<String, String> {
        val names = LinkedHashMap<String, String>()
        val owners = HashMap<String, OpenApiDoc>()
        for (doc in docs) {
            val (name, derived) =
                if (namespace != null && doc.input === first) namespace to false
                else derivedName(doc.input)
            if (derived) {
                report.at(
                    doc,
                    null,
                    ImportCodes.RENAMED,
                    "schema name '$name' was derived from the file name",
                )
            }
            owners.putIfAbsent(name, doc)?.let { owner ->
                report.at(
                    doc,
                    null,
                    ImportCodes.UNRESOLVED,
                    "schema name '$name' is taken by ${owner.path}",
                    ImportCodes.RENAME_HELP,
                )
            }
            names[doc.path] = name
        }
        return names
    }

    private fun derivedName(input: ImportInput): Pair<String, Boolean> {
        val file = input.path.replace('\\', '/').substringAfterLast('/')
        return Roots.namespaceFor(
            input.copy(relative = input.relative?.let(::withoutFormatSuffix)),
            null,
            withoutFormatSuffix(file),
        )
    }

    /** `cart.openapi.json` is `cart.json`: the `.openapi` names the format, not the schema. */
    private fun withoutFormatSuffix(path: String): String = FORMAT_SUFFIX.replace(path, "$1")

    private val FORMAT_SUFFIX = Regex("""\.openapi(\.[^./\\]+)$""", RegexOption.IGNORE_CASE)

    /**
     * Lowers every input document's components, each into the schema its dotted name gives. A file
     * reached only through a `$ref` is in the schema of its file stem, a `.openapi` before the
     * extension left out.
     */
    private fun lowerSchemas(context: ImportContext): SchemaLowering =
        SchemaLowering(context.docs, context.refs, context.report) { doc ->
            context.namespaces[doc.path] ?: ImportNames.namespaceStem(withoutFormatSuffix(doc.path))
        }

    /**
     * Puts each schema's declarations, and the schemas they refer to, on its draft. A schema that
     * only components name, such as `storefront.common`, gets its draft here, named after the first
     * document that put a declaration in it.
     */
    private fun addDeclarations(context: ImportContext, schemas: SchemaLowering) {
        for ((namespace, declarations) in schemas.declarations()) {
            val draft = context.draft(namespace, schemas.sourcePath(namespace) ?: namespace)
            draft.declarations += declarations
            draft.imports += schemas.imports(namespace)
        }
    }

    /** Paths and operations become services. No operation is lowered: a unit holds no service. */
    private fun lowerOperations(context: ImportContext) {}

    /** Each document's `info` on its unit: `info.description` is the schema's doc comment. */
    private fun lowerSecurityAndInfo(context: ImportContext) {
        for (doc in context.docs) {
            context.draftOf(doc).doc = doc.api.info?.description?.trim()?.takeIf { it.isNotEmpty() }
        }
    }
}
