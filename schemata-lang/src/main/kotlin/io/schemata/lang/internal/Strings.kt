package io.schemata.lang.internal

/**
 * An escape the language does not define: where it starts in the body, how long it is, its text.
 */
internal data class BadEscape(val offset: Int, val length: Int, val text: String)

/** The value of a string body and every escape in it that could not be read. */
internal data class Unescaped(val value: String, val bad: List<BadEscape>)

/**
 * String literals know six escapes: `\"`, `\\`, `\n`, `\t`, `\r`, and `\u{H…}` with one to six hex
 * digits naming a Unicode scalar value. Anything else after a backslash is an error; its text stays
 * in the value as written so the rest of the file still analyses. A `pattern` string is different:
 * a regex is full of backslashes that mean something to the target, so it is taken as written and
 * only `\"` is read, since a quote cannot otherwise appear in it.
 */
internal object Strings {
    private const val MAX_HEX = 6

    /** [body] is the text between the quotes, with its escapes still in place. */
    fun unescape(body: String): Unescaped {
        val out = StringBuilder()
        val bad = mutableListOf<BadEscape>()
        var i = 0
        var offset = 0 // code points consumed, for spans
        while (i < body.length) {
            val c = body[i]
            if (c != '\\') {
                val point = body.codePointAt(i)
                out.appendCodePoint(point)
                i += Character.charCount(point)
                offset += 1
                continue
            }
            val next = body.getOrNull(i + 1)
            val simple =
                when (next) {
                    '"' -> '"'
                    '\\' -> '\\'
                    'n' -> '\n'
                    't' -> '\t'
                    'r' -> '\r'
                    else -> null
                }
            if (simple != null) {
                out.append(simple)
                i += 2
                offset += 2
                continue
            }
            if (next == 'u' && body.getOrNull(i + 2) == '{') {
                val close = body.indexOf('}', i + 3)
                val hex = if (close < 0) "" else body.substring(i + 3, close)
                val point =
                    hex.takeIf { it.isNotEmpty() && it.length <= MAX_HEX && it.all(::isHex) }
                        ?.toInt(16)
                        ?.takeIf { Character.isValidCodePoint(it) && !isSurrogate(it) }
                if (close >= 0 && point != null) {
                    out.appendCodePoint(point)
                    val length = close - i + 1
                    i += length
                    offset += length
                    continue
                }
                val end = if (close < 0) minOf(body.length, i + 3 + MAX_HEX + 1) else close + 1
                val text = body.substring(i, end)
                bad += BadEscape(offset, text.codePointCount(0, text.length), text)
                out.append(text)
                i = end
                offset += text.codePointCount(0, text.length)
                continue
            }
            // `\q`, `\u` with no brace, or a lone backslash at the very end: two characters at
            // most.
            val end =
                minOf(
                    body.length,
                    i + 1 + (if (next == null) 0 else Character.charCount(body.codePointAt(i + 1))),
                )
            val text = body.substring(i, end)
            val length = text.codePointCount(0, text.length)
            bad += BadEscape(offset, length, text)
            out.append(text)
            i = end
            offset += length
        }
        return Unescaped(out.toString(), bad)
    }

    /** The regex a `pattern` refinement names: everything as written, except `\"` for a quote. */
    fun unquotePattern(text: String): String =
        text.substring(1, text.length - 1).replace("\\\"", "\"")

    private fun isHex(c: Char) = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    private fun isSurrogate(point: Int) = point in 0xD800..0xDFFF
}
