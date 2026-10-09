package io.schemata.target

import io.schemata.lang.Diagnostic
import io.schemata.lang.DiagnosticCode
import io.schemata.lang.Span

/**
 * Who owns each name a target emits. A second claim on a [key] reports [code] at the later claimant
 * with the first's location. A name is unique within its scope (a kind of name plus the declaring
 * path, say), so the same [name] in two scopes never collides; [display] is the name the message
 * shows, [kind] the word for what the holder lowers to.
 */
class NameClaims(
    private val code: DiagnosticCode,
    private val help: String,
    private val sink: MutableList<Diagnostic>,
) {
    private val holders = mutableMapOf<Pair<String, String>, Pair<String, Span>>()

    fun claim(
        scope: String,
        name: String,
        holder: String,
        span: Span,
        display: String = name,
        kind: String,
    ) {
        val previous = holders.putIfAbsent(scope to name, holder to span) ?: return
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
