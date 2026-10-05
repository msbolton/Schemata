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
        members +=
            "components" to
                obj("schemas" to JsonObject(doc.components.map { it.key to schema(it.schema) }))
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
        members += "responses" to responses(op.response)
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

    private fun responses(response: Response): JsonObject =
        when (response) {
            is Response.Content ->
                obj(
                    response.status.toString() to
                        obj(
                            "description" to JsonString(response.description),
                            "content" to content(response.mediaType, response.schema),
                        )
                )
            is Response.Empty ->
                obj("204" to obj("description" to JsonString(response.description)))
        }

    private fun content(mediaType: String, schema: JsonSchema): JsonObject =
        obj(mediaType to obj("schema" to schema(schema)))

    private fun schema(schema: JsonSchema): JsonObject = JsonSchemaRenderer.node(schema)
}
