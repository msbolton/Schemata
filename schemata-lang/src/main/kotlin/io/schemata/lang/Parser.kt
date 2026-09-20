package io.schemata.lang

import io.schemata.lang.antlr.SchemataLexer
import io.schemata.lang.antlr.SchemataParser
import io.schemata.lang.ast.SourceFile
import io.schemata.lang.internal.AstBuilder
import io.schemata.lang.internal.CollectingErrorListener
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream

/** [file] is null exactly when [diagnostics] contains an error. */
data class ParseResult(val file: SourceFile?, val diagnostics: List<Diagnostic>)

/**
 * The only public entry point into the parser. [path] is recorded in every span and never read from
 * disk — loading files is the caller's job.
 */
object Parser {
    fun parse(source: String, path: String): ParseResult {
        val listener = CollectingErrorListener(path)
        val lexer =
            SchemataLexer(CharStreams.fromString(source)).apply {
                removeErrorListeners()
                addErrorListener(listener)
            }
        val parser =
            SchemataParser(CommonTokenStream(lexer)).apply {
                removeErrorListeners()
                addErrorListener(listener)
            }
        val tree = parser.file()
        if (listener.diagnostics.hasErrors) return ParseResult(null, listener.diagnostics)
        return ParseResult(AstBuilder(path).build(tree), listener.diagnostics)
    }
}
