package io.schemata.lang.internal

/**
 * A part of a string body the language does not accept: where it starts in the body (in code
 * points), how long it is, its text, and why it is refused.
 */
internal data class BadText(
    val offset: Int,
    val length: Int,
    val text: String,
    val reason: Reason,
) {
    sealed interface Reason

    /** A backslash followed by something that is not one of the six escapes. */
    data object UnknownEscape : Reason

    /** A well-formed `\u{…}` whose value is a surrogate or lies above U+10FFFF. */
    data object NotScalar : Reason

    /** A control character XML cannot carry, written raw or as `\u{…}`. */
    data class Control(val point: Int) : Reason
}

/** The value of a string body and every part of it that could not be accepted. */
internal data class Unescaped(val value: String, val bad: List<BadText>)

/**
 * String literals know six escapes: `\"`, `\\`, `\n`, `\t`, `\r`, and `\u{H…}` with one to six hex
 * digits naming a Unicode scalar value. Anything else after a backslash is an error; its text stays
 * in the value as written so the rest of the file still analyses. A `pattern` string is different:
 * a regex is full of backslashes that mean something to the target, so it is taken as written and
 * only `\"` is read, since a quote cannot otherwise appear in it.
 *
 * Neither kind of string, nor a doc comment, may hold a control character below U+0020 other than
 * tab, newline, and carriage return, nor U+FFFE or U+FFFF: XML cannot carry them, so no target
 * could write the text.
 */
internal object Strings {
    private const val MAX_HEX = 6

    /** [body] is the text between the quotes, with its escapes still in place. */
    fun unescape(body: String): Unescaped {
        val out = StringBuilder()
        val bad = mutableListOf<BadText>()
        var i = 0
        var offset = 0 // code points consumed, for spans
        while (i < body.length) {
            val c = body[i]
            if (c != '\\') {
                val point = body.codePointAt(i)
                if (isForbidden(point))
                    bad +=
                        BadText(offset, 1, String(Character.toChars(point)), BadText.Control(point))
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
                // The brace is looked for among the next MAX_HEX + 2 characters only, so a
                // malformed escape never reaches a `}` further along the string.
                val window = minOf(body.length, i + 3 + MAX_HEX + 2)
                val close = body.indexOf('}', i + 3).takeIf { it in 0 until window } ?: -1
                val hex = if (close < 0) "" else body.substring(i + 3, close)
                val point =
                    hex.takeIf { it.isNotEmpty() && it.length <= MAX_HEX && it.all(::isHex) }
                        ?.toInt(16)
                if (point != null && isScalar(point)) {
                    val length = close - i + 1
                    if (isForbidden(point))
                        bad +=
                            BadText(
                                offset,
                                length,
                                body.substring(i, close + 1),
                                BadText.Control(point),
                            )
                    out.appendCodePoint(point)
                    i += length
                    offset += length
                    continue
                }
                var end = if (close < 0) minOf(body.length, i + 3 + MAX_HEX + 1) else close + 1
                if (end < body.length && body[end - 1].isHighSurrogate()) end += 1
                val text = body.substring(i, end)
                val reason = if (point != null) BadText.NotScalar else BadText.UnknownEscape
                bad += BadText(offset, text.codePointCount(0, text.length), text, reason)
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
            bad += BadText(offset, length, text, BadText.UnknownEscape)
            out.append(text)
            i = end
            offset += length
        }
        return Unescaped(out.toString(), bad)
    }

    /** The regex a `pattern` refinement names: everything as written, except `\"` for a quote. */
    fun unquotePattern(text: String): String =
        text.substring(1, text.length - 1).replace("\\\"", "\"")

    /**
     * Every raw control character in [body], read as written: a `pattern`'s escapes are the regex's
     * own, and a doc comment has none.
     */
    fun rawControls(body: String): List<BadText> {
        val bad = mutableListOf<BadText>()
        var offset = 0
        body.codePoints().forEach { point ->
            if (isForbidden(point))
                bad += BadText(offset, 1, String(Character.toChars(point)), BadText.Control(point))
            offset += 1
        }
        return bad
    }

    private fun isHex(c: Char) = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    private fun isScalar(point: Int) = Character.isValidCodePoint(point) && point !in 0xD800..0xDFFF

    private fun isForbidden(point: Int) =
        (point < 0x20 && point != 0x09 && point != 0x0A && point != 0x0D) ||
            point == 0xFFFE ||
            point == 0xFFFF
}
