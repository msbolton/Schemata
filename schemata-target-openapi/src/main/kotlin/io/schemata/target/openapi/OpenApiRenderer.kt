package io.schemata.target.openapi

import io.schemata.target.OutputFile
import io.schemata.target.json.JsonArray
import io.schemata.target.json.JsonBool
import io.schemata.target.json.JsonObject
import io.schemata.target.json.JsonPrinter
import io.schemata.target.json.JsonString
import io.schemata.target.json.JsonValue
import io.schemata.target.json.obj
import io.schemata.target.jsonschema.JsonSchema
import io.schemata.target.jsonschema.JsonSchemaRenderer

/** Prints an [OpenApiModel]; no decisions are made here. */
object OpenApiRenderer {
    private const val VERSION = "3.1.0"

    fun render(model: OpenApiModel): List<OutputFile> =
        model.documents.map { OutputFile(it.path, JsonPrinter.print(document(it))) }

    private fun document(doc: OpenApiDocument): JsonObject {
        val members = mutableListOf<Pair<String, JsonValue>>()
        members += "openapi" to JsonString(VERSION)
        members +=
            "info" to
                JsonObject(
                    listOfNotNull(
                        "title" to JsonString(doc.title),
                        "version" to JsonString(doc.version),
                        doc.description?.let { "description" to JsonString(it) },
                    )
                )
        doc.server?.let { members += "servers" to JsonArray(listOf(obj("url" to JsonString(it)))) }
        doc.security?.let { members += "security" to security(it) }
        members +=
            "tags" to
                JsonArray(
                    doc.tags.map { tag ->
                        JsonObject(
                            listOfNotNull(
                                "name" to JsonString(tag.name),
                                tag.description?.let { "description" to JsonString(it) },
                            )
                        )
                    }
                )
        members +=
            "paths" to
                JsonObject(
                    doc.paths.map { item ->
                        item.path to
                            JsonObject(item.operations.map { it.verb.lower to operation(it) })
                    }
                )
        val components =
            mutableListOf<Pair<String, JsonValue>>(
                "schemas" to JsonObject(doc.components.map { it.key to schema(it.schema) })
            )
        if (doc.securitySchemes.isNotEmpty())
            components +=
                "securitySchemes" to JsonObject(doc.securitySchemes.map { it.name to scheme(it) })
        members += "components" to JsonObject(components)
        return JsonObject(members)
    }

    private fun operation(op: OpenApiOperation): JsonObject {
        val members = mutableListOf<Pair<String, JsonValue>>()
        members += "operationId" to JsonString(op.operationId)
        members += "tags" to JsonArray(listOf(JsonString(op.tag)))
        op.summary?.let { members += "summary" to JsonString(it) }
        op.description?.let { members += "description" to JsonString(it) }
        if (op.deprecated) members += "deprecated" to JsonBool(true)
        if (op.parameters.isNotEmpty())
            members += "parameters" to JsonArray(op.parameters.map(::parameter))
        op.requestBody?.let { body ->
            members +=
                "requestBody" to
                    obj(
                        "required" to JsonBool(true),
                        "content" to content(body.mediaType, body.schema),
                    )
        }
        members += "responses" to JsonObject(op.responses.map { (code, r) -> code to response(r) })
        op.security?.let { members += "security" to security(it) }
        return JsonObject(members)
    }

    private fun parameter(p: Parameter): JsonObject {
        val members = mutableListOf<Pair<String, JsonValue>>()
        members += "name" to JsonString(p.name)
        members += "in" to JsonString(p.location)
        members += "required" to JsonBool(p.required)
        p.description?.let { members += "description" to JsonString(it) }
        if (p.deprecated) members += "deprecated" to JsonBool(true)
        if (p.style) {
            members += "style" to JsonString("form")
            members += "explode" to JsonBool(true)
        }
        members += "schema" to schema(p.schema)
        return JsonObject(members)
    }

    private fun response(r: Response): JsonObject {
        val members = mutableListOf<Pair<String, JsonValue>>()
        members += "description" to JsonString(r.description)
        if (r.headers.isNotEmpty())
            members += "headers" to JsonObject(r.headers.map { (name, h) -> name to header(h) })
        r.content?.let { members += "content" to content(it.mediaType, it.schema) }
        return JsonObject(members)
    }

    private fun header(h: Header): JsonObject {
        val members = mutableListOf<Pair<String, JsonValue>>()
        h.description?.let { members += "description" to JsonString(it) }
        members += "required" to JsonBool(h.required)
        if (h.deprecated) members += "deprecated" to JsonBool(true)
        members += "schema" to schema(h.schema)
        return JsonObject(members)
    }

    private fun content(mediaType: String, schema: JsonSchema): JsonObject =
        obj(mediaType to obj("schema" to schema(schema)))

    private fun schema(schema: JsonSchema): JsonObject = JsonSchemaRenderer.node(schema)

    /** A `security` list: one object per alternative, mapping the scheme to its scopes. */
    private fun security(requirements: List<Requirement>): JsonArray =
        JsonArray(requirements.map { obj(it.scheme to JsonArray(it.scopes.map(::JsonString))) })

    private fun scheme(scheme: SecurityScheme): JsonObject =
        when (scheme) {
            is SecurityScheme.Http ->
                JsonObject(
                    listOfNotNull(
                        "type" to JsonString("http"),
                        "scheme" to JsonString(scheme.scheme),
                        scheme.bearerFormat?.let { "bearerFormat" to JsonString(it) },
                    )
                )
            is SecurityScheme.ApiKey ->
                obj(
                    "type" to JsonString("apiKey"),
                    "name" to JsonString(scheme.parameter),
                    "in" to JsonString(scheme.location),
                )
            is SecurityScheme.OAuth2 -> {
                val scopes = "scopes" to JsonObject(scheme.scopes.map { it to JsonString("") })
                val flow =
                    scheme.authorizationUrl?.let { authorization ->
                        "authorizationCode" to
                            obj(
                                "authorizationUrl" to JsonString(authorization),
                                "tokenUrl" to JsonString(scheme.tokenUrl),
                                scopes,
                            )
                    }
                        ?: ("clientCredentials" to
                            obj("tokenUrl" to JsonString(scheme.tokenUrl), scopes))
                obj("type" to JsonString("oauth2"), "flows" to obj(flow))
            }
            is SecurityScheme.OpenIdConnect ->
                obj(
                    "type" to JsonString("openIdConnect"),
                    "openIdConnectUrl" to JsonString(scheme.url),
                )
        }
}
