package io.schemata.target.sql

import io.schemata.target.OutputFile

/** Postgres DDL. Every identifier is quoted so reserved words like `user` are safe. */
object SqlRenderer {
    fun render(schema: RelationalSchema): List<OutputFile> =
        listOf(OutputFile("${schema.schemaName}.sql", text(schema)))

    private fun text(schema: RelationalSchema): String = buildString {
        appendLine("CREATE SCHEMA IF NOT EXISTS ${quote(schema.schemaName)};")
        schema.tables.forEach { table ->
            appendLine()
            appendLine("CREATE TABLE ${quote(schema.schemaName)}.${quote(table.name)} (")
            table.columns.forEachIndexed { index, column ->
                append("  ${quote(column.name)} ${spell(column.type)}")
                if (!column.nullable) append(" NOT NULL")
                if (index < table.columns.lastIndex) append(",")
                appendLine()
            }
            appendLine(");")
        }
    }

    private fun quote(identifier: String): String = "\"" + identifier.replace("\"", "\"\"") + "\""

    private fun spell(type: ColumnType): String =
        when (type) {
            ColumnType.BOOLEAN -> "boolean"
            ColumnType.INTEGER -> "integer"
            ColumnType.TEXT -> "text"
            ColumnType.UUID -> "uuid"
        }
}
