package io.schemata.migrate

import io.schemata.core.ir.QualifiedName
import io.schemata.core.ir.RecordType
import io.schemata.core.ir.Schema
import io.schemata.core.ir.Type
import io.schemata.core.ir.UnionType
import io.schemata.evolution.Differ
import io.schemata.evolution.EnumValueAdded
import io.schemata.evolution.EnumValueRemoved
import io.schemata.evolution.EnumValueRenamed
import io.schemata.evolution.FieldNullabilityChanged
import io.schemata.evolution.FieldRefinementChanged
import io.schemata.evolution.FieldTypeChanged
import io.schemata.evolution.UnionMemberAdded
import io.schemata.evolution.UnionMemberRemoved
import io.schemata.evolution.UnionMemberTypeChanged
import io.schemata.target.sql.Column
import io.schemata.target.sql.ColumnOrigin
import io.schemata.target.sql.Table
import io.schemata.target.sql.TableOrigin

/** A table and the schema it lives in, on one side. */
internal data class Placed(val schema: String, val table: Table)

/**
 * A table both sides have, with its columns matched by origin. A column whose field chain passes
 * through a field or union member that now references another declaration ([renewed] on its own
 * side) matches nothing: it is dropped and its counterpart added.
 */
internal class Pairing(
    val old: Table,
    val newSchema: String,
    val new: Table,
    renewed: (Side, Table, Column) -> Boolean,
    oldSide: Side,
    newSide: Side,
) {
    private val oldByOrigin =
        old.columns.filter { !renewed(oldSide, old, it) }.associateBy { it.origin }
    private val newByOrigin =
        new.columns.filter { !renewed(newSide, new, it) }.associateBy { it.origin }

    /** Old and new column for every column both have, in NEW's order. */
    val changed: List<Pair<Column, Column>> =
        new.columns.mapNotNull { n ->
            if (newByOrigin[n.origin] !== n) null else oldByOrigin[n.origin]?.let { it to n }
        }
    val added: List<Column> = new.columns.filter { n -> changed.none { it.second === n } }
    val dropped: List<Column> = old.columns.filter { o -> changed.none { it.first === o } }

    /**
     * Each dropped column that copied a key (a reference's `k<ordinal>` or a child's
     * `parent:<id>`), with the added columns copying the new key in its place: a moved key is a new
     * column, which must be filled from the parent before the foreign key returns.
     */
    val rekeyed: Map<Column, List<Column>> =
        dropped
            .mapNotNull { o ->
                val base = keyCopy(o.origin) ?: return@mapNotNull null
                val into = added.filter { keyCopy(it.origin) == base }
                if (into.isEmpty()) null else o to into
            }
            .toMap()

    /** Old column name to new, for every column both have; a dropped column has no entry. */
    val names: Map<String, String> = changed.associate { (o, n) -> o.name to n.name }

    /** The OLD names of the columns whose type changes. */
    val retyped: Set<String> =
        changed.filter { (o, n) -> o.type != n.type }.map { it.first.name }.toSet()

    /** Where every step after the schema move and the renames addresses the table. */
    val at = At(newSchema, new.name)
}

/**
 * What a key-copying column copies a key for: the reference's field chain, or the parent; null for
 * any other column.
 */
private val KEY_PART = Regex("k\\d+")

private fun keyCopy(origin: ColumnOrigin): Any? =
    when (origin) {
        is ColumnOrigin.FieldPath ->
            if (origin.part?.matches(KEY_PART) == true) origin.path else null
        is ColumnOrigin.Role -> if (origin.role.startsWith("parent:")) "parent" else null
    }

/**
 * What every namespace's plan shares: both sides' tables by origin and by address. A child table
 * whose field chain passes through a field or member that now references another declaration is a
 * new identity on each side, so it pairs with nothing.
 */
internal class Context(val old: Side, val new: Side) {
    val tightening = Tightening(old.schema, new.schema)

    private val oldByOrigin =
        placed(old).filter { !renewed(old, it.table) }.associateBy { it.table.origin }
    private val newByOrigin =
        placed(new).filter { !renewed(new, it.table) }.associateBy { it.table.origin }
    private val oldByAt = placed(old).associateBy { At(it.schema, it.table.name) }
    private val newByAt = placed(new).associateBy { At(it.schema, it.table.name) }
    private val pairings = mutableMapOf<TableOrigin, Pairing?>()

    fun pairing(origin: TableOrigin): Pairing? =
        pairings.getOrPut(origin) {
            val o = oldByOrigin[origin] ?: return@getOrPut null
            val n = newByOrigin[origin] ?: return@getOrPut null
            Pairing(o.table, n.schema, n.table, ::renewed, old, new)
        }

