package io.schemata.target.sql

import io.schemata.core.ir.QualifiedName
import io.schemata.lang.Span
import io.schemata.target.TargetModel

/** Every DDL file the compilation produces, one per namespace, in namespace order. */
data class RelationalModel(val schemas: List<RelationalSchema>) : TargetModel

/**
 * One namespace's DDL, legal by construction: names are final and quoted by the renderer, every
 * constraint names existing columns, and foreign keys are printed after every table. [path] is
 * decided here, in lowering, not in the renderer. Each table and column carries its provenance,
 * which the renderer ignores and `schemata migrate` matches on.
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
    val origin: TableOrigin,
    val span: Span,
)

/** [default] and [notes] are already spelled for DDL: `'pending'`, `3`; `uuid?`. */
data class Column(
    val name: String,
    val type: ColumnType,
    val nullable: Boolean,
    val default: String? = null,
    val doc: String? = null,
    val notes: List<String> = emptyList(),
    val origin: ColumnOrigin,
    val span: Span,
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

/** One link of the field chain that produced a column or child table. */
sealed interface OriginStep {
    /** A field, by its ordinal within the record being lowered at that point. */
    data class FieldOrdinal(val ordinal: Int) : OriginStep

    /** A union member, by its ordinal within the union the preceding field names. */
    data class MemberOrdinal(val ordinal: Int) : OriginStep
}

/**
 * Which record a table serves and, for a child table, the field chain from that record's own table
 * down to the list or map field whose rows it holds. Two tables with equal origins are the same
 * table across two schema versions, whatever they are named.
 */
data class TableOrigin(val record: QualifiedName, val path: List<OriginStep> = emptyList())

/**
 * What produced a column: a field chain from the table's record, or a role the lowering invents.
 */
sealed interface ColumnOrigin {
    /**
     * [path] is the field chain from the table's record (an embedded `shipping.street` is two
     * steps, a union variant column has the member's ordinal between the field and the member's own
     * fields). [part] distinguishes the several columns one chain can produce: `kind` for a union's
     * discriminator, `k0`, `k1`, ... for the columns a reference copies from its target's key, null
     * for a chain that produces exactly one column.
     */
    data class FieldPath(val path: List<OriginStep>, val part: String? = null) : ColumnOrigin

    /**
     * A column no field names: `position` and `key` (a child table's discriminator), `value` (a
     * child table's scalar element), `parent:0`, `parent:1`, ... (a child table's copy of its
     * parent's key columns).
     */
    data class Role(val role: String) : ColumnOrigin
}
