package io.schemata.target.json

/** The JSON a target or report can emit: objects keep the member order they were built in. */
sealed interface JsonValue

data class JsonObject(val members: List<Pair<String, JsonValue>>) : JsonValue

data class JsonArray(val items: List<JsonValue>) : JsonValue

data class JsonString(val value: String) : JsonValue

/** A number as its JSON literal, so `19.5000` and `9223372036854775807` print exactly. */
data class JsonNumber(val literal: String) : JsonValue

data class JsonBool(val value: Boolean) : JsonValue

data object JsonNull : JsonValue

fun obj(vararg members: Pair<String, JsonValue>): JsonObject = JsonObject(members.toList())

/** Pretty-prints with two-space indentation; the result ends with one newline. */
object JsonPrinter {
    fun print(value: JsonValue): String =
        StringBuilder().also { write(it, value, 0) }.append('\n').toString()

    fun quote(s: String): String {
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

    private fun write(sb: StringBuilder, value: JsonValue, depth: Int) {
        when (value) {
            is JsonString -> sb.append(quote(value.value))
            is JsonNumber -> sb.append(value.literal)
            is JsonBool -> sb.append(value.value)
            JsonNull -> sb.append("null")
            is JsonArray -> block(sb, '[', ']', value.items, depth) { write(sb, it, depth + 1) }
            is JsonObject ->
                block(sb, '{', '}', value.members, depth) { (k, v) ->
                    sb.append(quote(k)).append(": ")
                    write(sb, v, depth + 1)
                }
        }
    }

    private fun <T> block(
        sb: StringBuilder,
        open: Char,
        close: Char,
        items: List<T>,
        depth: Int,
        item: (T) -> Unit,
    ) {
        if (items.isEmpty()) {
            sb.append(open).append(close)
            return
        }
        sb.append(open)
        items.forEachIndexed { i, x ->
            sb.append(if (i == 0) "\n" else ",\n").append("  ".repeat(depth + 1))
            item(x)
        }
        sb.append('\n').append("  ".repeat(depth)).append(close)
    }
}
