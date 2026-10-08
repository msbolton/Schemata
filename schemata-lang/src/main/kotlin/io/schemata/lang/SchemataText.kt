package io.schemata.lang

/**
 * A value printed as Schemata source, the one way every target note, diagnostic, and diff shows a
 * string: what it prints reads back as the same value.
 */
object SchemataText {
    /**
     * [value] as a string literal: in quotes, with the six escapes, and `\u{…}` for any other
     * character below U+0020.
     */
    fun string(value: String): String = buildString {
        append('"')
        value.codePoints().forEach { point ->
            when (point) {
                '"'.code -> append("\\\"")
                '\\'.code -> append("\\\\")
                '\n'.code -> append("\\n")
                '\t'.code -> append("\\t")
                '\r'.code -> append("\\r")
                in 0 until 0x20 ->
                    append("\\u{").append(Integer.toHexString(point).uppercase()).append('}')
                else -> appendCodePoint(point)
            }
        }
        append('"')
    }

    /**
     * [regex] as the literal of a `pattern` refinement, which is taken as written apart from `\"`:
     * only its quotes are escaped. A pattern read from source never holds an odd run of backslashes
     * before a quote, so the result always reads back as [regex].
     */
    fun pattern(regex: String): String = "\"" + regex.replace("\"", "\\\"") + "\""

    /** A reserved ordinal range as source: `#n` for one ordinal, `#a..#b` for a range. */
    fun ordinalRange(from: Int, to: Int): String = if (from == to) "#$from" else "#$from..#$to"
}
