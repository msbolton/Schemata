package io.schemata.importer.proto

/** A one-based line and column in a `.proto` file. */
data class Pos(val line: Int, val col: Int)

enum class TokenKind {
    IDENT,
    INT,
    FLOAT,
    STRING,
    SYMBOL,
    COMMENT,
    EOF,
}

/**
 * One token. [text] is the identifier, the number's source text, the string's decoded value, the
 * symbol, or the comment's body without delimiters. [endLine] is the line the token ends on, which
 * differs from [pos] only for block comments and strings joined across lines; [block] marks a block
 * comment.
 */
data class Token(
    val kind: TokenKind,
    val text: String,
    val pos: Pos,
    val endLine: Int,
    val block: Boolean = false,
)

class ProtoSyntaxError(val pos: Pos, message: String) : Exception(message)

/**
 * Tokens of a `.proto` file, comments included: the reader attaches comments as docs and reads the
 * `schemata:` notes the Protobuf target writes. Adjacent string literals join, as in proto.
 */
object ProtoLexer {
    private const val SYMBOLS = "{}[]()<>=;,.:/-+"

    fun lex(text: String): List<Token> {
        val out = mutableListOf<Token>()
        var i = 0
        var line = 1
        var lineStart = 0
        fun pos() = Pos(line, i - lineStart + 1)
        fun fail(message: String, at: Pos = pos()): Nothing = throw ProtoSyntaxError(at, message)
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\n' -> {
                    i++
                    line++
                    lineStart = i
                }
                c.isWhitespace() -> i++
                c == '/' && text.startsWith("//", i) -> {
                    val start = pos()
                    val end = text.indexOf('\n', i).let { if (it < 0) text.length else it }
                    out +=
                        Token(
                            TokenKind.COMMENT,
                            text.substring(i + 2, end).removeSuffix("\r"),
                            start,
                            line,
                        )
                    i = end
                }
                c == '/' && text.startsWith("/*", i) -> {
                    val start = pos()
                    val end = text.indexOf("*/", i + 2)
                    if (end < 0) fail("unterminated block comment", start)
                    val body = text.substring(i + 2, end)
                    body.forEachIndexed { k, ch ->
                        if (ch == '\n') {
                            line++
                            lineStart = i + 2 + k + 1
                        }
                    }
                    out += Token(TokenKind.COMMENT, body, start, line, block = true)
                    i = end + 2
                }
                c == '"' || c == '\'' -> {
                    val start = pos()
                    val sb = StringBuilder()
                    // Adjacent literals, separated only by whitespace, join into one token as
                    // protoc
                    // reads them.
                    while (true) {
                        val quote = text[i]
                        i++
                        while (true) {
                            if (i >= text.length || text[i] == '\n')
                                fail("unterminated string", start)
                            val d = text[i]
                            if (d == quote) {
                                i++
                                break
                            }
                            if (d != '\\') {
                                sb.append(d)
                                i++
                                continue
                            }
                            i++
                            if (i >= text.length) fail("unterminated string", start)
                            val e = text[i]
                            when (e) {
                                'n' -> sb.append('\n')
                                'r' -> sb.append('\r')
                                't' -> sb.append('\t')
                                'a' -> sb.append('\u0007')
                                'b' -> sb.append('\b')
                                'f' -> sb.append('\u000C')
                                'v' -> sb.append('\u000B')
                                '\\',
                                '\'',
                                '"',
                                '?' -> sb.append(e)
                                'x',
                                'X' -> {
                                    val hex = hexRun(text, i + 1, 2)
                                    if (hex.isEmpty()) fail("bad \\x escape")
                                    sb.append(hex.toInt(16).toChar())
                                    i += hex.length
                                }
                                'u' -> {
                                    val hex = hexRun(text, i + 1, 4)
                                    if (hex.length != 4) fail("bad \\u escape")
                                    sb.append(hex.toInt(16).toChar())
                                    i += 4
                                }
                                'U' -> {
                                    val hex = hexRun(text, i + 1, 8)
                                    val cp = if (hex.length == 8) hex.toLongOrNull(16) else null
                                    if (cp == null || cp > Character.MAX_CODE_POINT)
                                        fail("bad \\U escape")
                                    sb.appendCodePoint(cp.toInt())
                                    i += 8
                                }
                                in '0'..'7' -> {
                                    val oct = text.substring(i).takeWhile { it in '0'..'7' }.take(3)
                                    sb.append(oct.toInt(8).toChar())
                                    i += oct.length - 1
                                }
                                else -> fail("unknown escape \\$e")
                            }
                            i++
                        }
                        var j = i
                        var lines = 0
                        var jLineStart = lineStart
                        while (j < text.length && text[j].isWhitespace()) {
                            if (text[j] == '\n') {
                                lines++
                                jLineStart = j + 1
                            }
                            j++
                        }
                        if (j >= text.length || (text[j] != '"' && text[j] != '\'')) break
                        i = j
                        line += lines
                        lineStart = jLineStart
                    }
                    out += Token(TokenKind.STRING, sb.toString(), start, line)
                }
                c.isLetter() || c == '_' -> {
                    val start = pos()
                    val s = i
                    while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '_')) i++
                    out += Token(TokenKind.IDENT, text.substring(s, i), start, line)
                }
                c.isDigit() || (c == '.' && i + 1 < text.length && text[i + 1].isDigit()) -> {
                    val start = pos()
                    val s = i
                    var float = false
                    if (
                        c == '0' &&
                            i + 1 < text.length &&
                            (text[i + 1] == 'x' || text[i + 1] == 'X')
                    ) {
                        i += 2
                        while (i < text.length && text[i].isHexDigit()) i++
                    } else {
                        while (i < text.length && text[i].isDigit()) i++
                        if (i < text.length && text[i] == '.') {
                            float = true
                            i++
                            while (i < text.length && text[i].isDigit()) i++
                        }
                        if (i < text.length && (text[i] == 'e' || text[i] == 'E')) {
                            float = true
                            i++
                            if (i < text.length && (text[i] == '+' || text[i] == '-')) i++
                            while (i < text.length && text[i].isDigit()) i++
                        }
                    }
                    out +=
                        Token(
                            if (float) TokenKind.FLOAT else TokenKind.INT,
                            text.substring(s, i),
                            start,
                            line,
                        )
                }
                c in SYMBOLS -> {
                    out += Token(TokenKind.SYMBOL, c.toString(), pos(), line)
                    i++
                }
                else -> fail("unexpected character '$c'")
            }
        }
        out += Token(TokenKind.EOF, "", pos(), line)
        return out
    }

    /** Up to [max] hex digits of [text] starting at [from]. */
    private fun hexRun(text: String, from: Int, max: Int): String {
        var end = from
        while (end < text.length && end - from < max && text[end].isHexDigit()) end++
        return text.substring(from, end)
    }

    private fun Char.isHexDigit() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
