package io.schemata.target.sql

import io.schemata.target.TargetModel

/** Every DDL file the compilation produces, one per namespace, in namespace order. */
data class RelationalModel(val schemas: List<RelationalSchema>) : TargetModel

/**
 * One namespace's DDL. Dialect-neutral: Postgres spellings appear only in [SqlRenderer]. [path] is
 * decided here, in lowering, not in the renderer.
 */
data class RelationalSchema(val path: String, val schemaName: String, val tables: List<Table>)

data class Table(val name: String, val columns: List<Column>)

data class Column(val name: String, val type: ColumnType, val nullable: Boolean)

enum class ColumnType {
    BOOLEAN,
    INTEGER,
    TEXT,
    UUID,
}
