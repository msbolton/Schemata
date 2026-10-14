package io.schemata.importer.openapi

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import io.schemata.importer.ImportCodes
import io.schemata.importer.ImportInput
import io.schemata.lang.Diagnostic
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.parser.core.models.ParseOptions
import io.swagger.v3.parser.util.DeserializationUtils
import io.swagger.v3.parser.util.OpenAPIDeserializer
import org.yaml.snakeyaml.error.MarkedYAMLException

/**
 * One document as read: its [tree] exactly as written (the importer compares components and follows
 * references in it) and swagger-parser's [api] model of the same text. [version] is the `openapi`
 * value, such as `3.0.3` or `3.1.0`.
 */
data class OpenApiDoc(
    val path: String,
    val input: ImportInput,
    val tree: JsonNode,
    val api: OpenAPI,
    val version: String,
)

sealed interface ReadOutcome {
    /** The document, and swagger-parser's messages about it as warnings. */
    data class Read(val doc: OpenApiDoc, val warnings: List<Diagnostic>) : ReadOutcome

    /** The document cannot be imported; [diagnostics] hold at least one error. */
    data class Unreadable(val diagnostics: List<Diagnostic>) : ReadOutcome
}

/**
 * Reads YAML or JSON into a tree with swagger-parser's own reader, then builds the model from that
 * tree with `resolve` off: the model is built from this one text, and nothing a reference names is
 * fetched or read. The importer follows references itself.
 */
object OpenApiReader {
    private const val PARSE_HELP = "give the importer an OpenAPI 3.0 or 3.1 document"
    private const val SWAGGER_HELP =
        "convert the document with a Swagger 2.0 to OpenAPI 3 converter"
    private const val PARSER_HELP =
        "fix the document where the message points; the import ignores that part"

    /** The versions read: 3.0.x and 3.1.x, which swagger-parser reads into one model. */
    private val VERSION = Regex("""3\.[01](\.\S*)?""")

    fun read(input: ImportInput): ReadOutcome = read(input, fragmentVersion = null)

    /**
     * Reads [input]. Without [fragmentVersion], a file with no `openapi` key cannot be read. With
     * it, such a file is a part of a document of that version, such as a file a `$ref` names that
     * holds only components, or one schema. It is read as its tree and an empty model.
     */
    fun read(input: ImportInput, fragmentVersion: String?): ReadOutcome {
        val path = input.path
        val tree =
            try {
                DeserializationUtils.deserializeIntoTree(input.content, path)
            } catch (e: RuntimeException) {
                return cannotParse(path, reason(e))
            }
        if (tree == null || !tree.isObject) {
            return cannotParse(path, "the top level is not an object")
        }
        val declared = tree.get("openapi")
        if (declared == null) {
            tree.get("swagger")?.let {
                return ReadOutcome.Unreadable(
                    listOf(
                        located(
                            path,
                            ImportCodes.UNRESOLVED,
                            "Swagger ${it.asText()} is not read; convert it to OpenAPI 3 first",
                            SWAGGER_HELP,
                        )
                    )
                )
            }
            if (fragmentVersion == null) return cannotParse(path, "no top-level openapi key")
            return ReadOutcome.Read(
                OpenApiDoc(path, input, tree, OpenAPI(), fragmentVersion),
                emptyList(),
            )
        }
        val version = declared.asText()
        if (!VERSION.matches(version)) {
            return cannotParse(path, "OpenAPI $version is not 3.0 or 3.1")
        }
        val result =
            OpenAPIDeserializer()
                .deserialize(tree, path, ParseOptions().apply { isResolve = false })
        val messages =
            result.messages.orEmpty().map { it.trim().removeSuffix(".") }.filter { it.isNotEmpty() }
        // Without a model the document cannot be imported, so each message is an error then.
        val api =
            result.openAPI
                ?: return ReadOutcome.Unreadable(
                    messages
                        .ifEmpty { listOf("swagger-parser built no model from it") }
                        .map { cannotParseDiagnostic(path, it) }
                )
        return ReadOutcome.Read(
            OpenApiDoc(path, input, tree, api, version),
            messages.map { located(path, ImportCodes.DROPPED, "$it; ignored", PARSER_HELP) },
        )
    }

    private fun cannotParse(path: String, reason: String): ReadOutcome =
        ReadOutcome.Unreadable(listOf(cannotParseDiagnostic(path, reason)))

    private fun cannotParseDiagnostic(path: String, reason: String): Diagnostic =
        located(
            path,
            ImportCodes.UNRESOLVED,
            "cannot parse: ${reason.trim().removeSuffix(".")}",
            PARSE_HELP,
        )

    /**
     * What went wrong, on one line: the YAML or JSON parser's own words and the 1-based line and
     * column it stopped at. swagger-parser wraps the parser's exception in its own, whose message
     * spans several lines and quotes the input.
     */
    private fun reason(e: Throwable): String {
        val causes = generateSequence(e) { it.cause }.toList()
        for (cause in causes) {
            if (cause is MarkedYAMLException) {
                val mark = cause.problemMark
                val where = mark?.let { " at line ${it.line + 1}, column ${it.column + 1}" } ?: ""
                return (cause.problem ?: cause.context ?: "the YAML is malformed") + where
            }
            if (cause is JsonProcessingException) {
                val location = cause.location
                val where = location?.let { " at line ${it.lineNr}, column ${it.columnNr}" } ?: ""
                return cause.originalMessage + where
            }
        }
        return (causes.last().message ?: e.toString()).lineSequence().first()
    }
}
