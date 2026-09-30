package io.schemata.target.jsonschema

import io.schemata.target.OutputFile
import io.schemata.target.json.JsonArray
import io.schemata.target.json.JsonBool
import io.schemata.target.json.JsonNumber
import io.schemata.target.json.JsonObject
import io.schemata.target.json.JsonPrinter
import io.schemata.target.json.JsonString
import io.schemata.target.json.JsonValue
import io.schemata.target.json.obj
import java.math.BigDecimal

/** Prints a [JsonSchemaModel]; no decisions are made here. */
object JsonSchemaRenderer {
    private const val DRAFT = "https://json-schema.org/draft/2020-12/schema"

    fun render(model: JsonSchemaModel): List<OutputFile> =
        model.documents.map { OutputFile(it.path, JsonPrinter.print(document(it))) }

    private fun document(doc: JsonSchemaDocument): JsonObject {
        val members = mutableListOf<Pair<String, JsonValue>>()
        members += "\$schema" to JsonString(DRAFT)
        members += "\$id" to JsonString(doc.id)
        members += "title" to JsonString(doc.title)
        members += "\$defs" to JsonObject(doc.defs.map { it.key to node(it.schema) })
        return JsonObject(members)
    }

    /** [schema] with its description, default, deprecation, and nullability applied. */
    fun node(schema: JsonSchema): JsonObject {
        val c = schema.common
        val body = body(schema)
        val members = mutableListOf<Pair<String, JsonValue>>()
        c.description?.let { members += "description" to JsonString(it) }
        if (c.nullable && schema is ScalarSchema) {
            members +=
                body.members.map { (k, v) ->
                    if (k == "type") k to JsonArray(listOf(v, JsonString("null"))) else k to v
                }
        } else if (c.nullable) {
            members += "anyOf" to JsonArray(listOf(body, obj("type" to JsonString("null"))))
        } else {
            members += body.members
        }
        c.default?.let { members += "default" to it }
        if (c.deprecated) members += "deprecated" to JsonBool(true)
        return JsonObject(members)
    }

    /** The keywords of [schema] alone, without the common members. */
    private fun body(schema: JsonSchema): JsonObject =
        when (schema) {
            is RefSchema -> obj("\$ref" to JsonString(schema.uri))
            is ScalarSchema -> scalar(schema)
            is EnumSchema -> enum(schema)
            is ObjectSchema -> objectSchema(schema)
            is ArraySchema ->
                JsonObject(
                    listOfNotNull(
                        "type" to JsonString("array"),
                        "items" to node(schema.items),
                        schema.minItems?.let { "minItems" to JsonNumber(it.toString()) },
                        schema.maxItems?.let { "maxItems" to JsonNumber(it.toString()) },
                    )
                )
            is MapSchema ->
                JsonObject(
                    listOfNotNull(
                        "type" to JsonString("object"),
                        schema.keys?.let { "propertyNames" to scalar(it) },
                        "additionalProperties" to node(schema.values),
                        schema.minProperties?.let { "minProperties" to JsonNumber(it.toString()) },
                        schema.maxProperties?.let { "maxProperties" to JsonNumber(it.toString()) },
                    )
                )
            is TaggedUnionSchema ->
                obj(
                    "type" to JsonString("object"),
                    "oneOf" to
                        JsonArray(
                            schema.members.map { m ->
                                obj(
                                    "type" to JsonString("object"),
                                    "properties" to obj(m.tag to node(m.schema)),
                                    "required" to JsonArray(listOf(JsonString(m.tag))),
                                    "additionalProperties" to JsonBool(false),
                                )
                            }
                        ),
                )
        }

    private fun scalar(s: ScalarSchema): JsonObject =
        JsonObject(
            listOfNotNull(
                "type" to JsonString(s.type),
                s.format?.let { "format" to JsonString(it) },
                s.pattern?.let { "pattern" to JsonString(it) },
                s.minimum?.let { "minimum" to number(it) },
                s.maximum?.let { "maximum" to number(it) },
                s.minLength?.let { "minLength" to JsonNumber(it.toString()) },
                s.maxLength?.let { "maxLength" to JsonNumber(it.toString()) },
                s.contentEncoding?.let { "contentEncoding" to JsonString(it) },
            )
        )

    private fun enum(e: EnumSchema): JsonObject =
        if (e.values.none { it.description != null })
            obj(
                "type" to JsonString("string"),
                "enum" to JsonArray(e.values.map { JsonString(it.value) }),
            )
        else
            obj(
                "type" to JsonString("string"),
                "oneOf" to
                    JsonArray(
                        e.values.map { v ->
                            JsonObject(
                                listOfNotNull(
                                    "const" to JsonString(v.value),
                                    v.description?.let { "description" to JsonString(it) },
                                )
                            )
                        }
                    ),
            )

    private fun objectSchema(o: ObjectSchema): JsonObject {
        val members = mutableListOf<Pair<String, JsonValue>>("type" to JsonString("object"))
        members += "properties" to JsonObject(o.properties.map { it.name to node(it.schema) })
        val required = o.properties.filter { it.required }.map { JsonString(it.name) }
        if (required.isNotEmpty()) members += "required" to JsonArray(required)
        if (o.closed) members += "additionalProperties" to JsonBool(false)
        return JsonObject(members)
    }

    private fun number(n: BigDecimal): JsonNumber = JsonNumber(n.toPlainString())
}
