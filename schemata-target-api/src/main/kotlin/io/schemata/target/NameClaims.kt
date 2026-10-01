package io.schemata.target

import io.schemata.lang.Diagnostic
import io.schemata.lang.DiagnosticCode
import io.schemata.lang.Span

/**
 * Who owns each name a target emits. A second claim on a [key] reports [code] at the later claimant
 * with the first's location; [key] scopes uniqueness (a declaring path plus the name), [display] is
 * the name the message shows, [kind] the word for what the holder lowers to.
 */
class NameClaims(
    private val code: DiagnosticCode,
    private val help: String,
    private val sink: MutableList<Diagnostic>,
) {
    private val holders = mutableMapOf<String, Pair<String, Span>>()

    fun claim(key: String, holder: String, span: Span, display: String = key, kind: String) {
        val previous = holders.putIfAbsent(key, holder to span) ?: return
        sink +=
            Diagnostic(
                code,
                "$holder lowers to $kind '$display', already used by ${previous.first} " +
                    "(${previous.second.file}:${previous.second.startLine})",
                span,
                help = help,
            )
    }
}
