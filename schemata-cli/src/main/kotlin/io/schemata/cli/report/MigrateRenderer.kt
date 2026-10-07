package io.schemata.cli.report

import io.schemata.cli.Migrated
import io.schemata.evolution.SqlRules
import io.schemata.lang.Diagnostic
import io.schemata.lang.Severity
import io.schemata.migrate.AddColumn
import io.schemata.migrate.AddConstraint
import io.schemata.migrate.AlterColumnType
import io.schemata.migrate.Backfill
import io.schemata.migrate.Comment
import io.schemata.migrate.CreateIndex
import io.schemata.migrate.CreateSchema
import io.schemata.migrate.CreateTable
import io.schemata.migrate.DropColumn
import io.schemata.migrate.DropConstraint
import io.schemata.migrate.DropDefault
import io.schemata.migrate.DropIndex
import io.schemata.migrate.DropNotNull
import io.schemata.migrate.DropSchema
import io.schemata.migrate.DropTable
import io.schemata.migrate.MigrateCodes
import io.schemata.migrate.Migration
import io.schemata.migrate.MigrationRenderer
import io.schemata.migrate.RenameColumn
import io.schemata.migrate.RenameConstraint
import io.schemata.migrate.RenameIndex
import io.schemata.migrate.RenameTable
import io.schemata.migrate.RenameValue
import io.schemata.migrate.Risk
import io.schemata.migrate.SetDefault
import io.schemata.migrate.SetNotNull
import io.schemata.migrate.SetSchema
import io.schemata.migrate.Step
import io.schemata.target.sql.Ddl
import io.schemata.target.sql.Naming

/** `schemata migrate`'s own output: the human step list, and the JSON document. */
object MigrateRenderer {
    /**
     * One block per file in path order, one line per step (` <describe> <risk>`), then a trailer:
     * `<n> steps in <m> files`, `<d> destructive, <f> may fail`; `no changes` when the migration is
     * empty.
     */
    fun steps(migration: Migration): String {
        if (migration.isEmpty) return "no changes\n"
        val lines = mutableListOf<String>()
        migration.namespaces.forEach { ns ->
            lines += ns.path
            ns.steps.forEach { lines += "  ${describe(it)}    ${risk(it.risk)}" }
        }
        lines += ""
        lines +=
            "${plural(migration.steps.size, "step")} in ${plural(migration.namespaces.size, "file")}"
        lines +=
            "${migration.steps.count { it.risk == Risk.DESTRUCTIVE }} destructive, " +
                "${migration.steps.count { it.risk == Risk.MAY_FAIL }} may fail"
        return lines.joinToString("\n") + "\n"
    }

    /**
     * `diff`'s document for the sql rulebook plus `steps` (`file`, `kind`, `sql`, `risk`, `path`,
     * `line`, and `code`, `message`, `help` for a step that is not clean, null otherwise) and
     * `files` (`path`, `content`); `errors`, shaped as `diff`'s, holds only errors: why the sides
     * cannot be compared, or a side's SQL errors.
     */
    internal fun json(
        migrated: Migrated,
        report: Report,
        files: List<Pair<String, String>>,
        errors: List<Diagnostic> = emptyList(),
    ): String {
        val comparison = migrated.comparison
        val shown =
            (errors.ifEmpty { if (migrated.lowered) emptyList() else migrated.diagnostics })
                .filter { it.severity == Severity.ERROR }
        val fields =
            mutableListOf<Pair<String, Any?>>(
                "changes" to comparison.judged.map { DiffRenderer.changeJson(it) },
                "summary" to
                    DiffRenderer.obj(
                        listOf(
                            SqlRules.target to DiffRenderer.summaryJson(comparison, SqlRules.target)
                        )
                    ),
                "steps" to
                    migrated.migration.namespaces.flatMap { ns ->
                        ns.steps.map { stepJson("migrate/${ns.path}", it) }
                    },
                "files" to
                    files.map { (path, content) -> Json.Obj("path" to path, "content" to content) },
                "exitCode" to report.exitCode,
            )
        if (shown.isNotEmpty()) fields += "errors" to shown.map { DiffRenderer.errorJson(it) }
        return Json.document(DiffRenderer.obj(fields))
    }

