package io.schemata.migrate

import io.schemata.lang.Category
import io.schemata.lang.Diagnostic
import io.schemata.lang.DiagnosticCode
import io.schemata.lang.Severity
import io.schemata.target.sql.Ddl
import io.schemata.target.sql.Naming

object MigrateCodes {
    /** Error unless the run allows destructive steps, then a warning; one id either way. */
    val DESTRUCTIVE: DiagnosticCode =
        DiagnosticCode(
            "SCH2701",
            Severity.ERROR,
            Category.LOSSY,
            "a migration step loses data; a warning under --allow-destructive",
        )

    val DESTRUCTIVE_ALLOWED: DiagnosticCode = DESTRUCTIVE.copy(severity = Severity.WARNING)

    val MAY_FAIL: DiagnosticCode =
        DiagnosticCode(
            "SCH2702",
            Severity.WARNING,
            Category.LOSSY,
            "a migration step can fail on existing rows",
        )

    /** The codes the appendix lists: one entry per id. */
    val all: List<DiagnosticCode> = listOf(DESTRUCTIVE, MAY_FAIL)

    /** The diagnostics [migration] reports, in step order. */
    fun diagnostics(migration: Migration, allowDestructive: Boolean): List<Diagnostic> =
        migration.steps
            .filter { it.risk != Risk.CLEAN }
            .map { step ->
                val code =
                    when (step.risk) {
                        Risk.DESTRUCTIVE -> if (allowDestructive) DESTRUCTIVE_ALLOWED else DESTRUCTIVE
                        else -> MAY_FAIL
                    }
                Diagnostic(code, message(step), step.subject.span, step.help)
            }

    /** `<path>: <statement> loses <reason>` / `<path>: <statement> fails when <reason>`. */
    fun message(step: Step): String {
        val verb = if (step.risk == Risk.DESTRUCTIVE) "loses" else "fails when"
        return "${step.subject.path}: ${statement(step)} $verb ${step.reason}"
    }

    private fun statement(step: Step): String =
        when (step) {
            is DropColumn -> "DROP COLUMN ${Naming.quote(step.column)}"
            is DropTable -> "DROP TABLE ${Naming.quote(step.at.table)}"
            is AlterColumnType ->
                "ALTER COLUMN ${Naming.quote(step.column)} TYPE ${Ddl.spell(step.type)}"
            is SetNotNull -> "SET NOT NULL on ${Naming.quote(step.column)}"
            is AddConstraint ->
                when (val c = step.constraint) {
                    is Constraint.PrimaryKey -> "PRIMARY KEY ${Naming.quote(c.name)}"
                    is Constraint.UniqueKey -> "UNIQUE ${Naming.quote(c.name)}"
                    is Constraint.CheckConstraint -> "CONSTRAINT ${Naming.quote(c.name)}"
                    is Constraint.Foreign -> "FOREIGN KEY ${Naming.quote(c.name)}"
                }
            else -> MigrationRenderer.sql(step).removeSuffix(";")
        }
}
