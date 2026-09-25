package io.schemata.target.sql

import io.schemata.target.TargetModel

/** Every DDL file the compilation produces, one per namespace, in namespace order. */
data class RelationalModel(val schemas: List<RelationalSchema>) : TargetModel

/**
 * One namespace's DDL, legal by construction: names are final and quoted by the renderer, every
 * constraint names existing columns, and foreign keys are printed after every table. [path] is
 * decided here, in lowering, not in the renderer.
 */
data class RelationalSchema(
    val path: String,
    val schemaName: String,
    val tables: List<Table>,
    val foreignKeys: List<ForeignKey> = emptyList(),
)

/**
 * [primaryKeyName] is final when [primaryKey] is non-empty; lowering derives it, not the renderer.
 */
data class Table(
    val name: String,
    val columns: List<Column>,
    val primaryKey: List<String> = emptyList(),
    val primaryKeyName: String? = null,
    val checks: List<Check> = emptyList(),
    val uniques: List<Unique> = emptyList(),
    val indexes: List<Index> = emptyList(),
    val doc: String? = null,
)

/** [default] and [notes] are already spelled for DDL: `'pending'`, `3`; `uuid?`. */
data class Column(
    val name: String,
    val type: ColumnType,
    val nullable: Boolean,
    val default: String? = null,
    val doc: String? = null,
    val notes: List<String> = emptyList(),
)

/** Postgres spellings live in [SqlRenderer]; [RAW] carries an `@sql(type)` override verbatim. */
sealed interface ColumnType {
    data object BOOLEAN : ColumnType

    data object INTEGER : ColumnType

    data object BIGINT : ColumnType

    data object REAL : ColumnType

    data object DOUBLE : ColumnType

    data class NUMERIC(val precision: Int, val scale: Int) : ColumnType

    data object TEXT : ColumnType

    data class VARCHAR(val length: Int) : ColumnType

    data object BYTEA : ColumnType

    data object UUID : ColumnType

    data object DATE : ColumnType

    data object TIME : ColumnType

    data object TIMESTAMPTZ : ColumnType

    data object INTERVAL : ColumnType

    data object JSONB : ColumnType

    data class ARRAY(val element: ColumnType) : ColumnType

    data class RAW(val spelling: String) : ColumnType
}

/** [expression] is Postgres CHECK text with quoted column names: `"age" >= 0`. */
data class Check(val name: String, val expression: String)

data class Unique(val name: String, val columns: List<String>)

data class Index(val name: String, val columns: List<String>)

data class ForeignKey(
    val name: String,
    val schema: String,
    val table: String,
    val columns: List<String>,
    val targetSchema: String,
    val targetTable: String,
    val targetColumns: List<String>,
    val cascade: Boolean,
)
