package io.schemata.lang.internal

import io.schemata.lang.Diagnostic
import io.schemata.lang.LangCodes
import io.schemata.lang.Span
import org.antlr.v4.runtime.BaseErrorListener
import org.antlr.v4.runtime.RecognitionException
import org.antlr.v4.runtime.Recognizer
import org.antlr.v4.runtime.Token

/**
 * Turns ANTLR syntax errors into [Diagnostic]s that name [file], each with [help], which says how
 * the surface being read writes its common forms.
 */
internal class CollectingErrorListener(private val file: String, private val help: String) :
    BaseErrorListener() {
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
        val width =
            when {
                token == null -> 1
                token.type == Token.EOF -> 1
                else -> token.stopIndex - token.startIndex + 1
            }
        collected +=
            Diagnostic(
                LangCodes.SYNTAX,
                msg,
                Span(file, line, column, line, column + width - 1),
                help = help,
            )
    }

    companion object {
        const val HELP =
            "the parser stopped at the caret; a field is written `name Type`, a declaration `model Name { … }`, `enum Name { a b }`, `union Name = A | B`, or `service Name { #1 op(A): B }`"

        /** For the 1.x surface, which `schemata upgrade` reads. */
        const val V1_HELP =
            "the parser stopped at the caret; a 1.x field is written `name: type`, a declaration `record Name { … }`, `enum Name { a, b }`, `union Name = A | B`, or `service Name { #1 op(A): B }`"
    }
}
