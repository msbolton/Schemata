package io.schemata.evolution

import io.schemata.lang.Category
import io.schemata.lang.DiagnosticCode
import io.schemata.lang.Severity

/**
 * What one target's [Rulebook] decides a [Change] means for data already produced under the old
 * schema. [Note.message] and [Breaking.message] are the rulebook's own text, without the target
 * name; the facade that renders a [io.schemata.lang.Diagnostic] adds that prefix.
 */
sealed interface Verdict {
    data object Compatible : Verdict

    data class Note(val message: String, val help: String) : Verdict

    data class Breaking(val message: String, val help: String) : Verdict
}

/** One target's answer for every kind of [Change], given the context a bare [Change] lacks. */
interface Rulebook {
    val target: String

    fun classify(change: Change, ctx: ChangeContext): Verdict
}

/** The evolution checker's own diagnostic catalog, `SCH25xx`. */
object EvolutionCodes {
    val BREAKING =
        DiagnosticCode(
            "SCH2501",
            Severity.ERROR,
            Category.SEMANTIC,
            "a change breaks data produced under the old schema on a target",
        )
    val NOTE =
        DiagnosticCode(
            "SCH2502",
            Severity.WARNING,
            Category.LOSSY,
            "a change is compatible on a target with a caveat",
        )
    val CANNOT_DIFF =
        DiagnosticCode(
            "SCH2503",
            Severity.ERROR,
            Category.SEMANTIC,
            "the two schema sets cannot be compared",
        )

    val all: List<DiagnosticCode> = listOf(BREAKING, NOTE, CANNOT_DIFF)
}

/** Every rulebook this build knows how to classify changes for, in the order reports list them. */
object Rulebooks {
    val all: List<Rulebook> = listOf(ProtoRules, SqlRules, XsdRules, JsonSchemaRules)

    fun named(name: String): Rulebook? = all.firstOrNull { it.target == name }
}
