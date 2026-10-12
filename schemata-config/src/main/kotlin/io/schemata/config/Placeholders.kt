package io.schemata.config

import io.schemata.target.Names

/**
 * The `{name}` placeholders a target default may hold. `{schema}` is the schema name as written,
 * `{Schema}` the same with each dotted segment UpperCamel, `{schema_}` with dots turned into
 * underscores; `{package}` and `{Package}` are the Protobuf package of the schema's file in the
 * same two spellings. `{{` and `}}` stand for literal braces.
 */
object Placeholders {
    private val SCHEMA_NAMES = listOf("schema", "Schema", "schema_")
    private val PACKAGE_NAMES = listOf("package", "Package")

    /**
     * The placeholder names a string may hold: `schema`, `Schema`, `schema_`, and with
     * [allowPackage] `package`, `Package`. Returns the first offending placeholder name, or null.
     */
    fun check(template: String, allowPackage: Boolean): String? {
        val allowed = if (allowPackage) SCHEMA_NAMES + PACKAGE_NAMES else SCHEMA_NAMES
        val names = mutableListOf<String>()
        render(template) { name ->
            names += name
            ""
        }
        return names.firstOrNull { it !in allowed }
    }

    /**
     * Expands [template]; [protoPackage] fills `{package}`/`{Package}` and must be given when they
     * occur.
     */
    fun expand(template: String, schema: String, protoPackage: String? = null): String =
        render(template) { name ->
            when (name) {
                "schema" -> schema
                "Schema" -> upperCamelDotted(schema)
                "schema_" -> schema.replace('.', '_')
                "package" -> requirePackage(protoPackage, template)
                "Package" -> upperCamelDotted(requirePackage(protoPackage, template))
                else ->
                    throw IllegalArgumentException("unknown placeholder '{$name}' in '$template'")
            }
        }

    /** `shop.orders` → `Shop.Orders`, via [Names.upperCamel] per segment. */
    fun upperCamelDotted(name: String): String =
        name.split('.').joinToString(".") { Names.upperCamel(it) }

    private fun requirePackage(protoPackage: String?, template: String): String =
        requireNotNull(protoPackage) { "'$template' needs a Protobuf package" }

    /**
     * Copies [template] with every placeholder replaced by [fill] of its name. A `{` without a
     * closing `}` takes the rest of the text as the name, so [check] reports it rather than letting
     * it through as literal text; a lone `}` stays as written.
     */
    private fun render(template: String, fill: (String) -> String): String {
        val out = StringBuilder()
        var i = 0
        while (i < template.length) {
            when {
                template.startsWith("{{", i) -> {
                    out.append('{')
                    i += 2
                }
                template.startsWith("}}", i) -> {
                    out.append('}')
                    i += 2
                }
                template[i] == '{' -> {
                    val close =
                        template.indexOf('}', i + 1).let { if (it < 0) template.length else it }
                    out.append(fill(template.substring(i + 1, close)))
                    i = close + 1
                }
                else -> {
                    out.append(template[i])
                    i += 1
                }
            }
        }
        return out.toString()
    }
}
