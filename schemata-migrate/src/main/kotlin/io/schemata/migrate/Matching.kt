package io.schemata.migrate

import io.schemata.core.ir.QualifiedName
import io.schemata.core.ir.Schema
import io.schemata.evolution.Differ
import io.schemata.evolution.EnumValueAdded
import io.schemata.evolution.EnumValueRemoved
import io.schemata.evolution.EnumValueRenamed
import io.schemata.evolution.FieldNullabilityChanged
import io.schemata.evolution.FieldRefinementChanged
import io.schemata.evolution.FieldTypeChanged
import io.schemata.evolution.UnionMemberAdded
import io.schemata.evolution.UnionMemberRemoved
import io.schemata.target.sql.Column
import io.schemata.target.sql.Table
import io.schemata.target.sql.TableOrigin

/** A table and the schema it lives in, on one side. */
internal data class Placed(val schema: String, val table: Table)

/** A table both sides have, with its columns matched by origin. */
internal class Pairing(
    val oldSchema: String,
    val old: Table,
    val newSchema: String,
    val new: Table,
) {
    private val oldByOrigin = old.columns.associateBy { it.origin }
    private val newByOrigin = new.columns.associateBy { it.origin }

    /** Old and new column for every column both have, in NEW's order. */
    val changed: List<Pair<Column, Column>> =
        new.columns.mapNotNull { n -> oldByOrigin[n.origin]?.let { it to n } }
    val added: List<Column> = new.columns.filter { it.origin !in oldByOrigin }
    val dropped: List<Column> = old.columns.filter { it.origin !in newByOrigin }

    /** Old column name to new, for every column both have; a dropped column has no entry. */
    val names: Map<String, String> = changed.associate { (o, n) -> o.name to n.name }

    /** The OLD names of the columns whose type changes. */
    val retyped: Set<String> =
        changed.filter { (o, n) -> o.type != n.type }.map { it.first.name }.toSet()

    /** Where every step after the schema move and the renames addresses the table. */
    val at = At(newSchema, new.name)
}

/** What every namespace's plan shares: both sides' tables by origin and by address. */
internal class Context(val old: Side, val new: Side) {
    private val oldByOrigin = placed(old).associateBy { it.table.origin }
    private val newByOrigin = placed(new).associateBy { it.table.origin }
    private val oldByAt = placed(old).associateBy { At(it.schema, it.table.name) }
    private val newByAt = placed(new).associateBy { At(it.schema, it.table.name) }
    private val pairings = mutableMapOf<TableOrigin, Pairing?>()

    val tightening = Tightening(old.schema, new.schema)

    fun pairing(origin: TableOrigin): Pairing? =
        pairings.getOrPut(origin) {
            val o = oldByOrigin[origin] ?: return@getOrPut null
            val n = newByOrigin[origin] ?: return@getOrPut null
            Pairing(o.schema, o.table, n.schema, n.table)
        }

    fun oldTable(at: At): Table? = oldByAt[at]?.table

    fun newTable(at: At): Table? = newByAt[at]?.table

    fun existed(table: Table): Boolean = table.origin in oldByOrigin

    private fun placed(side: Side) =
        side.model.schemas.flatMap { s -> s.tables.map { Placed(s.schemaName, it) } }
}

/**
 * Which field chains a migration narrows, from the evolution differ: a tightened refinement, an
 * enum value removed or renamed, a union member removed. A loosened refinement, an enum value or
 * union member added widen instead. A field made nullable widens only its presence check, the one
 * that lets its columns be NULL together; every other check on its path still holds whatever the
 * field's type now is. A retyped field ([retypes]) widens nothing. Fields are keyed by their record
 * and ordinal, so an embedded record's field is found under whichever table embeds it.
 */
internal class Tightening(old: Schema, new: Schema) {
    private val tightFields = mutableSetOf<Pair<QualifiedName, Int>>()
    private val tightDecls = mutableSetOf<QualifiedName>()
    private val looseFields = mutableSetOf<Pair<QualifiedName, Int>>()
    private val looseDecls = mutableSetOf<QualifiedName>()
    private val nullableFields = mutableSetOf<Pair<QualifiedName, Int>>()
    private val retypedFields = mutableSetOf<Pair<QualifiedName, Int>>()

    init {
        Differ.diff(old, new).forEach { change ->
            when (change) {
                is FieldRefinementChanged ->
                    (if (change.tightened) tightFields else looseFields) +=
                        change.record.qualifiedName to change.to.ordinal
                is FieldNullabilityChanged ->
                    if (change.to.nullable)
                        nullableFields += change.record.qualifiedName to change.to.ordinal
                is FieldTypeChanged ->
                    retypedFields += change.record.qualifiedName to change.to.ordinal
                is EnumValueRemoved -> tightDecls += change.enum.qualifiedName
                is EnumValueRenamed -> tightDecls += change.enum.qualifiedName
                is UnionMemberRemoved -> tightDecls += change.union.qualifiedName
                is EnumValueAdded -> looseDecls += change.enum.qualifiedName
                is UnionMemberAdded -> looseDecls += change.union.qualifiedName
                else -> {}
            }
        }
    }

    fun tightens(chain: Chain): Boolean = touches(chain, tightFields, tightDecls)

    /** [presence] for a check that only says which columns are NULL together. */
    fun loosens(chain: Chain, presence: Boolean): Boolean =
        touches(chain, looseFields, looseDecls) ||
            (presence && touches(chain, nullableFields, emptySet()))

    fun retypes(chain: Chain): Boolean = touches(chain, retypedFields, emptySet())

    private fun touches(
        chain: Chain,
        fields: Set<Pair<QualifiedName, Int>>,
        decls: Set<QualifiedName>,
    ): Boolean =
        chain.fields.any { (record, field) -> (record.qualifiedName to field.ordinal) in fields } ||
            chain.named.any { it in decls }
}

/** Subjects for a side's tables and columns: the IR path and the declaring name's span. */
internal fun tableSubject(side: Side, table: Table) =
    Subject(Labels.table(side.schema, table.origin), table.span)

internal fun columnSubject(side: Side, table: Table, column: Column) =
    Subject(Labels.column(side.schema, table.origin, column.origin), column.span)

/** The first of [columns] the table has, else the table itself. */
internal fun subjectOf(side: Side, table: Table, columns: List<String>): Subject =
    columns
        .firstNotNullOfOrNull { name -> table.columns.firstOrNull { it.name == name } }
        ?.let { columnSubject(side, table, it) } ?: tableSubject(side, table)
