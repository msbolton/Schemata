package io.schemata.target.sql

import io.schemata.target.OutputFile
import io.schemata.target.sql.Naming.literal
import io.schemata.target.sql.Naming.quote

/**
 * Postgres DDL. No decisions are made here; every identifier is quoted so reserved words are safe.
 */
object SqlRenderer {
    fun render(model: RelationalModel): List<OutputFile> =
        model.schemas.map { OutputFile(it.path, text(it)) }

    private fun text(schema: RelationalSchema): String = buildString {
        val s = quote(schema.schemaName)
        appendLine("CREATE SCHEMA IF NOT EXISTS $s;")
        schema.tables.forEach { table ->
            appendLine()
            appendLine("CREATE TABLE $s.${quote(table.name)} (")
            val lines = table.columns.map { column(it) } + constraints(table)
            lines.forEachIndexed { i, (text, note) ->
                append("  $text")
                if (i < lines.lastIndex) append(",")
                note?.let { append("  -- schemata: $it") }
                appendLine()
            }
            appendLine(");")
        }
        val indexes = schema.tables.flatMap { t -> t.indexes.map { t to it } }
        if (indexes.isNotEmpty()) appendLine()
        indexes.forEach { (t, ix) ->
            appendLine(
                "CREATE INDEX ${quote(ix.name)} ON $s.${quote(t.name)} (${columns(ix.columns)});"
            )
        }
        if (schema.foreignKeys.isNotEmpty()) appendLine()
        schema.foreignKeys.forEach { fk ->
            append("ALTER TABLE $s.${quote(fk.table)} ADD CONSTRAINT ${quote(fk.name)} ")
            append("FOREIGN KEY (${columns(fk.columns)}) ")
            append(
                "REFERENCES ${quote(fk.targetSchema)}.${quote(fk.targetTable)} (${columns(fk.targetColumns)})"
            )
            if (fk.cascade) append(" ON DELETE CASCADE")
            appendLine(";")
        }
        val comments =
            schema.tables.flatMap { t ->
                listOfNotNull(
                    t.doc?.let { "COMMENT ON TABLE $s.${quote(t.name)} IS ${literal(it)};" }
                ) +
                    t.columns.mapNotNull { c ->
                        c.doc?.let {
                            "COMMENT ON COLUMN $s.${quote(t.name)}.${quote(c.name)} IS ${literal(it)};"
                        }
                    }
            }
        if (comments.isNotEmpty()) appendLine()
        comments.forEach { appendLine(it) }
    }

    /** A body line and its optional note. */
    private fun column(column: Column): Pair<String, String?> {
        val text = buildString {
            append("${quote(column.name)} ${spell(column.type)}")
            if (!column.nullable) append(" NOT NULL")
            column.default?.let { append(" DEFAULT $it") }
        }
        val note = column.notes.takeIf { it.isNotEmpty() }?.joinToString("; ")
        return text to note
    }

    private fun constraints(table: Table): List<Pair<String, String?>> =
        listOfNotNull(
                table.primaryKey
                    .takeIf { it.isNotEmpty() }
                    ?.let {
                        "CONSTRAINT ${quote(table.primaryKeyName!!)} PRIMARY KEY (${columns(it)})"
                    }
            )
            .plus(
                table.uniques.map { "CONSTRAINT ${quote(it.name)} UNIQUE (${columns(it.columns)})" }
            )
            .plus(table.checks.map { "CONSTRAINT ${quote(it.name)} CHECK (${it.expression})" })
            .map { it to null }

    private fun columns(names: List<String>): String = names.joinToString(", ") { quote(it) }

    private fun spell(type: ColumnType): String =
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
}
