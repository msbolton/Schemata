package io.schemata.target.sql

import io.schemata.target.TargetModel

/** Dialect-neutral. Postgres spellings appear only in [SqlRenderer]. */
data class RelationalSchema(val schemaName: String, val tables: List<Table>) : TargetModel

data class Table(val name: String, val columns: List<Column>)

data class Column(val name: String, val type: ColumnType, val nullable: Boolean)

enum class ColumnType {
    BOOLEAN,
    INTEGER,
    TEXT,
    UUID,
}
