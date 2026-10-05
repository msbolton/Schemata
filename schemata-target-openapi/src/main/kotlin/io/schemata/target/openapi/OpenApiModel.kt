package io.schemata.target.openapi

import io.schemata.core.ir.Verb
import io.schemata.target.TargetModel
import io.schemata.target.jsonschema.JsonDef
import io.schemata.target.jsonschema.JsonSchema

/** Every `.openapi.json` the compilation produces: one per namespace that declares a service. */
data class OpenApiModel(val documents: List<OpenApiDocument>) : TargetModel

/**
 * One OpenAPI 3.1 document, legal by construction: every `$ref` points into [components], every
 * `operationId` and verb-and-path pair is unique, and no two paths differ only in their parameters'
 * names.
 */
data class OpenApiDocument(
    val path: String,
    val title: String,
    val version: String,
    val description: String?,
    val server: String?,
    val tags: List<Tag>,
    val paths: List<PathItem>,
    val components: List<JsonDef>,
)

data class Tag(val name: String, val description: String?)

/** One path and its operations, in verb order (get, post, put, patch, delete, head, options). */
data class PathItem(val path: String, val operations: List<OpenApiOperation>)

data class OpenApiOperation(
    val verb: Verb,
    val operationId: String,
    val tag: String,
    val summary: String?,
    val description: String?,
    val deprecated: Boolean,
    val parameters: List<Parameter>,
    val requestBody: Body?,
    val response: Response,
)

/**
 * [location] is `path` or `query`; [style] prints `style: form` and `explode: true`, as a query
 * parameter carries them.
 */
data class Parameter(
    val name: String,
    val location: String,
    val required: Boolean,
    val schema: JsonSchema,
    val description: String?,
    val deprecated: Boolean,
    val style: Boolean,
)

/** A required request body of one media type. */
data class Body(val mediaType: String, val schema: JsonSchema)

/** The one response an operation describes: `200` with content, or `204` without. */
sealed interface Response {
    data class Content(
        val status: Int,
        val description: String,
        val mediaType: String,
        val schema: JsonSchema,
    ) : Response

    data class Empty(val description: String) : Response
}
