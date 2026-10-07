package io.schemata.migrate

import io.schemata.target.sql.Ddl
import io.schemata.target.sql.Naming

/** Spells each [Step] as Postgres DDL and wraps one namespace's steps in a transactional file. */
object MigrationRenderer {
    private fun q(identifier: String) = Naming.quote(identifier)

    private fun t(at: At) = "${q(at.schema)}.${q(at.table)}"

    /**
     * The SQL of one step, one or more complete statements each on its own line, no trailing
     * newline.
     */
    fun sql(step: Step): String =
        when (step) {
            is CreateSchema -> Ddl.createSchema(step.schema)
            is DropSchema -> "DROP SCHEMA ${q(step.schema)};"
            is CreateTable -> Ddl.createTable(step.schema, step.table).trimEnd()
            is DropTable -> "DROP TABLE ${t(step.at)} CASCADE;"
            is RenameTable -> "ALTER TABLE ${t(step.at)} RENAME TO ${q(step.to)};"
            is SetSchema -> "ALTER TABLE ${t(step.at)} SET SCHEMA ${q(step.to)};"
            is AddColumn ->
                "ALTER TABLE ${t(step.at)} ADD COLUMN ${q(step.column.name)} ${Ddl.columnDefinition(step.column)};"
            is DropColumn -> "ALTER TABLE ${t(step.at)} DROP COLUMN ${q(step.column)};"
            is RenameColumn ->
                "ALTER TABLE ${t(step.at)} RENAME COLUMN ${q(step.from)} TO ${q(step.to)};"
            is AlterColumnType -> {
                val type = Ddl.spell(step.type)
                "ALTER TABLE ${t(step.at)} ALTER COLUMN ${q(step.column)} TYPE $type USING ${q(step.column)}::$type;"
            }
            is SetNotNull ->
                "ALTER TABLE ${t(step.at)} ALTER COLUMN ${q(step.column)} SET NOT NULL;"
            is DropNotNull ->
                "ALTER TABLE ${t(step.at)} ALTER COLUMN ${q(step.column)} DROP NOT NULL;"
            is SetDefault ->
                "ALTER TABLE ${t(step.at)} ALTER COLUMN ${q(step.column)} SET DEFAULT ${step.default};"
            is DropDefault ->
                "ALTER TABLE ${t(step.at)} ALTER COLUMN ${q(step.column)} DROP DEFAULT;"
            is Backfill ->
                "UPDATE ${t(step.at)} SET ${q(step.column)} = ${step.default} WHERE ${q(step.column)} IS NULL;"
            is RenameValue -> {
                val c = q(step.column)
                val olds = step.renames.joinToString(", ") { Naming.literal(it.first) }
                fun case(of: String) =
                    "CASE $of " +
                        step.renames.joinToString(" ") { (from, to) ->
                            "WHEN ${Naming.literal(from)} THEN ${Naming.literal(to)}"
                        }
                if (step.array)
                    "UPDATE ${t(step.at)} SET $c = ARRAY(SELECT ${case("e")} ELSE e END FROM unnest($c) e) WHERE $c && ARRAY[$olds];"
                else "UPDATE ${t(step.at)} SET $c = ${case(c)} END WHERE $c IN ($olds);"
            }
            is DropConstraint -> {
                val exists = if (step.ifExists) "IF EXISTS " else ""
                val cascade = if (step.cascade) " CASCADE" else ""
                "ALTER TABLE ${t(step.at)} DROP CONSTRAINT $exists${q(step.name)}$cascade;"
            }
            is AddConstraint -> {
                when (val c = step.constraint) {
                    is Constraint.PrimaryKey ->
                        "ALTER TABLE ${t(step.at)} ADD ${Ddl.primaryKey(c.name, c.columns)};"
                    is Constraint.UniqueKey ->
                        "ALTER TABLE ${t(step.at)} ADD ${Ddl.unique(c.unique)};"
                    is Constraint.CheckConstraint ->
                        "ALTER TABLE ${t(step.at)} ADD ${Ddl.check(c.check)};"
                    is Constraint.Foreign -> Ddl.addForeignKey(c.fk)
                }
            }
            is RenameConstraint ->
                "ALTER TABLE ${t(step.at)} RENAME CONSTRAINT ${q(step.from)} TO ${q(step.to)};"
            is DropIndex -> "DROP INDEX ${q(step.schema)}.${q(step.name)};"
            is CreateIndex -> Ddl.createIndex(step.at.schema, step.at.table, step.index)
            is RenameIndex ->
                "ALTER INDEX ${q(step.schema)}.${q(step.from)} RENAME TO ${q(step.to)};"
            is Comment ->
                if (step.column == null)
                    Ddl.commentOnTable(step.at.schema, step.at.table, step.text)
                else Ddl.commentOnColumn(step.at.schema, step.at.table, step.column, step.text)
        }

    /**
     * One namespace's file: `BEGIN;`, a blank line, each step's SQL (a destructive step preceded by
     * `-- SCH2701: <message>` when [allowDestructive]), a blank line, `COMMIT;`, trailing newline.
     */
    fun render(namespace: NamespaceMigration, allowDestructive: Boolean): String = buildString {
        appendLine("BEGIN;")
        appendLine()
        namespace.steps.forEach { step ->
            if (step.risk == Risk.DESTRUCTIVE && allowDestructive)
                appendLine("-- ${MigrateCodes.DESTRUCTIVE.id}: ${MigrateCodes.message(step)}")
            appendLine(sql(step))
        }
        appendLine()
        appendLine("COMMIT;")
    }

    /** `migrate/<path>` → content, for every namespace in [migration]. */
    fun files(migration: Migration, allowDestructive: Boolean): List<Pair<String, String>> =
        migration.namespaces.map { "migrate/${it.path}" to render(it, allowDestructive) }
}
