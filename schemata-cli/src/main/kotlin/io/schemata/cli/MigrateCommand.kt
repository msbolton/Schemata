package io.schemata.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import io.schemata.cli.report.MigrateRenderer
import io.schemata.cli.report.Report
import io.schemata.cli.report.Skipped
import io.schemata.cli.report.Sources
import io.schemata.cli.report.Written
import io.schemata.core.ir.Schema
import io.schemata.evolution.Comparison
import io.schemata.evolution.Evolution
import io.schemata.evolution.SqlRules
import io.schemata.lang.Diagnostic
import io.schemata.lang.Severity
import io.schemata.migrate.MigrateCodes
import io.schemata.migrate.Migration
import io.schemata.migrate.MigrationRenderer
import io.schemata.migrate.Planner
import io.schemata.migrate.Side
import io.schemata.target.sql.SqlTarget
import kotlin.io.path.Path

class MigrateCommand : CliktCommand(name = "migrate") {
    override fun help(context: Context) =
        "Write the Postgres DDL that carries a database from one schema version to the next."

    private val out by
        option("--out", help = "Output directory (default: out)")
            .path(canBeFile = false)
            .default(Path("out"))

    private val allowDestructive by
        option(
                "--allow-destructive",
                help = "Write steps that lose data, reporting each as a warning",
            )
            .flag()

    private val reporting by ReportingOptions()

    private val old by argument("OLD").path(mustExist = true)

    private val new by argument("NEW").path(mustExist = true)

    override fun run() {
        val oldSources = loadSources(listOf(old))
        val newSources = loadSources(listOf(new))
        val oldAnalyzed = analyzeSide(oldSources)
        val newAnalyzed = analyzeSide(newSources)
        val sources = Sources.of(newSources + oldSources)
        val failures = cannotDiff(oldAnalyzed, newAnalyzed)
        if (failures.isNotEmpty()) {
            val report = Report.of(failures, emptyList(), emptyList(), strict = reporting.strict)
            if (reporting.format == Format.JSON) {
                echo(
                    MigrateRenderer.json(Migrated.EMPTY, report, emptyList(), failures),
                    trailingNewline = false,
                )
                throw ProgramResult(report.exitCode)
            }
            emit(this, report, sources, reporting, out.toString())
            return
        }
        val migrated = migrate(oldAnalyzed.schema!!, newAnalyzed.schema!!, allowDestructive)
        val files =
            if (migrated.lowered) MigrationRenderer.files(migrated.migration, allowDestructive)
            else emptyList()
        val provisional =
            Report.of(migrated.diagnostics, emptyList(), emptyList(), strict = reporting.strict)
        val writes = provisional.errors == 0 && files.isNotEmpty()
        val report =
            Report.of(
                migrated.diagnostics,
                written = if (writes) files.map { Written("migrate", it.first) } else emptyList(),
                skipped =
                    if (!writes && files.isNotEmpty())
                        listOf(Skipped("migrate", provisional.errors))
                    else emptyList(),
                strict = reporting.strict,
            )
        if (writes) files.forEach { (path, content) -> writeOutput(out.resolve(path), content) }
        when (reporting.format) {
            Format.JSON -> {
                echo(
                    MigrateRenderer.json(migrated, report, if (writes) files else emptyList()),
                    trailingNewline = false,
                )
                if (report.exitCode != 0) throw ProgramResult(report.exitCode)
            }
            Format.HUMAN -> {
                if (migrated.lowered) echo(MigrateRenderer.steps(migrated.migration), err = true)
                emit(this, report, sources, reporting, out.toString())
            }
        }
    }
}

/**
 * Everything `schemata migrate` computes before rendering, so tests and fixtures see the same
 * thing.
 */
internal data class Migrated(
    val migration: Migration,
    /** The SQL rulebook's judgement of the same two schemas, for the JSON `changes`. */
    val comparison: Comparison,
    /** SQL lowering diagnostics of either side when present, else the migration's. */
    val diagnostics: List<Diagnostic>,
    /** False when a side had an SQL error: nothing is planned. */
    val lowered: Boolean,
) {
    companion object {
        val EMPTY =
            Migrated(
                Migration(emptyList()),
                Comparison(emptyList(), emptyList()),
                emptyList(),
                false,
            )
    }
}

internal fun migrate(old: Schema, new: Schema, allowDestructive: Boolean): Migrated {
    val comparison = Evolution.compare(old, new, listOf(SqlRules))
    val oldLowered = SqlTarget.lower(old)
    val newLowered = SqlTarget.lower(new)
    val errors =
        (oldLowered.diagnostics + newLowered.diagnostics).filter { it.severity == Severity.ERROR }
    if (errors.isNotEmpty())
        return Migrated(
            Migration(emptyList()),
            comparison,
            oldLowered.diagnostics + newLowered.diagnostics,
            lowered = false,
        )
    val migration = Planner.plan(Side(old, oldLowered.model), Side(new, newLowered.model))
    return Migrated(
        migration,
        comparison,
        MigrateCodes.diagnostics(migration, allowDestructive),
        lowered = true,
    )
}
