package io.schemata.cli.report

/**
 * The subset of JSON the reports need: objects with fixed field order, arrays, strings, ints,
 * bools, null.
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
            is Int -> v.toString()
            is Boolean -> v.toString()
            is List<*> -> v.joinToString(",", "[", "]") { value(it) }
            is Obj -> v.fields.joinToString(",", "{", "}") { (k, x) -> "${string(k)}:${value(x)}" }
            else -> error("unsupported JSON value: ${v::class}")
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