    private fun renewed(side: Side, table: Table): Boolean =
        tightening.renews(Labels.table(side.schema, table.origin.record, table.origin.path))

    private fun renewed(side: Side, table: Table, column: Column): Boolean =
        tightening.renews(Labels.chain(side.schema, table.origin, column.origin))

    fun oldTable(at: At): Table? = oldByAt[at]?.table

    fun newTable(at: At): Table? = newByAt[at]?.table

    fun existed(table: Table): Boolean = table.origin in oldByOrigin

    private fun placed(side: Side) =
        side.model.schemas.flatMap { s -> s.tables.map { Placed(s.schemaName, it) } }
}

/**
 * Which field chains a migration narrows, from the evolution differ: a tightened refinement, an
 * enum value removed, a union member removed. A loosened refinement, an enum value or union member
 * added widen instead, and so does an enum value renamed, since the migration rewrites the stored
 * value before the check returns ([renamedValues]). A field made nullable widens only its presence
 * check, the one that lets its columns be NULL together; every other check on its path still holds
 * whatever the field's type now is. A retyped field ([retypes]) widens nothing. A field that now
 * references another record or union, or a union member whose type changed, [renews] every column
 * and child table under it. Fields are keyed by their record and ordinal, so an embedded record's
 * field is found under whichever table embeds it.
 */
internal class Tightening(old: Schema, new: Schema) {
    private val tightFields = mutableSetOf<Pair<QualifiedName, Int>>()
    private val tightDecls = mutableSetOf<QualifiedName>()
    private val looseFields = mutableSetOf<Pair<QualifiedName, Int>>()
    private val looseDecls = mutableSetOf<QualifiedName>()
    private val nullableFields = mutableSetOf<Pair<QualifiedName, Int>>()
    private val retypedFields = mutableSetOf<Pair<QualifiedName, Int>>()
    private val retargetedFields = mutableSetOf<Pair<QualifiedName, Int>>()
    private val retypedMembers = mutableSetOf<Pair<QualifiedName, Int>>()
    private val renamed = mutableMapOf<QualifiedName, MutableList<Pair<String, String>>>()

    init {
        Differ.diff(old, new).forEach { change ->
            when (change) {
                is FieldRefinementChanged ->
                    (if (change.tightened) tightFields else looseFields) +=
                        change.record.qualifiedName to change.to.ordinal
                is FieldNullabilityChanged ->
                    if (change.to.nullable)
                        nullableFields += change.record.qualifiedName to change.to.ordinal
                is FieldTypeChanged -> {
                    retypedFields += change.record.qualifiedName to change.to.ordinal
                    if (retargets(old, change.from.type, new, change.to.type))
                        retargetedFields += change.record.qualifiedName to change.to.ordinal
                }
                is UnionMemberTypeChanged ->
                    retypedMembers += change.union.qualifiedName to change.to.ordinal
                is EnumValueRemoved -> tightDecls += change.enum.qualifiedName
                is EnumValueRenamed -> {
                    looseDecls += change.enum.qualifiedName
                    renamed.getOrPut(change.enum.qualifiedName) { mutableListOf() } +=
                        change.from.name to change.to.name
                }
                is UnionMemberRemoved -> tightDecls += change.union.qualifiedName
                is EnumValueAdded -> looseDecls += change.enum.qualifiedName
                is UnionMemberAdded -> looseDecls += change.union.qualifiedName
                else -> {}
            }
        }
    }

    /** Every value of [enum] stored under an OLD name that NEW spells differently, old to new. */
    fun renamedValues(enum: QualifiedName): List<Pair<String, String>> = renamed[enum].orEmpty()

    /** True when [chain] passes through a field or member whose referenced declaration changed. */
    fun renews(chain: Chain): Boolean =
        chain.fields.any { (record, field) ->
            (record.qualifiedName to field.ordinal) in retargetedFields
        } ||
            chain.members.any { (union, member) ->
                (union.qualifiedName to member.ordinal) in retypedMembers
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

/**
 * Whether a field's type change moves it to another record or union: either side references one
 * (directly, as a list element, or as a map value) and the two sides do not reference the same
 * declaration. An enum swapped for another, or a scalar for an enum, keeps its text column.
 */
private fun retargets(old: Schema, from: Type, new: Schema, to: Type): Boolean {
    val before = Labels.referenced(from)
    val after = Labels.referenced(to)
    if (before == after) return false
    fun structured(schema: Schema, name: QualifiedName?) =
        name != null && schema.lookupOrNull(name).let { it is RecordType || it is UnionType }
    return structured(old, before) || structured(new, after)
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
