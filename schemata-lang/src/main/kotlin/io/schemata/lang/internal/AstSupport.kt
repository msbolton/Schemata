package io.schemata.lang.internal

import io.schemata.lang.Diagnostic
import io.schemata.lang.LangCodes
import io.schemata.lang.SchemataText
import io.schemata.lang.Span
import io.schemata.lang.ast.BindingDecl
import io.schemata.lang.ast.Literal
import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.Token
import org.antlr.v4.runtime.tree.TerminalNode

/**
 * What every parse-tree builder shares, whichever grammar produced the tree: spans, ordinals,
 * numbers and strings with their escape and control-character checks, doc comments, the HTTP
 * binding checks, and the reserved-word report. Each finding goes to [diagnostics]; every span
 * names [file].
 */
internal class AstSupport(
    private val file: String,
    private val diagnostics: MutableList<Diagnostic>,
) {
    fun span(ctx: ParserRuleContext): Span = span(ctx.start, ctx.stop ?: ctx.start)

    /** From the first column of [start] to the last of [stop]. */
    fun span(start: Token, stop: Token): Span {
        val endColumn =
            if (stop.type == Token.EOF) stop.charPositionInLine + 1
            else stop.charPositionInLine + stop.text.codePointLength()
        return Span(file, start.line, start.charPositionInLine + 1, stop.line, endColumn)
    }

    fun span(token: Token): Span =
        Span(
            file,
            token.line,
            token.charPositionInLine + 1,
            token.line,
            token.charPositionInLine + token.text.codePointLength(),
        )

    /** A keyword the grammar accepts only so it can be reported as reserved for a later version. */
    fun reservedFuture(keyword: Token) {
        diagnostics +=
            Diagnostic(
                LangCodes.RESERVED_KEYWORD,
                "'${keyword.text}' is reserved for a future version of Schemata",
                span(keyword),
                help = "rename the declaration; reserved words are listed in the language reference",
            )
    }

    /**
     * The text of a doc comment, which every target writes out; each control character XML cannot
     * carry is reported as SCH0005, as in a string.
     */
    fun doc(comments: List<TerminalNode>?): String? =
        comments?.joinToString("\n") { node ->
            val token = node.symbol
            Strings.rawControls(token.text).forEach {
                report(it, token.line, token.charPositionInLine + 1, "a doc comment")
            }
            node.text.removePrefix("///").removePrefix(" ").trimEnd()
        }

    fun ordinal(node: TerminalNode): Int =
        node.text.removePrefix("#").toIntOrNull()
            ?: run {
                diagnostics +=
                    Diagnostic(
                        LangCodes.NUMERIC_LITERAL_RANGE,
                        "ordinal '${node.text}' is out of range",
                        span(node.symbol),
                        help = "use an ordinal that fits in 32 bits",
                    )
                0
            }

    /** An integer token's value; one that does not fit in 64 bits is reported and read as 0. */
    fun int(node: TerminalNode, span: Span): Literal.IntLit {
        val value =
            node.text.toLongOrNull()
                ?: run {
                    diagnostics +=
                        Diagnostic(
                            LangCodes.NUMERIC_LITERAL_RANGE,
                            "number '${node.text}' is out of range",
                            span,
                            help = "use a value that fits in 64 bits",
                        )
                    0L
                }
        return Literal.IntLit(value, span)
    }

    /**
     * A string token's value; each escape the language does not define is reported as SCH0004, and
     * each control character XML cannot carry as SCH0005.
     */
    fun string(node: TerminalNode, span: Span): String {
        val text = node.text
        val result = Strings.unescape(text.substring(1, text.length - 1))
        result.bad.forEach { report(it, span) }
        return result.value
    }

    /**
     * A regular expression's string, taken as written so a pattern needs no doubled backslashes;
     * only control characters are reported. [span] is the literal's.
     */
    fun pattern(node: TerminalNode, span: Span): Literal.StringLit {
        Strings.rawControls(node.text.substring(1, node.text.length - 1)).forEach { bad ->
            report(bad, span)
        }
        return Literal.StringLit(Strings.unquotePattern(node.text), span)
    }

    /**
     * The verb is an identifier in the grammar so that `get` and `post` stay legal names elsewhere;
     * here it must be an HTTP method (SCH0006). The path must be `/`-separated segments of
     * unreserved URL characters or `{lower_snake}` parameters (SCH0007). [span] is the binding's.
     */
    fun binding(verbNode: TerminalNode, literal: TerminalNode, span: Span): BindingDecl {
        val verb = verbNode.text
        if (verb !in VERBS) {
            diagnostics +=
                Diagnostic(
                    LangCodes.UNKNOWN_VERB,
                    "'$verb' is not an HTTP verb",
                    span(verbNode.symbol),
                    help = "use one of get, post, put, patch, delete, head, options",
                )
        }
        val pathSpan = span(literal.symbol)
        val path = string(literal, pathSpan)
        val parameters = mutableListOf<String>()
        val problem = pathProblem(path, parameters)
        if (problem != null) {
            diagnostics +=
                Diagnostic(
                    LangCodes.MALFORMED_PATH,
                    "path ${SchemataText.string(path)} is malformed: $problem",
                    pathSpan,
                    help = "write the path as /segment/{param}; parameters are lower_snake",
                )
        }
        return BindingDecl(verb, span(verbNode.symbol), path, pathSpan, parameters, span)
    }

    /**
     * Null when [path] is well formed; otherwise what is wrong, with [parameters] filled as far as
     * it got.
     */
    private fun pathProblem(path: String, parameters: MutableList<String>): String? {
        if (!path.startsWith("/")) return "it must start with /"
        if (path.length > 1 && path.endsWith("/")) return "it must not end with /"
        val body = path.substring(1)
        if (body.isEmpty()) return null
        for (segment in body.split("/")) {
            if (segment.isEmpty()) return "it has an empty segment"
            if (segment.startsWith("{") && segment.endsWith("}")) {
                val name = segment.substring(1, segment.length - 1)
                if (!LOWER_SNAKE.matches(name)) return "parameter \"$name\" is not lower_snake"
                parameters += name
            } else if (!SEGMENT.matches(segment)) {
                return "segment \"$segment\" holds a character outside A-Z a-z 0-9 . _ ~ -"
            }
        }
        return null
    }

    /** [span] is a string literal's; its body starts one column in, after the quote. */
    private fun report(bad: BadText, span: Span) {
        // A string cannot span lines, so the bad text sits on the line the string starts on.
        report(bad, span.startLine, span.startColumn + 1, "a string")
    }

    /** [column] is where offset 0 of the text [bad] was found in sits; [what] names that text. */
    private fun report(bad: BadText, line: Int, column: Int, what: String) {
        val start = column + bad.offset
        val where = Span(file, line, start, line, start + bad.length - 1)
        diagnostics +=
            when (val reason = bad.reason) {
                BadText.UnknownEscape ->
                    Diagnostic(
                        LangCodes.BAD_ESCAPE,
                        "unknown escape '${bad.text}' in a string",
                        where,
                        help = ESCAPE_HELP,
                    )
                BadText.NotScalar ->
                    Diagnostic(
                        LangCodes.BAD_ESCAPE,
                        "'${bad.text}' is not a Unicode scalar value",
                        where,
                        help = ESCAPE_HELP,
                    )
                is BadText.Control ->
                    Diagnostic(
                        LangCodes.CONTROL_CHARACTER,
                        "control character U+%04X in %s".format(reason.point, what),
                        where,
                        help =
                            "write text; only tab, newline, and carriage return are allowed as control characters",
                    )
            }
    }

    private companion object {
        const val ESCAPE_HELP =
            "write \\\\ for a backslash; the escapes are \\\" \\\\ \\n \\t \\r \\u{…}"
        val VERBS = setOf("get", "post", "put", "patch", "delete", "head", "options")
        val LOWER_SNAKE = Regex("[a-z][a-z0-9]*(_[a-z0-9]+)*")
        val SEGMENT = Regex("[A-Za-z0-9._~-]+")
    }

    // ANTLR counts columns in Unicode code points; a token's own text is a normal UTF-16 Java
    // string, so an astral character inside it (an emoji, say) counts as one column here too,
    // rather than the two UTF-16 units `String.length` would give it.
    private fun String.codePointLength(): Int = codePointCount(0, length)
}
