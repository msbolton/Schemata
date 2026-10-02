package io.schemata.evolution

import io.schemata.core.ir.Schema
import io.schemata.lang.Diagnostic

/** One target's [Verdict] for a [Change]. */
data class TargetVerdict(val target: String, val verdict: Verdict)

/**
 * One [Change], judged by every rulebook the comparison ran. [deprecatedInOld] is whether the thing
 * the change removes, renames away from, or recasts was `@deprecated` on OLD's side.
 */
data class Judged(
    val change: Change,
    val verdicts: List<TargetVerdict>,
    val deprecatedInOld: Boolean,
)

/**
 * Every change between two schemas, judged by [rulebooks], and the diagnostics that judging
 * produced.
 */
data class Comparison(val judged: List<Judged>, val diagnostics: List<Diagnostic>)

/**
 * The facade over [Differ] and the rulebooks: diff, then judge every change by every target asked
 * for.
 */
object Evolution {
    fun compare(old: Schema, new: Schema, rulebooks: List<Rulebook>): Comparison {
        val ctx = ChangeContext(old, new)
        val diagnostics = mutableListOf<Diagnostic>()
        val judged =
            Differ.diff(old, new).map { change ->
                val verdicts =
                    rulebooks.map { rulebook ->
                        val verdict = rulebook.classify(change, ctx)
                        when (verdict) {
                            is Verdict.Compatible -> Unit
                            is Verdict.Note ->
                                diagnostics +=
                                    Diagnostic(
                                        EvolutionCodes.NOTE,
                                        "${rulebook.target}: ${verdict.message}",
                                        change.span,
                                        verdict.help,
                                    )
                            is Verdict.Breaking ->
                                diagnostics +=
                                    Diagnostic(
                                        EvolutionCodes.BREAKING,
                                        "${rulebook.target}: ${verdict.message}",
                                        change.span,
                                        verdict.help,
                                    )
                        }
                        TargetVerdict(rulebook.target, verdict)
                    }
                Judged(change, verdicts, ctx.deprecatedInOld(change))
            }
        return Comparison(judged, diagnostics)
    }
}
