package io.schemata.config

import io.schemata.core.annotations.AnnotationRegistry

/** The `schemata.toml` that `schemata init` writes (SCH-105). */
object StarterFile {
    private const val WIKI = "https://github.com/msbolton/Schemata/wiki/Project-Configuration"

    /**
     * Every run table with each key commented out at its default, so the project keeps following
     * the built-in defaults until a key is uncommented, then the target tables, commented, with the
     * keys a schema line takes. `[sources]` is written active when [sourcePaths] names directories.
     */
    fun text(
        registry: AnnotationRegistry,
        targetNames: List<String>,
        sourcePaths: List<String>,
    ): String {
        val out = StringBuilder()
        out.appendLine(
            "# schemata.toml, written by schemata init. Each key shows its default; uncomment one"
        )
        out.appendLine("# to change it. $WIKI")
        out.appendLine()
        for ((table, keys) in ConfigKeys.runTables(targetNames)) {
            val found = table == "sources" && sourcePaths.isNotEmpty()
            out.appendLine(if (table == "sources" && !found) "# [$table]" else "[$table]")
            for (key in keys) {
                if (found && key.name == "paths") {
                    out.appendLine(line(key.name, toml(sourcePaths), key.note))
                    continue
                }
                val value =
                    when {
                        table == "sources" && key.name == "paths" -> toml(listOf("schemas"))
                        key.default != null -> toml(key.default)
                        else -> empty(key.expect)
                    }
                out.appendLine("# " + line(key.name, value, key.note))
            }
            out.appendLine()
        }
        out.appendLine(
            "# Defaults for the schema line of every schema, by target (see the Attributes page)."
        )
        val tables =
            targetNames.mapNotNull { target ->
                val keys = ConfigKeys.targetKeys(registry, target)
                if (keys.isEmpty()) return@mapNotNull null
                buildString {
                    appendLine("# [$target]")
                    for ((key, expect) in keys) {
                        val shown = "# $key = ${empty(expect)}"
                        appendLine(
                            if (expect is Expect.OneOf) "$shown   # ${expect.wording}" else shown
                        )
                    }
                }
            }
        out.appendLine()
        out.append(tables.joinToString("\n"))
        return out.toString()
    }

    private fun toml(value: Any): String =
        when (value) {
            is String -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
            is List<*> -> value.joinToString(", ", "[", "]") { toml(it!!) }
            is Boolean -> value.toString()
            else -> value.toString()
        }

    private fun empty(expect: Expect): String =
        when (expect) {
            Expect.Flag -> "false"
            Expect.Integer -> "0"
            Expect.Texts -> "[]"
            Expect.Text -> "\"\""
            is Expect.OneOf -> if (expect.many) "[]" else "\"\""
        }

    private fun line(key: String, value: String, note: String?): String =
        "$key = $value" + (note?.let { "   # $it" } ?: "")
}
