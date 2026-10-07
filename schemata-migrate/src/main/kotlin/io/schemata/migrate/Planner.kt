package io.schemata.migrate

import io.schemata.core.ir.QualifiedName
import io.schemata.core.ir.Schema
import io.schemata.evolution.Differ
import io.schemata.evolution.EnumValueAdded
import io.schemata.evolution.EnumValueRemoved
import io.schemata.evolution.EnumValueRenamed
import io.schemata.evolution.FieldNullabilityChanged
import io.schemata.evolution.FieldRefinementChanged
import io.schemata.evolution.UnionMemberAdded
import io.schemata.evolution.UnionMemberRemoved
import io.schemata.target.sql.Column
import io.schemata.target.sql.ColumnOrigin
import io.schemata.target.sql.Ddl
import io.schemata.target.sql.Naming
import io.schemata.target.sql.OriginStep
import io.schemata.target.sql.RelationalModel
import io.schemata.target.sql.RelationalSchema
import io.schemata.target.sql.Table
import io.schemata.target.sql.TableOrigin

/** One side of a migration: the analysed schema and the SQL target's lowering of it. */
data class Side(val schema: Schema, val model: RelationalModel)

/**
 * Diffs two lowerings of a schema into the DDL that turns OLD's database into NEW's. Tables and
 * columns are matched by provenance (the record and field ordinals that produced them), never by
 * name, so a renamed field or table is a rename rather than a drop and an add. Each namespace's
 * steps go to that namespace's own file, so they run in the order the compiled files do.
 */
object Planner {
    fun plan(old: Side, new: Side): Migration {
        val context = Context(old, new)
        val oldByPath = old.model.schemas.associateBy { it.path }
        val newByPath = new.model.schemas.associateBy { it.path }
        val namespaces =
            (oldByPath.keys + newByPath.keys).sorted().mapNotNull { path ->
                val steps = NamespacePlan(context, oldByPath[path], newByPath[path]).steps()
                if (steps.isEmpty()) null
                else
                    NamespaceMigration(
                        path,
                        (newByPath[path] ?: oldByPath.getValue(path)).schemaName,
                        steps,
                    )
            }
        return Migration(namespaces)
    }
}

internal const val DESTRUCTIVE_HELP =
    "rerun with --allow-destructive once the data is migrated or no longer needed"

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
 * enum value removed or renamed, a union member removed. A loosened refinement, a field made
 * nullable, an enum value or union member added widen instead. Fields are keyed by their record and
 * ordinal, so an embedded record's field is found under whichever table embeds it.
 */
internal class Tightening(old: Schema, new: Schema) {
    private val tightFields = mutableSetOf<Pair<QualifiedName, Int>>()
    private val tightDecls = mutableSetOf<QualifiedName>()
    private val looseFields = mutableSetOf<Pair<QualifiedName, Int>>()
    private val looseDecls = mutableSetOf<QualifiedName>()

