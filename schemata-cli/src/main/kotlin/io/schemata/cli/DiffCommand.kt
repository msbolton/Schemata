package io.schemata.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.split
import com.github.ajalt.clikt.parameters.types.path
import io.schemata.cli.report.DiffRenderer
import io.schemata.cli.report.Report
import io.schemata.cli.report.Sources
import io.schemata.core.ir.Schema
import io.schemata.evolution.Comparison
import io.schemata.evolution.Evolution
import io.schemata.evolution.EvolutionCodes
import io.schemata.evolution.Rulebooks
import io.schemata.lang.Diagnostic
import io.schemata.lang.Severity
import java.nio.file.Path

class DiffCommand : CliktCommand(name = "diff") {
    override fun help(context: Context) =
        "Compare two versions of a schema set and judge every change per target."

    private val targetNames by
        option("--target", help = "Comma-separated targets to judge (default: all)").split(",")

    private val reporting by ReportingOptions()

    private val old by argument("OLD").path(mustExist = true)

    private val new by argument("NEW").path(mustExist = true)

    override fun run() {
        val rulebooks =
            targetNames
                ?.map { it.trim() }
                ?.distinct()
                ?.map { Rulebooks.named(it) ?: throw UsageError("unknown target '$it'") }
                ?: Rulebooks.all
        val oldSide = load(old)
        val newSide = load(new)
        val failures = cannotDiff(oldSide.analyzed, newSide.analyzed)
        val sources = Sources.of(newSide.sources + oldSide.sources)
        if (failures.isNotEmpty()) {
            val report = Report.of(failures, emptyList(), emptyList(), strict = reporting.strict)
            if (reporting.format == Format.JSON) {
                val empty = Comparison(emptyList(), emptyList())
                echo(DiffRenderer.json(empty, report, rulebooks, failures), trailingNewline = false)
                throw ProgramResult(report.exitCode)
            }
            emit(this, report, sources, reporting, out = "")
            return
        }
        val oldSchema = oldSide.analyzed.schema!!
        val newSchema = newSide.analyzed.schema!!
        val comparison = Evolution.compare(oldSchema, newSchema, rulebooks)
        val report =
            Report.of(comparison.diagnostics, emptyList(), emptyList(), strict = reporting.strict)
        when (reporting.format) {
            Format.JSON ->
                echo(DiffRenderer.json(comparison, report, rulebooks), trailingNewline = false)
            Format.HUMAN -> {
                val implicitOrdinals =
                    oldSide.analyzed.implicitOrdinals + newSide.analyzed.implicitOrdinals
                echo(DiffRenderer.changes(comparison, rulebooks, implicitOrdinals), err = true)
                emit(this, report, sources, reporting, out = "")
            }
        }
        if (reporting.format == Format.JSON && report.exitCode != 0)
            throw ProgramResult(report.exitCode)
    }

    private fun load(path: Path): Loaded {
        val sources = loadSources(listOf(path))
        return Loaded(sources, analyzeSide(sources))
    }
}

private data class Loaded(val sources: List<SourceInput>, val analyzed: Analyzed)

/**
 * One side as `diff` analyses it: never with strict ordinals, since `--strict` on `diff` only
 * promotes notes to breaks and an implicit ordinal is reported in the trailer instead.
 */
internal fun analyzeSide(sources: List<SourceInput>): Analyzed =
    Pipeline.analyze(sources, strict = false)

/**
 * Why [old] and [new] cannot be compared, as [EvolutionCodes.CANNOT_DIFF] diagnostics: one per side
 * that failed to parse or analyze (counting its errors rather than repeating them, since `schemata
 * check` already shows them), or, when both sides loaded, one when they share no namespace at all,
 * which means two unrelated schema sets rather than two versions of one. Empty when the two can be
 * compared.
 */
internal fun cannotDiff(old: Analyzed, new: Analyzed): List<Diagnostic> {
    val failures =
        listOfNotNull(
            if (old.schema == null) cannotLoad("OLD", old.diagnostics) else null,
            if (new.schema == null) cannotLoad("NEW", new.diagnostics) else null,
        )
    if (failures.isNotEmpty()) return failures
    return listOfNotNull(disjoint(old.schema!!, new.schema!!))
}

private fun cannotLoad(label: String, diagnostics: List<Diagnostic>): Diagnostic {
    val errors = diagnostics.count { it.severity == Severity.ERROR }
    val span = diagnostics.first { it.severity == Severity.ERROR }.span
    return Diagnostic(
        EvolutionCodes.CANNOT_DIFF,
        "$label: the schema set has ${plural(errors, "error")}",
        span,
        help = "fix the schema with check before diffing",
    )
}

private fun disjoint(old: Schema, new: Schema): Diagnostic? {
    val oldNames = old.namespaces.map { it.name }.toSet()
    val newNames = new.namespaces.map { it.name }.toSet()
    if (oldNames.any { it in newNames }) return null
    val span = (new.namespaces.firstOrNull() ?: old.namespaces.firstOrNull())?.span ?: return null
    return Diagnostic(
        EvolutionCodes.CANNOT_DIFF,
        "OLD and NEW share no schema",
        span,
        help = "diff two versions of the same schema set",
    )
}

private fun plural(n: Int, noun: String) = if (n == 1) "1 $noun" else "$n ${noun}s"
