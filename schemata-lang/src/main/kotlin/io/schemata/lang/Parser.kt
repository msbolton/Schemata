package io.schemata.lang

import io.schemata.lang.antlr.Schemata2Lexer
import io.schemata.lang.antlr.Schemata2Parser
import io.schemata.lang.antlr.SchemataLexer
import io.schemata.lang.antlr.SchemataParser
import io.schemata.lang.ast.SourceFile
import io.schemata.lang.format.CommentTable
import io.schemata.lang.format.Comments
import io.schemata.lang.internal.Ast2Builder
import io.schemata.lang.internal.AstBuilder
import io.schemata.lang.internal.CollectingErrorListener
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.Recognizer

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
    private const val BYTE_ORDER_MARK = "\uFEFF"

    fun parse(input: String, path: String): ParseResult {
        val source = stripBom(input)
        val listener = CollectingErrorListener(path)
        val (parser, _) = lexAndParse(source, listener)
        val tree = parser.file()
        if (listener.diagnostics.hasErrors) return ParseResult(null, listener.diagnostics)
        val builderDiagnostics = mutableListOf<Diagnostic>()
        val file = AstBuilder(path, builderDiagnostics).build(tree)
        val diagnostics = listener.diagnostics + builderDiagnostics
        return ParseResult(if (diagnostics.hasErrors) null else file, diagnostics)
    }

    /** Parses the 2.0 surface (`schema`, `model`, `name Type { options }`) into the same AST. */
    fun parse2(input: String, path: String): ParseResult {
        val source = stripBom(input)
        val listener = CollectingErrorListener(path)
        val lexer = Schemata2Lexer(CharStreams.fromString(source))
        val parser = Schemata2Parser(CommonTokenStream(lexer))
        installErrorListener(listener, lexer, parser)
        val tree = parser.file()
        if (listener.diagnostics.hasErrors) return ParseResult(null, listener.diagnostics)
        val builderDiagnostics = mutableListOf<Diagnostic>()
        val file = Ast2Builder(path, builderDiagnostics).build(tree)
        val diagnostics = listener.diagnostics + builderDiagnostics
        return ParseResult(if (diagnostics.hasErrors) null else file, diagnostics)
    }

    fun parseForFormat(input: String, path: String): FormatParse {
        val source = stripBom(input)
        val listener = CollectingErrorListener(path)
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
