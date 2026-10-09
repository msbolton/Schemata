package io.schemata.lang

import io.schemata.lang.antlr.Schemata1Lexer
import io.schemata.lang.antlr.Schemata1Parser
import io.schemata.lang.antlr.SchemataLexer
import io.schemata.lang.antlr.SchemataParser
import io.schemata.lang.ast.SourceFile
import io.schemata.lang.format.CommentTable
import io.schemata.lang.format.Comments
import io.schemata.lang.internal.AstBuilder
import io.schemata.lang.internal.CollectingErrorListener
import io.schemata.lang.internal.V1AstBuilder
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.Recognizer
import org.antlr.v4.runtime.Token

/** [file] is null exactly when [diagnostics] contains an error. */
data class ParseResult(val file: SourceFile?, val diagnostics: List<Diagnostic>)

/** What the formatter needs: the file (null on a parse error) and every comment, attached. */
data class FormatParse(
    val file: SourceFile?,
    val comments: CommentTable,
    val diagnostics: List<Diagnostic>,
)

/**
 * The only public entry point into the parser. [path] is recorded in every span and never read from
 * disk — loading files is the caller's job. Callers never see ANTLR types. A UTF-8 byte-order mark
 * at the start of the source is skipped, so columns on the first line count from the character
 * after it.
 */
object Parser {
    private const val BYTE_ORDER_MARK = "﻿"
    private val LEGACY_OPENERS = setOf("namespace", "record")

    /**
     * A 1.x file (one whose first word after its doc and leading annotations is `namespace`, or
     * `record` for a file that left its namespace out) is one SCH0008 at that word and nothing
     * else: the parser never runs, so no cascade of syntax errors buries the one thing to do.
     */
    fun parse(input: String, path: String): ParseResult {
        val source = stripBom(input)
        legacy(source, path)?.let {
            return ParseResult(null, listOf(it))
        }
        val listener = CollectingErrorListener(path, CollectingErrorListener.HELP)
        val (parser, _) = lexAndParse(source, listener)
        val tree = parser.file()
        if (listener.diagnostics.hasErrors) return ParseResult(null, listener.diagnostics)
        val builderDiagnostics = mutableListOf<Diagnostic>()
        val file = AstBuilder(path, builderDiagnostics).build(tree)
        val diagnostics = listener.diagnostics + builderDiagnostics
        return ParseResult(if (diagnostics.hasErrors) null else file, diagnostics)
    }

    /** [parse] with every comment attached, for the formatter. */
    fun parseForFormat(input: String, path: String): FormatParse {
        val source = stripBom(input)
        legacy(source, path)?.let {
            return FormatParse(null, CommentTable.EMPTY, listOf(it))
        }
        val listener = CollectingErrorListener(path, CollectingErrorListener.HELP)
        val (parser, tokens) = lexAndParse(source, listener)
        val tree = parser.file()
        if (listener.diagnostics.hasErrors)
            return FormatParse(null, CommentTable.EMPTY, listener.diagnostics)
        val builderDiagnostics = mutableListOf<Diagnostic>()
        val file = AstBuilder(path, builderDiagnostics).build(tree)
        val diagnostics = listener.diagnostics + builderDiagnostics
        if (diagnostics.hasErrors) return FormatParse(null, CommentTable.EMPTY, diagnostics)
        return FormatParse(
            file,
            Comments.attach(file, Comments.collect(tokens), source),
            diagnostics,
        )
    }

    /** The 1.x surface with comments attached, read only by `schemata upgrade`. */
    fun parse1ForUpgrade(input: String, path: String): FormatParse {
        val source = stripBom(input)
        val listener = CollectingErrorListener(path, CollectingErrorListener.V1_HELP)
        val lexer = Schemata1Lexer(CharStreams.fromString(source))
        val tokens = CommonTokenStream(lexer)
        val parser = Schemata1Parser(tokens)
        installErrorListener(listener, lexer, parser)
        val tree = parser.file()
        if (listener.diagnostics.hasErrors)
            return FormatParse(null, CommentTable.EMPTY, listener.diagnostics)
        val builderDiagnostics = mutableListOf<Diagnostic>()
        val file = V1AstBuilder(path, builderDiagnostics).build(tree)
        val diagnostics = listener.diagnostics + builderDiagnostics
        if (diagnostics.hasErrors) return FormatParse(null, CommentTable.EMPTY, diagnostics)
        return FormatParse(
            file,
            Comments.attach(file, Comments.collectV1(tokens), source),
            diagnostics,
        )
    }

    /**
     * SCH0008 when [source] starts as a 1.x file does: past its doc comments and leading attributes
     * (`@name`, or `@name(…)` through its closing parenthesis), the first token is a word only 1.x
     * opens a file with (`namespace`, `record`), which the 2.0 lexer reads as a plain identifier.
     * Null otherwise; a 2.0 file starts with `schema`, a keyword, so a model or field with one of
     * those names is never mistaken.
     */
    private fun legacy(source: String, path: String): Diagnostic? {
        val lexer = SchemataLexer(CharStreams.fromString(source))
        lexer.removeErrorListeners()
        val tokens =
            generateSequence { lexer.nextToken() }
                .takeWhile { it.type != Token.EOF }
                .filter { it.channel == Token.DEFAULT_CHANNEL }
                .iterator()
        var token = if (tokens.hasNext()) tokens.next() else return null
        while (true) {
            when {
                token.type == SchemataLexer.DOC_COMMENT -> {}
                token.text == "@" || token.text == "@@" -> {
                    if (!tokens.hasNext()) return null
                    tokens.next()
                    if (!tokens.hasNext()) return null
                    token = tokens.next()
                    if (token.text != "(") continue
                    var depth = 1
                    while (depth > 0) {
                        if (!tokens.hasNext()) return null
                        val t = tokens.next()
                        if (t.text == "(") depth++ else if (t.text == ")") depth--
                    }
                }
                token.type == SchemataLexer.IDENT && token.text in LEGACY_OPENERS ->
                    return Diagnostic(
                        LangCodes.LEGACY_SYNTAX,
                        "this is a 1.x schema",
                        token.span(path),
                        help = "run schemata upgrade on this file",
                    )
                else -> return null
            }
            if (!tokens.hasNext()) return null
            token = tokens.next()
        }
    }

    private fun Token.span(path: String): Span {
        val column = charPositionInLine + 1
        return Span(path, line, column, line, column + (stopIndex - startIndex))
    }

    private fun lexAndParse(
        source: String,
        listener: CollectingErrorListener,
    ): Pair<SchemataParser, CommonTokenStream> {
        val lexer = SchemataLexer(CharStreams.fromString(source))
        val tokens = CommonTokenStream(lexer)
        val parser = SchemataParser(tokens)
        installErrorListener(listener, lexer, parser)
        return parser to tokens
    }

    private fun stripBom(input: String): String = input.removePrefix(BYTE_ORDER_MARK)

    /** Every lexer and parser error goes to [listener] alone, never to the console. */
    private fun installErrorListener(
        listener: CollectingErrorListener,
        vararg recognizers: Recognizer<*, *>,
    ) {
        for (recognizer in recognizers) {
            recognizer.removeErrorListeners()
            recognizer.addErrorListener(listener)
        }
    }
}
