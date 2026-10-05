package io.schemata.testkit

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SchemaId
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SpecVersion
import com.networknt.schema.oas.OpenApi31

/**
 * Checks rendered `.openapi.json` documents with networknt's validator, offline: the OpenAPI 3.1
 * document schema is a vendored resource registered under its own `$id`, and the document under
 * test is registered under a fixed URN so its `#/components/schemas/...` references resolve against
 * itself.
 */
object OpenApi {
    /** The `$id` of the vendored OpenAPI 3.1 document schema. */
    const val DOCUMENT_SCHEMA_ID = "https://spec.openapis.org/oas/3.1/schema/2022-10-07"

    private const val DOCUMENT_ID = "urn:schemata:openapi-document"
    private const val REPORTED = 3
    private val mapper = ObjectMapper()

    private val documentSchemaText: String by lazy {
        val resource =
            OpenApi::class.java.getResource("/openapi/schema.json")
                ?: error("the vendored OpenAPI document schema is missing")
        resource.readText()
    }

    /**
     * @return null when [document] is valid under the OpenAPI 3.1 document schema, every schema
     *   object in it is valid draft 2020-12, every component schema compiles under the OpenAPI 3.1
     *   dialect, and every `$ref` in it resolves; otherwise the first failures, one per line.
     */
    fun validate(document: String): String? {
        val tree =
            try {
                mapper.readTree(document)
            } catch (e: JsonProcessingException) {
                return "not JSON: ${e.originalMessage}"
            }
        val documentSchema =
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012) { builder ->
                    builder.schemaLoaders { loaders ->
                        loaders.schemas(mapOf(DOCUMENT_SCHEMA_ID to documentSchemaText))
                    }
                }
                .getSchema(SchemaLocation.of(DOCUMENT_SCHEMA_ID))
        val problems = documentSchema.validate(tree)
        if (problems.isNotEmpty()) return problems.take(REPORTED).joinToString("\n")

        val metaSchema =
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(SchemaLocation.of(SchemaId.V202012))
        for ((pointer, schema) in schemaObjects(tree)) {
            val meta = metaSchema.validate(schema)
            if (meta.isNotEmpty()) return "$pointer: ${meta.first()}"
        }

        // The document itself is the schema resource here: with no `$schema` of its own it is
        // read under the OpenAPI 3.1 dialect, as OpenAPI 3.1 reads every schema object.
        val dialect =
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012) { builder ->
                builder
                    .metaSchema(OpenApi31.getInstance())
                    .defaultMetaSchemaIri(OpenApi31.getInstance().iri)
                    .schemaLoaders { loaders -> loaders.schemas(mapOf(DOCUMENT_ID to document)) }
            }
        try {
            tree["components"]?.get("schemas")?.fieldNames()?.forEach { key ->
                dialect
                    .getSchema(SchemaLocation.of("$DOCUMENT_ID#/components/schemas/$key"))
                    .initializeValidators()
            }
            walkRefs(tree) { ref ->
                if (!ref.startsWith("#/")) throw IllegalStateException("'$ref' is not local")
                dialect.getSchema(SchemaLocation.of(DOCUMENT_ID + ref)).initializeValidators()
            }
        } catch (e: RuntimeException) {
            return e.message ?: e.toString()
        }
        return null
    }

    /**
     * Every schema object of [tree] with a readable location: the component schemas, each
     * parameter's schema, and each media type's schema in a request body or a response.
     */
    private fun schemaObjects(tree: JsonNode): List<Pair<String, JsonNode>> {
        val out = mutableListOf<Pair<String, JsonNode>>()
        tree["components"]?.get("schemas")?.fields()?.forEach { (key, schema) ->
            out += "components.schemas.$key" to schema
        }
        tree["paths"]?.fields()?.forEach { (path, item) ->
            item.fields().forEach { (verb, operation) ->
                val at = "$verb $path"
                operation["parameters"]?.forEachIndexed { i, p ->
                    p["schema"]?.let { out += "$at parameter $i" to it }
                }
                operation["requestBody"]?.get("content")?.fields()?.forEach { (type, media) ->
                    media["schema"]?.let { out += "$at request $type" to it }
                }
                operation["responses"]?.fields()?.forEach { (status, response) ->
                    response["content"]?.fields()?.forEach { (type, media) ->
                        media["schema"]?.let { out += "$at response $status $type" to it }
                    }
                }
            }
        }
        return out
    }

    /** Every `$ref` string in [tree], depth first. */
    private fun walkRefs(tree: JsonNode, action: (String) -> Unit) {
        if (tree.isObject) {
            tree["\$ref"]?.takeIf { it.isTextual }?.let { action(it.asText()) }
            tree.fields().forEach { (_, v) -> walkRefs(v, action) }
        } else if (tree.isArray) tree.forEach { walkRefs(it, action) }
    }
}
