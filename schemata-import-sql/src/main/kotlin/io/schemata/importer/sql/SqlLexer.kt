package io.schemata.importer.sql

import java.util.Locale

/** A one-based line and column in a `.sql` file. */
data class SqlPos(val line: Int, val col: Int)

enum class SqlTokenKind {
    IDENT,
    QIDENT,
    STRING,
    NUMBER,
    SYMBOL,
    COMMENT,
    EOF,
}

/**
 * One token. IDENT text is lower-cased, as Postgres folds unquoted identifiers; QIDENT and STRING
 * text is decoded; NUMBER is the source text; COMMENT is the body without its delimiters. SYMBOL
 * covers `( ) , ; . = < > <= >= <> != ~ ~* [ ] :: + - * /` and, one character at a time, the
 * remaining Postgres operator characters (`: @ # % ^ & | ? !`), so any operator lexes.
 */
data class SqlToken(val kind: SqlTokenKind, val text: String, val pos: SqlPos)

class SqlSyntaxError(val pos: SqlPos, message: String) : Exception(message)

/**
 * Tokens of a Postgres DDL file, comments included: the reader takes `schemata:` notes from them. A
 * line whose first character is `\` is a psql meta command and lexes as a comment. The data after
 * `COPY … FROM stdin;`, up to its `\.` line, is one comment, so a pg_dump with data lexes.
 */
object SqlLexer {
    private val TWO_CHAR = listOf("<=", ">=", "<>", "!=", "~*", "::")
    private const val ONE_CHAR = "(),;.=<>~[]+-*/:@#%^&|?!"
    private val DOLLAR_TAG = Regex("""\$([A-Za-z_][A-Za-z0-9_]*)?\$""")

    fun lex(text: String): List<SqlToken> {
        val out = mutableListOf<SqlToken>()
        var i = 0
        var line = 1
        var lineStart = 0
        var statementStart = 0
        fun pos() = SqlPos(line, i - lineStart + 1)
        fun fail(message: String, at: SqlPos = pos()): Nothing = throw SqlSyntaxError(at, message)
        /** Advances to [end], counting the newlines passed. */
        fun advanceTo(end: Int) {
            while (i < end) {
                if (text[i] == '\n') {
                    line++
                    lineStart = i + 1
                }
                i++
            }
        }
        fun lineEnd(from: Int) = text.indexOf('\n', from).let { if (it < 0) text.length else it }
        while (i < text.length) {
            val c = text[i]
            when {
                c.isWhitespace() -> advanceTo(i + 1)
                c == '-' && text.startsWith("--", i) -> {
                    val end = lineEnd(i)
                    out +=
                        SqlToken(
                            SqlTokenKind.COMMENT,
                            text.substring(i + 2, end).removeSuffix("\r"),
                            pos(),
                        )
                    i = end
                }
                c == '/' && text.startsWith("/*", i) -> {
                    val start = pos()
                    val bodyStart = i + 2
                    var j = bodyStart
                    var depth = 1
                    while (depth > 0) {
                        if (j >= text.length) fail("unterminated block comment", start)
                        when {
                            text.startsWith("/*", j) -> {
                                depth++
                                j += 2
                            }
                            text.startsWith("*/", j) -> {
                                depth--
                                j += 2
                            }
                            else -> j++
                        }
                    }
                    out += SqlToken(SqlTokenKind.COMMENT, text.substring(bodyStart, j - 2), start)
                    advanceTo(j)
                }
                c == '\\' && text.substring(lineStart, i).isBlank() -> {
                    val end = lineEnd(i)
                    out +=
                        SqlToken(
                            SqlTokenKind.COMMENT,
                            text.substring(i, end).removeSuffix("\r"),
                            pos(),
                        )
                    i = end
                }
                c == '\'' -> {
                    val start = pos()
                    out +=
                        SqlToken(
                            SqlTokenKind.STRING,
                            quoted(text, i, '\'', start, ::advanceTo),
                            start,
                        )
                }
                (c == 'E' || c == 'e') && text.startsWith("'", i + 1) -> {
                    val start = pos()
                    i++
                    out +=
                        SqlToken(SqlTokenKind.STRING, escaped(text, i, start, ::advanceTo), start)
                }
                c == '"' -> {
                    val start = pos()
                    out +=
                        SqlToken(
                            SqlTokenKind.QIDENT,
                            quoted(text, i, '"', start, ::advanceTo),
                            start,
                        )
                }
                c == '$' -> {
                    val start = pos()
                    val tag = DOLLAR_TAG.matchAt(text, i)?.value ?: fail("unexpected character '$'")
                    val end = text.indexOf(tag, i + tag.length)
                    if (end < 0) fail("unterminated dollar-quoted string", start)
                    out += SqlToken(SqlTokenKind.STRING, text.substring(i + tag.length, end), start)
                    advanceTo(end + tag.length)
                }
                c.isLetter() || c == '_' -> {
                    val start = pos()
                    val s = i
                    while (
                        i < text.length &&
                            (text[i].isLetterOrDigit() || text[i] == '_' || text[i] == '$')
                    ) i++
                    out +=
                        SqlToken(
                            SqlTokenKind.IDENT,
                            text.substring(s, i).lowercase(Locale.ROOT),
                            start,
                        )
                }
                c.isDigit() || (c == '.' && i + 1 < text.length && text[i + 1].isDigit()) -> {
                    val start = pos()
                    val s = i
                    while (i < text.length && text[i].isDigit()) i++
                    if (
                        i < text.length &&
                            text[i] == '.' &&
                            i + 1 < text.length &&
                            text[i + 1].isDigit()
                    ) {
                        i++
                        while (i < text.length && text[i].isDigit()) i++
                    }
                    if (i < text.length && (text[i] == 'e' || text[i] == 'E')) {
                        var j = i + 1
                        if (j < text.length && (text[j] == '+' || text[j] == '-')) j++
                        if (j < text.length && text[j].isDigit()) {
                            i = j
                            while (i < text.length && text[i].isDigit()) i++
                        }
                    }
                    out += SqlToken(SqlTokenKind.NUMBER, text.substring(s, i), start)
                }
                else -> {
                    val symbol =
                        TWO_CHAR.firstOrNull { text.startsWith(it, i) }
                            ?: c.toString().takeIf { c in ONE_CHAR }
                            ?: fail("unexpected character '$c'")
                    out += SqlToken(SqlTokenKind.SYMBOL, symbol, pos())
                    i += symbol.length
                    if (symbol == ";") {
                        if (isCopyFromStdin(out.subList(statementStart, out.size))) {
                            // The data starts on the next line and ends at a line holding only
                            // `\.`.
                            advanceTo(minOf(lineEnd(i) + 1, text.length))
                            val dataStart = pos()
                            val dataFrom = i
                            var end = text.length
                            var j = i
                            while (j < text.length) {
                                val e = lineEnd(j)
                                if (text.substring(j, e).removeSuffix("\r") == "\\.") {
                                    end = minOf(e + 1, text.length)
                                    break
                                }
                                j = e + 1
                            }
                            out +=
                                SqlToken(
                                    SqlTokenKind.COMMENT,
                                    text.substring(dataFrom, end),
                                    dataStart,
                                )
                            advanceTo(end)
                        }
                        statementStart = out.size
                    }
                }
            }
        }
        out += SqlToken(SqlTokenKind.EOF, "", pos())
        return out
    }

