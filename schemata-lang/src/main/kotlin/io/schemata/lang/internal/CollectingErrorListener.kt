package io.schemata.lang.internal

import io.schemata.lang.Category
import io.schemata.lang.Diagnostic
import io.schemata.lang.Severity
import io.schemata.lang.Span
import org.antlr.v4.runtime.BaseErrorListener
import org.antlr.v4.runtime.RecognitionException
import org.antlr.v4.runtime.Recognizer
import org.antlr.v4.runtime.Token

/** Turns ANTLR syntax errors into [Diagnostic]s that name [file]. */
internal class CollectingErrorListener(private val file: String) : BaseErrorListener() {
    private val collected = mutableListOf<Diagnostic>()
    val diagnostics: List<Diagnostic>
        get() = collected

    override fun syntaxError(
        recognizer: Recognizer<*, *>?,
        offendingSymbol: Any?,
        line: Int,
        charPositionInLine: Int,
        msg: String,
        e: RecognitionException?,
    ) {
        val column = charPositionInLine + 1
        val token = offendingSymbol as? Token
        val width = if (token == null || token.type == Token.EOF) 0 else token.text.length
        collected +=
            Diagnostic(
                Severity.ERROR,
                Category.SYNTAX,
                msg,
                Span(file, line, column, line, column + width - 1),
            )
    }
}