    init {
        Differ.diff(old, new).forEach { change ->
            when (change) {
                is FieldRefinementChanged ->
                    (if (change.tightened) tightFields else looseFields) +=
                        change.record.qualifiedName to change.to.ordinal
                is FieldNullabilityChanged ->
                    if (change.to.nullable)
                        looseFields += change.record.qualifiedName to change.to.ordinal
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

    fun loosens(chain: Chain): Boolean = touches(chain, looseFields, looseDecls)

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

/** The steps for one namespace present on either side or both, in the order they apply. */
private class NamespacePlan(
    private val context: Context,
    private val old: RelationalSchema?,
    private val new: RelationalSchema?,
) {
    private val schemaName = (new ?: old)!!.schemaName
    private val oldSide = context.old
    private val newSide = context.new
    private val matched: List<Pairing> =
        new?.tables.orEmpty().mapNotNull { context.pairing(it.origin) }
    private val added: List<Table> = new?.tables.orEmpty().filter { !context.existed(it) }
    private val dropped: List<Table> =
        old?.tables.orEmpty().filter { context.pairing(it.origin) == null }

    fun steps(): List<Step> {
        val (schemaSteps, dropSchema) = schemas()
        val (adds, queuedNotNull) = adds()
        val constraints =
            Constraints(context, schemaName)
                .plan(matched, added, old?.foreignKeys.orEmpty(), new?.foreignKeys.orEmpty())
        return schemaSteps +
            added.map { CreateTable(schemaName, it, tableSubject(newSide, it)) } +
            renames() +
            adds +
            types() +
            defaults() +
            nullability() +
            queuedNotNull +
            constraints.drops +
            constraints.indexDrops +
            constraints.renames +
            constraints.indexRenames +
            constraints.adds +
            constraints.foreignKeyAdds +
            constraints.indexAdds +
            columnDrops() +
            tableDrops() +
            comments() +
            dropSchema
    }

    /**
     * A new namespace creates its schema; a removed one drops it last. A namespace whose schema
     * name changed creates the new schema, moves every surviving table into it under its old name
     * (renames follow and address the new schema), and drops the old schema last, after the tables
     * left behind in it are dropped.
     */
    private fun schemas(): Pair<List<Step>, List<Step>> =
        when {
            old == null ->
                listOf(CreateSchema(new!!.schemaName, namespaceSubject(newSide, new))) to
                    emptyList()
            new == null ->
                emptyList<Step>() to
                    listOf(DropSchema(old.schemaName, namespaceSubject(oldSide, old)))
            old.schemaName != new.schemaName ->
                (listOf(CreateSchema(new.schemaName, namespaceSubject(newSide, new))) +
                    matched.map {
                        SetSchema(
                            At(old.schemaName, it.old.name),
                            new.schemaName,
                            tableSubject(newSide, it.new),
                        )
                    }) to listOf(DropSchema(old.schemaName, namespaceSubject(oldSide, old)))
            else -> emptyList<Step>() to emptyList()
        }

    /**
     * The namespace a file belongs to: the SQL target names it `<namespace, dots as slashes>.sql`.
     */
    private fun namespaceSubject(side: Side, schema: RelationalSchema): Subject {
        val namespace =
            side.schema.namespaces.first { it.name.replace('.', '/') + ".sql" == schema.path }
        return Subject(namespace.name, namespace.span)
    }

    private fun renames(): List<Step> =
        matched
            .filter { it.old.name != it.new.name }
            .map {
                RenameTable(At(schemaName, it.old.name), it.new.name, tableSubject(newSide, it.new))
            } +
            matched.flatMap { p ->
                p.changed
                    .filter { (o, n) -> o.name != n.name }
                    .map { (o, n) ->
                        RenameColumn(p.at, o.name, n.name, columnSubject(newSide, p.new, n))
                    }
            }

    /**
     * A column that is required and has no default cannot be added as such to a table that has
     * rows, so it is added nullable and made NOT NULL afterwards, a step that fails unless the
     * table is empty or the rows are filled in first.
     */
    private fun adds(): Pair<List<Step>, List<Step>> {
        val adds = mutableListOf<Step>()
        val queued = mutableListOf<Step>()
        matched.forEach { p ->
            p.added.forEach { c ->
                val subject = columnSubject(newSide, p.new, c)
                if (!c.nullable && c.default == null) {
                    adds += AddColumn(p.at, c.copy(nullable = true), subject)
                    queued += mayFailNotNull(p.at, c.name, subject)
                } else adds += AddColumn(p.at, c, subject)
            }
        }
        return adds to queued
    }

    private fun mayFailNotNull(at: At, column: String, subject: Subject): Step {
        val table = "${Naming.quote(at.schema)}.${Naming.quote(at.table)}"
        val col = Naming.quote(column)
        return SetNotNull(
            at,
            column,
            subject,
            Risk.MAY_FAIL,
            "a row holds NULL",
            "run UPDATE $table SET $col = … WHERE $col IS NULL before applying",
        )
    }

    /** A type change is clean when every value survives it, and loses the rest otherwise. */
    private fun types(): List<Step> =
        matched.flatMap { p ->
            p.changed
                .filter { (o, n) -> o.type != n.type }
                .map { (o, n) ->
                    val lossless = Widening.lossless(o.type, n.type)
                    AlterColumnType(
                        p.at,
                        n.name,
                        n.type,
                        columnSubject(newSide, p.new, n),
                        if (lossless) Risk.CLEAN else Risk.DESTRUCTIVE,
                        if (lossless) null else "values that do not fit ${Ddl.spell(n.type)}",
                        if (lossless) null else DESTRUCTIVE_HELP,
                    )
                }
        }

    private fun defaults(): List<Step> =
        matched.flatMap { p ->
            p.changed
                .filter { (o, n) -> o.default != n.default }
                .map { (_, n) ->
                    val subject = columnSubject(newSide, p.new, n)
                    n.default?.let { SetDefault(p.at, n.name, it, subject) }
                        ?: DropDefault(p.at, n.name, subject)
                }
        }

    /** NOT NULL with a default to fill the NULLs with backfills them first and cannot fail. */
    private fun nullability(): List<Step> =
        matched.flatMap { p ->
            p.changed.flatMap { (o, n) ->
                val subject = columnSubject(newSide, p.new, n)
                when {
                    o.nullable && !n.nullable ->
                        n.default?.let {
                            listOf(
                                Backfill(p.at, n.name, it, subject),
                                SetNotNull(p.at, n.name, subject, Risk.CLEAN, null, null),
                            )
                        } ?: listOf(mayFailNotNull(p.at, n.name, subject))
                    !o.nullable && n.nullable -> listOf(DropNotNull(p.at, n.name, subject))
                    else -> emptyList()
                }
            }
        }

    private fun columnDrops(): List<Step> =
        matched.flatMap { p ->
            p.dropped.map { c ->
                DropColumn(
                    p.at,
                    c.name,
                    columnSubject(oldSide, p.old, c),
                    "every value the column holds",
                    destructiveHelp(p.old.origin, c.origin),
                )
            }
        }

    /** Child tables first, though `CASCADE` would take them with their parent anyway. */
    private fun tableDrops(): List<Step> =
        dropped
            .sortedWith(compareByDescending<Table> { it.origin.path.size }.thenBy { it.name })
            .map {
                DropTable(
                    At(old!!.schemaName, it.name),
                    tableSubject(oldSide, it),
                    "every row of the table",
                    destructiveHelp(it.origin, null),
                )
            }

    /**
     * A field whose shape changed (a list moved between an array column and a child table, an
     * embedded record turned into a json column) drops one shape and creates the other, so the
     * drop's help names where its data belongs. Shapes are related when one's field chain is a
     * prefix of the other's: `billing_street` and `billing_city` both moved into `billing`.
     */
    private fun destructiveHelp(table: TableOrigin, column: ColumnOrigin?): String {
        val from = chainOf(table, column) ?: return DESTRUCTIVE_HELP
        val targets =
            added.map { chainOf(it.origin, null)!! to Naming.quote(it.name) } +
                matched.flatMap { p ->
                    p.added.mapNotNull { c ->
                        chainOf(p.new.origin, c.origin)?.let {
                            it to "${Naming.quote(p.new.name)}.${Naming.quote(c.name)}"
                        }
                    }
                }
        val into =
            targets
                .filter { (to, _) ->
                    to.first == from.first &&
                        (to.second.startsWith(from.second) || from.second.startsWith(to.second))
                }
                .map { it.second }
                .distinct()
        if (into.isEmpty()) return DESTRUCTIVE_HELP
        return "move the data into ${into.joinToString(", ")} between this statement and the one that creates it, then rerun with --allow-destructive"
    }

    /** A table's or a field column's record and full field chain; null for a role column. */
    private fun chainOf(
        table: TableOrigin,
        column: ColumnOrigin?,
    ): Pair<QualifiedName, List<OriginStep>>? =
        when (column) {
            null -> table.record to table.path
            is ColumnOrigin.FieldPath -> table.record to table.path + column.path
            is ColumnOrigin.Role -> null
        }

    private fun <T> List<T>.startsWith(prefix: List<T>) =
        size >= prefix.size && subList(0, prefix.size) == prefix

    /**
     * `CREATE TABLE` carries no comments, so a created table is commented as compile would comment
     * it; a surviving table or column is recommented when its doc changed, and a removed doc
     * comments NULL.
     */
    private fun comments(): List<Step> =
        new?.tables.orEmpty().flatMap { table ->
            val p = context.pairing(table.origin)
            val at = At(schemaName, table.name)
            if (p == null) {
                listOfNotNull(
                    table.doc?.let { Comment(at, null, it, tableSubject(newSide, table)) }
                ) +
                    table.columns.mapNotNull { c ->
                        c.doc?.let { Comment(at, c.name, it, columnSubject(newSide, table, c)) }
                    }
            } else {
                listOfNotNull(
                    if (p.old.doc != table.doc)
                        Comment(at, null, table.doc, tableSubject(newSide, table))
                    else null
                ) +
                    table.columns.mapNotNull { c ->
                        val before = p.changed.firstOrNull { it.second === c }?.first
                        val changed = if (before == null) c.doc != null else before.doc != c.doc
                        if (changed) Comment(at, c.name, c.doc, columnSubject(newSide, table, c))
                        else null
                    }
            }
        }
}