    private fun isCopyFromStdin(statement: List<SqlToken>): Boolean {
        val code = statement.filter { it.kind != SqlTokenKind.COMMENT }
        if (code.firstOrNull()?.let { it.kind == SqlTokenKind.IDENT && it.text == "copy" } != true)
            return false
        return code.zipWithNext().any { (a, b) ->
            a.kind == SqlTokenKind.IDENT &&
                a.text == "from" &&
                b.kind == SqlTokenKind.IDENT &&
                b.text == "stdin"
        }
    }

    /**
     * The body of a literal quoted with [quote] starting at [from], a doubled quote standing for
     * one; [advanceTo] moves the lexer past the closing quote.
     */
    private fun quoted(
        text: String,
        from: Int,
        quote: Char,
        start: SqlPos,
        advanceTo: (Int) -> Unit,
    ): String {
        val sb = StringBuilder()
        var j = from + 1
        while (true) {
            if (j >= text.length)
                throw SqlSyntaxError(
                    start,
                    if (quote == '"') "unterminated quoted identifier" else "unterminated string",
                )
            val d = text[j]
            if (d == quote) {
                if (j + 1 < text.length && text[j + 1] == quote) {
                    sb.append(quote)
                    j += 2
                    continue
                }
                break
            }
            sb.append(d)
            j++
        }
        advanceTo(j + 1)
        return sb.toString()
    }

    /**
     * The body of an `E'…'` string whose quote is at [from], with its backslash escapes decoded.
     */
    private fun escaped(text: String, from: Int, start: SqlPos, advanceTo: (Int) -> Unit): String {
        val sb = StringBuilder()
        var j = from + 1
        fun fail(): Nothing = throw SqlSyntaxError(start, "unterminated string")
        while (true) {
            if (j >= text.length) fail()
            val d = text[j]
            when {
                d == '\'' && j + 1 < text.length && text[j + 1] == '\'' -> {
                    sb.append('\'')
                    j += 2
                }
                d == '\'' -> break
                d == '\\' -> {
                    if (j + 1 >= text.length) fail()
                    val e = text[j + 1]
                    j += 2
                    when (e) {
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'x' -> {
                            val hex = run(text, j, 2) { it.isHexDigit() }
                            if (hex.isEmpty()) sb.append('x') else sb.append(hex.toInt(16).toChar())
                            j += hex.length
                        }
                        'u',
                        'U' -> {
                            val n = if (e == 'u') 4 else 8
                            val hex = run(text, j, n) { it.isHexDigit() }
                            val cp = if (hex.length == n) hex.toLongOrNull(16) else null
                            if (cp == null || cp > Character.MAX_CODE_POINT) {
                                throw SqlSyntaxError(start, "bad \\$e escape")
                            }
                            sb.appendCodePoint(cp.toInt())
                            j += n
                        }
                        in '0'..'7' -> {
                            val oct = e + run(text, j, 2) { it in '0'..'7' }
                            sb.append(oct.toInt(8).toChar())
                            j += oct.length - 1
                        }
                        else -> sb.append(e)
                    }
                }
                else -> {
                    sb.append(d)
                    j++
                }
            }
        }
        advanceTo(j + 1)
        return sb.toString()
    }

    /** Up to [max] characters of [text] from [from] that satisfy [ok]. */
    private fun run(text: String, from: Int, max: Int, ok: (Char) -> Boolean): String {
        var end = from
        while (end < text.length && end - from < max && ok(text[end])) end++
        return text.substring(from, end)
    }

    private fun Char.isHexDigit() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
