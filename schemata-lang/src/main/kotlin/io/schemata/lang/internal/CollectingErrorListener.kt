package io.schemata.lang.internal

import io.schemata.lang.Category
import io.schemata.lang.Diagnostic
import io.schemata.lang.Severity
import io.schemata.lang.Span
import org.antlr.v4.runtime.BaseErrorListener
import org.antlr.v4.runtime.RecognitionException
import org.antlr.v4.runtime.Recognizer

/** Turns ANTLR syntax errors into [Diagnostic]s instead of printing them to stderr. */
internal class CollectingErrorListener : BaseErrorListener() {
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
        collected +=
            Diagnostic(Severity.ERROR, Category.SYNTAX, msg, Span(line, column, line, column))
    }
}
