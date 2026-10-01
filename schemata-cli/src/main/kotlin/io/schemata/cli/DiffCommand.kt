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
import io.schemata.core.ir.QualifiedName
import io.schemata.core.ir.Schema
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
            targetNames?.map { Rulebooks.named(it) ?: throw UsageError("unknown target '$it'") }
                ?: Rulebooks.all
        val oldSide = load("OLD", old)
        val newSide = load("NEW", new)
        val failures = listOfNotNull(oldSide.cannotDiff, newSide.cannotDiff)
        if (failures.isNotEmpty()) {
            val report = Report.of(failures, emptyList(), emptyList(), strict = reporting.strict)
            emit(this, report, Sources.of(oldSide.sources + newSide.sources), reporting, out = "")
            return
        }
        val oldSchema = oldSide.schema!!
        val newSchema = newSide.schema!!
        val comparison = Evolution.compare(oldSchema, newSchema, rulebooks)
        val report =
            Report.of(comparison.diagnostics, emptyList(), emptyList(), strict = reporting.strict)
        when (reporting.format) {
            Format.JSON ->
                echo(DiffRenderer.json(comparison, report, rulebooks), trailingNewline = false)
            Format.HUMAN -> {
                val implicitOrdinals = oldSide.implicitOrdinals + newSide.implicitOrdinals
                echo(
                    DiffRenderer.changes(comparison, oldSchema, newSchema, implicitOrdinals),
                    err = true,
                )
                emit(
                    this,
                    report,
                    Sources.of(newSide.sources + oldSide.sources),
                    reporting,
                    out = "",
                )
            }
        }
        if (reporting.format == Format.JSON && report.exitCode != 0)
            throw ProgramResult(report.exitCode)
    }

    private fun load(label: String, path: Path): Loaded {
        val sources = loadSources(listOf(path))
        val analyzed = Pipeline.analyze(sources, strict = false)
        val cannotDiff =
            if (analyzed.schema == null) cannotDiffDiagnostic(label, analyzed.diagnostics) else null
        return Loaded(sources, analyzed.schema, analyzed.implicitOrdinals, cannotDiff)
    }
}

private data class Loaded(
    val sources: List<SourceInput>,
    val schema: Schema?,
    val implicitOrdinals: Set<QualifiedName>,
    val cannotDiff: Diagnostic?,
)

/**
 * `diff` cannot compare a side that failed to parse or analyze: one [EvolutionCodes.CANNOT_DIFF]
 * diagnostic per side, naming how many core errors it reported, instead of the errors themselves
 * (already visible through `schemata check`).
 */
internal fun cannotDiffDiagnostic(label: String, diagnostics: List<Diagnostic>): Diagnostic {
    val errors = diagnostics.count { it.severity == Severity.ERROR }
    val span = diagnostics.first { it.severity == Severity.ERROR }.span
    return Diagnostic(
        EvolutionCodes.CANNOT_DIFF,
        "$label: ${plural(errors, "error")}; fix the schema with check before diffing",
        span,
        help = "run `schemata check` on the $label side to see what is wrong",
    )
}

private fun plural(n: Int, noun: String) = if (n == 1) "1 $noun" else "$n ${noun}s"
