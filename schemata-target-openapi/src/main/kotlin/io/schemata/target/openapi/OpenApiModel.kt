package io.schemata.target.openapi

import io.schemata.core.ir.Verb
import io.schemata.target.TargetModel
import io.schemata.target.jsonschema.JsonDef
import io.schemata.target.jsonschema.JsonSchema

/** Every `.openapi.json` the compilation produces: one per namespace that declares a service. */
data class OpenApiModel(val documents: List<OpenApiDocument>) : TargetModel

/**
 * One OpenAPI 3.1 document: every `$ref` points into [components], every verb-and-path pair is
 * unique, and no two paths differ only in their parameters' names. An `operationId` is unique among
 * the operations kept; an operation dropped for repeating a route claims none, and two kept
 * operations that share one are reported (SCH2602) but both stay.
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

/**
 * [responses] maps each code to its response, in the order they are written: the success code
 * first, then the error codes ascending, `default` last.
 */
data class OpenApiOperation(
    val verb: Verb,
    val operationId: String,
    val tag: String,
    val summary: String?,
    val description: String?,
    val deprecated: Boolean,
    val parameters: List<Parameter>,
    val requestBody: Body?,
    val responses: Map<String, Response>,
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

/** A required request body, or a response's content, of one media type. */
data class Body(val mediaType: String, val schema: JsonSchema)

/**
 * One response: [content] is null when the response has no body; [headers] are its HTTP headers by
 * name, in the order they are written.
 */
data class Response(
    val description: String,
    val content: Body? = null,
    val headers: Map<String, Header> = emptyMap(),
)

/**
 * A response header. [schema] carries no description, deprecation, or null, as a parameter's schema
 * does: those are the header's own.
 */
data class Header(
    val schema: JsonSchema,
    val description: String?,
    val required: Boolean,
    val deprecated: Boolean,
)
