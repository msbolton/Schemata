package io.schemata.cli.report

/**
 * The subset of JSON the reports need: objects with fixed field order, arrays, strings, numbers,
 * bools, null.
 *
 * This is the report's JSON-lines contract: [value] prints on one line, and [document] puts one
 * top-level field per line so a report diffs by field. The shared `JsonPrinter` is not used here
 * because it prints one pretty, indented document, which would change every report's bytes.
 */
object Json {
    fun string(s: String): String {
        val sb = StringBuilder("\"")
        s.forEach { c ->
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append(String.format("\\u%04x", c.code))
                else -> sb.append(c)
            }
        }
        return sb.append('"').toString()
    }

    fun value(v: Any?): String =
        when (v) {
            null -> "null"
            is String -> string(v)
            is Double -> finite(v).toString()
            is Float -> {
                finite(v.toDouble())
                v.toString()
            }
            is Number -> v.toString()
            is Boolean -> v.toString()
            is List<*> -> v.joinToString(",", "[", "]") { value(it) }
            is Obj -> v.fields.joinToString(",", "{", "}") { (k, x) -> "${string(k)}:${value(x)}" }
            else -> error("unsupported JSON value: ${v::class}")
        }

    /** JSON has no NaN or infinity, so a report holding one is a bug, not a value to print. */
    private fun finite(d: Double): Double {
        require(d.isFinite()) { "JSON cannot represent $d" }
        return d
    }

    /** An object whose fields print in insertion order. */
    class Obj(vararg fields: Pair<String, Any?>) {
        val fields: List<Pair<String, Any?>> = fields.toList()
    }

    /**
     * One value per line at the top level; nested values stay on one line so output stays diffable.
     */
    fun document(obj: Obj): String =
        obj.fields.joinToString(",\n", "{\n", "\n}\n") { (k, x) -> "  ${string(k)}: ${value(x)}" }
}
