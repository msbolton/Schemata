package io.schemata.lsp.workspace

import io.schemata.lang.Span

/** A zero-based editor position: [character] counts UTF-16 code units within the line. */
data class TextPosition(val line: Int, val character: Int)

/** A zero-based editor range; [end] is exclusive. */
data class TextRange(val start: TextPosition, val end: TextPosition)

/**
 * The one place where the compiler's spans (one-based lines, one-based columns counted in code
 * points, inclusive end) meet an editor's positions (zero-based, UTF-16 units, exclusive end).
 * Lines break at `\n`, as the lexer counts them; a `\r` before it belongs to the line it ends.
 */
class LineIndex(private val text: String) {
    private val starts: IntArray =
        buildList {
                add(0)
                text.forEachIndexed { i, c -> if (c == '\n') add(i + 1) }
            }
            .toIntArray()

    private fun line(zeroBased: Int): String {
        if (zeroBased !in starts.indices) return ""
        val end = if (zeroBased + 1 < starts.size) starts[zeroBased + 1] - 1 else text.length
        return text.substring(starts[zeroBased], end).removeSuffix("\r")
    }

    /** The compiler's line and column under an editor position; a column past the line clamps. */
    fun toCompiler(position: TextPosition): Pair<Int, Int> {
        val line = line(position.line)
        var units = position.character.coerceIn(0, line.length)
        // A position between the two halves of a surrogate pair belongs to the character before it.
        if (
            units in 1 until line.length &&
                line[units - 1].isHighSurrogate() &&
                line[units].isLowSurrogate()
        ) {
            units--
        }
        return (position.line + 1) to (line.codePointCount(0, units) + 1)
    }

    /** The editor position of the compiler's [line] and [column]. */
    fun toEditor(line: Int, column: Int): TextPosition {
        val content = line(line - 1)
        val points = (column - 1).coerceIn(0, content.codePointCount(0, content.length))
        return TextPosition(line - 1, content.offsetByCodePoints(0, points))
    }

    fun range(span: Span): TextRange =
        TextRange(
            toEditor(span.startLine, span.startColumn),
            toEditor(span.endLine, span.endColumn + 1),
        )

    /** The source text a span covers. */
    fun slice(span: Span): String {
        val range = range(span)
        return text.substring(offset(range.start), offset(range.end))
    }

    /** The position just past the last character, for an edit that replaces the whole text. */
    fun end(): TextPosition {
        val last = starts.size - 1
        return TextPosition(last, text.length - starts[last])
    }

    private fun offset(position: TextPosition): Int =
        (starts.getOrElse(position.line) { text.length } + position.character).coerceAtMost(
            text.length
        )
}
