package io.schemata.target.sql

import io.schemata.core.ir.OnDelete
import io.schemata.target.sql.Naming.literal
import io.schemata.target.sql.Naming.quote

object Ddl {
    fun createSchema(schema: String): String = "CREATE SCHEMA IF NOT EXISTS ${quote(schema)};"

    fun createTable(schema: String, table: Table): String = buildString {
        val s = quote(schema)
        appendLine("CREATE TABLE $s.${quote(table.name)} (")
        val lines = columnLines(table)
        lines.forEachIndexed { i, (text, note) ->
            append("  $text")
            if (i < lines.lastIndex) append(",")
            note?.let { append("  -- schemata: $it") }
            appendLine()
        }
        appendLine(");")
    }

    fun createIndex(schema: String, table: String, index: Index): String =
        "CREATE INDEX ${quote(index.name)} ON ${quote(schema)}.${quote(table)} (${columns(index.columns)});"

    fun addForeignKey(fk: ForeignKey): String = buildString {
        append(
            "ALTER TABLE ${quote(fk.schema)}.${quote(fk.table)} ADD CONSTRAINT ${quote(fk.name)} "
        )
        append("FOREIGN KEY (${columns(fk.columns)}) ")
        append(
            "REFERENCES ${quote(fk.targetSchema)}.${quote(fk.targetTable)} (${columns(fk.targetColumns)})"
        )
        // RESTRICT is spelled by saying nothing: Postgres's default refuses the delete too.
        when (fk.onDelete) {
            OnDelete.RESTRICT -> {}
            OnDelete.CASCADE -> append(" ON DELETE CASCADE")
            OnDelete.SET_NULL -> append(" ON DELETE SET NULL")
        }
        append(";")
    }

    fun commentOnTable(schema: String, table: String, text: String?): String =
        if (text == null) {
            "COMMENT ON TABLE ${quote(schema)}.${quote(table)} IS NULL;"
        } else {
            "COMMENT ON TABLE ${quote(schema)}.${quote(table)} IS ${literal(text)};"
        }

    fun commentOnColumn(schema: String, table: String, column: String, text: String?): String =
        if (text == null) {
            "COMMENT ON COLUMN ${quote(schema)}.${quote(table)}.${quote(column)} IS NULL;"
        } else {
            "COMMENT ON COLUMN ${quote(schema)}.${quote(table)}.${quote(column)} IS ${literal(text)};"
        }

    fun columnDefinition(column: Column): String = buildString {
        append(spell(column.type))
        if (!column.nullable) append(" NOT NULL")
        column.default?.let { append(" DEFAULT $it") }
    }

    fun primaryKey(name: String, columns: List<String>): String =
        "CONSTRAINT ${quote(name)} PRIMARY KEY (${columns(columns)})"

    fun unique(unique: Unique): String =
        "CONSTRAINT ${quote(unique.name)} UNIQUE (${columns(unique.columns)})"

    fun check(check: Check): String = "CONSTRAINT ${quote(check.name)} CHECK (${check.expression})"

    fun spell(type: ColumnType): String =
        when (type) {
            ColumnType.BOOLEAN -> "boolean"
            ColumnType.INTEGER -> "integer"
            ColumnType.BIGINT -> "bigint"
            ColumnType.REAL -> "real"
            ColumnType.DOUBLE -> "double precision"
            is ColumnType.NUMERIC -> "numeric(${type.precision}, ${type.scale})"
            ColumnType.TEXT -> "text"
            is ColumnType.VARCHAR -> "varchar(${type.length})"
            ColumnType.BYTEA -> "bytea"
            ColumnType.UUID -> "uuid"
            ColumnType.DATE -> "date"
            ColumnType.TIME -> "time"
            ColumnType.TIMESTAMPTZ -> "timestamptz"
            ColumnType.INTERVAL -> "interval"
            ColumnType.JSONB -> "jsonb"
            is ColumnType.ARRAY -> "${spell(type.element)}[]"
            is ColumnType.RAW -> type.spelling
        }

    fun columns(names: List<String>): String = names.joinToString(", ") { quote(it) }

    private fun columnLines(table: Table): List<Pair<String, String?>> {
        val columnLines = table.columns.map { columnLine(it) }
        val constraintLines =
            listOfNotNull(
                table.primaryKey
                    .takeIf { it.isNotEmpty() }
                    ?.let { primaryKey(table.primaryKeyName!!, it) to null }
            ) + table.uniques.map { unique(it) to null } + table.checks.map { check(it) to null }
        return columnLines + constraintLines
    }

    private fun columnLine(column: Column): Pair<String, String?> {
        val text = "${quote(column.name)} ${columnDefinition(column)}"
        val note = column.notes.takeIf { it.isNotEmpty() }?.joinToString("; ")
        return text to note
    }
}