    private fun stepJson(file: String, step: Step): Json.Obj {
        val risky = step.risk != Risk.CLEAN
        val code =
            when (step.risk) {
                Risk.CLEAN -> null
                Risk.MAY_FAIL -> MigrateCodes.MAY_FAIL.id
                Risk.DESTRUCTIVE -> MigrateCodes.DESTRUCTIVE.id
            }
        return Json.Obj(
            "file" to file,
            "kind" to step::class.simpleName!!.replaceFirstChar { it.lowercase() },
            "sql" to MigrationRenderer.sql(step),
            "risk" to risk(step.risk),
            "path" to step.subject.path,
            "line" to step.subject.span.startLine,
            "code" to code,
            "message" to if (risky) MigrateCodes.message(step) else null,
            "help" to if (risky) step.help else null,
        )
    }

    private fun risk(r: Risk) =
        when (r) {
            Risk.CLEAN -> "clean"
            Risk.MAY_FAIL -> "may fail"
            Risk.DESTRUCTIVE -> "destructive"
        }

    private fun plural(n: Int, noun: String) = if (n == 1) "1 $noun" else "$n ${noun}s"

    private fun q(name: String) = Naming.quote(name)

    private fun describe(step: Step): String =
        when (step) {
            is CreateSchema -> "create schema ${q(step.schema)}"
            is DropSchema -> "drop schema ${q(step.schema)}"
            is CreateTable -> "create table ${q(step.table.name)}"
            is DropTable -> "drop table ${q(step.at.table)}"
            is RenameTable -> "rename table ${q(step.at.table)} to ${q(step.to)}"
            is SetSchema -> "move table ${q(step.at.table)} to schema ${q(step.to)}"
            is AddColumn -> "add column ${q(step.at.table)}.${q(step.column.name)}"
            is DropColumn -> "drop column ${q(step.at.table)}.${q(step.column)}"
            is RenameColumn -> "rename column ${q(step.at.table)}.${q(step.from)} to ${q(step.to)}"
            is AlterColumnType ->
                "alter type ${q(step.at.table)}.${q(step.column)} to ${Ddl.spell(step.type)}"
            is SetNotNull -> "set not null ${q(step.at.table)}.${q(step.column)}"
            is DropNotNull -> "drop not null ${q(step.at.table)}.${q(step.column)}"
            is SetDefault -> "set default ${q(step.at.table)}.${q(step.column)}"
            is DropDefault -> "drop default ${q(step.at.table)}.${q(step.column)}"
            is Backfill -> "backfill ${q(step.at.table)}.${q(step.column)}"
            is RenameValue ->
                "rename values ${q(step.at.table)}.${q(step.column)} " +
                    step.renames.joinToString(", ") { (from, to) ->
                        "${Naming.literal(from)} to ${Naming.literal(to)}"
                    }
            is DropConstraint -> "drop constraint ${q(step.at.table)}.${q(step.name)}"
            is AddConstraint -> "add constraint ${q(step.at.table)}.${q(step.constraint.name)}"
            is RenameConstraint ->
                "rename constraint ${q(step.at.table)}.${q(step.from)} to ${q(step.to)}"
            is DropIndex -> "drop index ${q(step.name)}"
            is CreateIndex -> "create index ${q(step.index.name)}"
            is RenameIndex -> "rename index ${q(step.from)} to ${q(step.to)}"
            is Comment -> {
                val column = step.column
                if (column == null) "comment on ${q(step.at.table)}"
                else "comment on ${q(step.at.table)}.${q(column)}"
            }
        }
}
