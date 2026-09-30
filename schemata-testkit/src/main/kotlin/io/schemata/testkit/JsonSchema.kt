package io.schemata.testkit

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SchemaId
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SchemaValidatorsConfig
import com.networknt.schema.SpecVersion

/**
 * Checks rendered `.schema.json` files with networknt's validator: every document is registered
 * under its own `$id`, so absolute cross-document references resolve without touching disk or the
 * network.
 */
object JsonSchema {
    private val mapper = ObjectMapper()

    /**
     * @return null when every document is valid draft 2020-12 and every `$ref` in it resolves;
     *   otherwise `"<path>: <first problem>"`.
     */
    fun validate(files: Map<String, String>): String? {
        val byId = idsOf(files)
        val factory = factory(byId)
        val metaSchema = factory.getSchema(SchemaLocation.of(SchemaId.V202012))
        for ((path, text) in files.toSortedMap()) {
            val tree = mapper.readTree(text)
            val problems = metaSchema.validate(tree)
            if (problems.isNotEmpty()) return "$path: ${problems.first()}"
            val id = tree["\$id"]?.asText() ?: return "$path: no \$id"
            try {
                factory.getSchema(SchemaLocation.of(id)).initializeValidators()
                walkRefs(tree) { ref ->
                    factory.getSchema(SchemaLocation.of(resolve(id, ref))).initializeValidators()
                }
            } catch (e: RuntimeException) {
                return "$path: ${e.message}"
            }
        }
        return null
    }

    /**
     * @return null when [instance] is valid against `<id>#/$defs/<def>`; otherwise the first
     *   message.
     */
    fun check(files: Map<String, String>, id: String, def: String, instance: String): String? {
        val config = SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build()
        val schema = factory(idsOf(files)).getSchema(SchemaLocation.of("$id#/\$defs/$def"), config)
        val problems = schema.validate(mapper.readTree(instance))
        return problems.firstOrNull()?.toString()
    }

    private fun idsOf(files: Map<String, String>): Map<String, String> =
        files.values
            .associateBy { mapper.readTree(it)["\$id"]?.asText() ?: "" }
            .filterKeys { it.isNotEmpty() }

    private fun factory(byId: Map<String, String>): JsonSchemaFactory =
        JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012) { builder ->
            builder.schemaLoaders { loaders -> loaders.schemas(byId) }
        }

    /** Every `$ref` string in [tree], depth first. */
    private fun walkRefs(tree: JsonNode, action: (String) -> Unit) {
        if (tree.isObject) {
            tree["\$ref"]?.takeIf { it.isTextual }?.let { action(it.asText()) }
            tree.fields().forEach { (_, v) -> walkRefs(v, action) }
        } else if (tree.isArray) tree.forEach { walkRefs(it, action) }
    }

    /** `#/$defs/X` against [base]; an absolute ref is returned as is. */
    private fun resolve(base: String, ref: String): String =
        if (ref.startsWith("#")) base + ref else ref
}
