package io.schemata.target.sql

import io.schemata.target.OutputFile

/**
 * Postgres DDL. No decisions are made here; every identifier is quoted so reserved words are safe.
 */
object SqlRenderer {
    fun render(model: RelationalModel): List<OutputFile> =
        model.schemas.map { OutputFile(it.path, text(it)) }

    private fun text(schema: RelationalSchema): String = buildString {
        appendLine(Ddl.createSchema(schema.schemaName))
        schema.tables.forEach { table ->
            appendLine()
            append(Ddl.createTable(schema.schemaName, table))
        }
        val indexes = schema.tables.flatMap { t -> t.indexes.map { t to it } }
        if (indexes.isNotEmpty()) appendLine()
        indexes.forEach { (t, ix) -> appendLine(Ddl.createIndex(schema.schemaName, t.name, ix)) }
        if (schema.foreignKeys.isNotEmpty()) appendLine()
        schema.foreignKeys.forEach { appendLine(Ddl.addForeignKey(it)) }
        val comments =
            schema.tables.flatMap { t ->
                listOfNotNull(t.doc?.let { Ddl.commentOnTable(schema.schemaName, t.name, it) }) +
                    t.columns.mapNotNull { c ->
                        c.doc?.let { Ddl.commentOnColumn(schema.schemaName, t.name, c.name, it) }
                    }
            }
        if (comments.isNotEmpty()) appendLine()
        comments.forEach { appendLine(it) }
    }
}
