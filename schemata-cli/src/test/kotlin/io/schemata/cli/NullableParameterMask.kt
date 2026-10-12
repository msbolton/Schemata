package io.schemata.cli

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * Reads an OpenAPI document with each nullable parameter schema in its plain form. 1.4.0 wrote a
 * nullable query parameter as `anyOf` with `{"type": "null"}` or as `type: [T, "null"]`; a query
 * string has no null, so 2.8 writes the plain schema and the parameter stays optional. Only the
 * schema directly under an operation's `parameters` is rewritten: a nullable schema in a request or
 * response body, or inside a component, is left as written, so a change there still shows.
 */
internal object NullableParameterMask {
    private val mapper = ObjectMapper()

    fun mask(document: String): String {
        val tree = mapper.readTree(document)
        tree.path("paths").forEach { item ->
            item.forEach { operation ->
                operation.path("parameters").forEach { parameter ->
                    val schema = parameter.get("schema")
                    if (schema is ObjectNode)
                        (parameter as ObjectNode).set<JsonNode>("schema", plain(schema))
                }
            }
        }
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(tree)
    }

    private fun plain(schema: ObjectNode): JsonNode {
        val anyOf = schema.get("anyOf")
        if (anyOf is ArrayNode && anyOf.size() == 2 && schema.size() == 1) {
            val rest = anyOf.filterNot { it.size() == 1 && it.path("type").asText() == "null" }
            if (rest.size == 1) return rest.single()
        }
        val type = schema.get("type")
        if (type is ArrayNode && type.size() == 2 && type.any { it.asText() == "null" }) {
            val copy = schema.deepCopy()
            copy.set<JsonNode>("type", type.first { it.asText() != "null" })
            return copy
        }
        return schema
    }
}
