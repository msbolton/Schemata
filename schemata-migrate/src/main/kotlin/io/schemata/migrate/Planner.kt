package io.schemata.migrate

import io.schemata.core.ir.EnumType
import io.schemata.core.ir.QualifiedName
import io.schemata.core.ir.Schema
import io.schemata.target.sql.Column
import io.schemata.target.sql.ColumnOrigin
import io.schemata.target.sql.ColumnType
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
        val plans =
            (oldByPath.keys + newByPath.keys).sorted().map { path ->
                path to NamespacePlan(context, path, oldByPath[path], newByPath[path])
            }
        // A foreign key between two files is dropped by the earlier one, which runs first.
        val moved = plans.flatMap { (_, plan) -> plan.constraints.movedKeyDrops }
        val namespaces =
            plans.mapNotNull { (path, plan) ->
                val steps = plan.steps(moved.filter { it.first == path }.map { it.second })
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

/** The steps for one namespace present on either side or both, in the order they apply. */
private class NamespacePlan(
    private val context: Context,
    path: String,
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

    /**
     * A field renumbered under the same name is a removal and an addition that share a name, so the
     * dropped column or table whose name a new or renamed one takes goes first, ahead of every
     * rename and add; a table only collides while it stays in the same schema.
     */
    private val earlyTables: Set<TableOrigin> = run {
        val taken =
            added.map { it.name } +
                matched.filter { it.old.name != it.new.name }.map { it.new.name }
        dropped
            .filter { old!!.schemaName == schemaName && it.name in taken }
            .map { it.origin }
            .toSet()
    }

    private val earlyColumns: Map<TableOrigin, Set<String>> =
        matched.associate { p ->
            val taken =
                p.added.map { it.name } +
                    p.changed.filter { (o, n) -> o.name != n.name }.map { it.second.name }
            p.old.origin to p.dropped.map { it.name }.filter { it in taken }.toSet()
        }

    val constraints: ConstraintSteps =
        Constraints(context, path, schemaName, earlyColumns)
            .plan(matched, added, old?.foreignKeys.orEmpty(), new?.foreignKeys.orEmpty())

    /**
     * Every step, with [movedKeyDrops] (foreign keys another file lists whose drop this earlier
     * file makes) among the constraint drops.
     */
    fun steps(movedKeyDrops: List<Step>): List<Step> {
        val (schemaSteps, dropSchema) = schemas()
        val (adds, queuedNotNull, lateNotNull) = adds()
        val earlyColumnDrops =
            matched.flatMap { p ->
                constraints.early[p.old.origin].orEmpty() +
                    columnDrops(p, early = true, At(schemaName, p.old.name))
            }
        // Table renames precede creates, so a created table may take a renamed table's old name;
        // the primary keys, uniques, and indexes share the schema's relation names with it, so
        // they are renamed (and any whose name is about to be taken dropped) before it too.
        // Constraint and index drops precede type changes, since Postgres re-checks every check and
        // foreign key on a column whose type changes.
        return schemaSteps +
            tableDrops(early = true) +
            earlyColumnDrops +
            tableRenames() +
            constraints.relationDrops +
            constraints.relationRenames +
            added.map { CreateTable(schemaName, it, tableSubject(newSide, it)) } +
            columnRenames() +
            adds +
            movedKeyDrops +
            constraints.drops +
            constraints.indexDrops +
            types() +
            defaults() +
            valueRenames() +
            nullability() +
            queuedNotNull +
            constraints.renames +
            constraints.adds +
            constraints.foreignKeyAdds +
            constraints.indexAdds +
            matched.flatMap { columnDrops(it, early = false, it.at) } +
            tableDrops(early = false) +
            lateNotNull +
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

    private fun tableRenames(): List<Step> =
        ordered(
            matched
                .filter { it.old.name != it.new.name }
                .map { p ->
                    val subject = tableSubject(newSide, p.new)
                    Rename(schemaName, p.old.name, p.new.name) { from, to ->
                        RenameTable(At(schemaName, from), to, subject)
                    }
                }
        )

    private fun columnRenames(): List<Step> =
        ordered(
            matched.flatMap { p ->
                p.changed
                    .filter { (o, n) -> o.name != n.name }
                    .map { (o, n) ->
                        val subject = columnSubject(newSide, p.new, n)
                        Rename(p.at, o.name, n.name) { from, to ->
                            RenameColumn(p.at, from, to, subject)
                        }
                    }
            }
        )

    /**
     * A column that is required and has no default cannot be added as such to a table that has
     * rows, so it is added nullable and made NOT NULL afterwards, a step that fails unless the
     * table is empty or the rows are filled in first. When the column is the new shape of a field
     * whose old shape is dropped (a strategy change), or a moved key's new copy, its NOT NULL waits
     * until after the drops and the keys, so the data can be moved or filled before it can fail.
     */
    private fun adds(): Triple<List<Step>, List<Step>, List<Step>> {
        val adds = mutableListOf<Step>()
        val queued = mutableListOf<Step>()
        val late = mutableListOf<Step>()
        matched.forEach { p ->
            p.added.forEach { c ->
                val subject = columnSubject(newSide, p.new, c)
                if (!c.nullable && c.default == null) {
                    adds += AddColumn(p.at, c.copy(nullable = true), subject)
                    val notNull = mayFailNotNull(p.at, c.name, subject)
                    val rekey = p.rekeyed.values.any { into -> c in into }
                    if (rekey || reshaped(p, c)) late += notNull else queued += notNull
                } else adds += AddColumn(p.at, c, subject)
            }
        }
        return Triple(adds, queued, late)
    }

    /** Whether [column] replaces a dropped shape of the same field, a column or a child table. */
    private fun reshaped(p: Pairing, column: Column): Boolean {
        val to = chainOf(p.new.origin, column.origin) ?: return false
        val from =
            p.dropped.mapNotNull { chainOf(p.old.origin, it.origin) } +
                dropped.map { chainOf(it.origin, null)!! }
        return from.any { related(it, to) }
    }

    private fun related(
        a: Pair<QualifiedName, List<OriginStep>>,
        b: Pair<QualifiedName, List<OriginStep>>,
    ) = a.first == b.first && (a.second.startsWith(b.second) || b.second.startsWith(a.second))

    /**
     * A renamed enum value is rewritten in every column that stores it, after the old check is
     * dropped and before the new one is added, so the new check finds only its own names.
     */
    private fun valueRenames(): List<Step> =
        matched.flatMap { p ->
            p.changed.flatMap { (o, n) ->
                val enum = storedEnum(newSide, p.new, n) ?: return@flatMap emptyList()
                if (storedEnum(oldSide, p.old, o) != enum) return@flatMap emptyList()
                val renames = context.tightening.renamedValues(enum)
                if (renames.isEmpty()) return@flatMap emptyList()
                val subject = columnSubject(newSide, p.new, n)
                listOf(RenameValue(p.at, n.name, renames, n.type is ColumnType.ARRAY, subject))
            }
        }

    /**
     * The enum whose names [column] stores, as a text column or an array of them: a field or union
     * member that references the enum, or a child table's `value`.
     */
    private fun storedEnum(side: Side, table: Table, column: Column): QualifiedName? {
        val stores =
            when (val origin = column.origin) {
                is ColumnOrigin.FieldPath -> origin.part == null
                is ColumnOrigin.Role -> origin.role == "value"
            }
        if (!stores) return null
        val type = column.type
        if (type != ColumnType.TEXT && type != ColumnType.ARRAY(ColumnType.TEXT)) return null
        val last = Labels.chain(side.schema, table.origin, column.origin).last ?: return null
        return last.takeIf { side.schema.lookupOrNull(it) is EnumType }
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

    /**
     * A type change is clean when every value survives it, and loses the rest otherwise. Postgres
     * recasts a column's stored default by assignment, not by the `USING` expression, and rejects
     * the change when no such cast exists (`'3'` to integer), so a retyped column's default is
     * dropped first and NEW's is set after.
     */
    private fun types(): List<Step> =
        matched.flatMap { p ->
            p.changed
                .filter { (o, n) -> o.type != n.type }
                .flatMap { (o, n) ->
                    val subject = columnSubject(newSide, p.new, n)
                    val lossless = Widening.lossless(o.type, n.type)
                    listOfNotNull(
                        o.default?.let { DropDefault(p.at, n.name, subject) },
                        AlterColumnType(
                            p.at,
                            n.name,
                            n.type,
                            subject,
                            if (lossless) Risk.CLEAN else Risk.DESTRUCTIVE,
                            if (lossless) null
                            else
                                "values that do not fit ${Ddl.spell(n.type)} (the cast fails or truncates)",
                            if (lossless) null else DESTRUCTIVE_HELP,
                        ),
                        n.default?.let { SetDefault(p.at, n.name, it, subject) },
                    )
                }
        }

    /** A retyped column's default is already settled by [types]. */
    private fun defaults(): List<Step> =
        matched.flatMap { p ->
            p.changed
                .filter { (o, n) -> o.type == n.type && o.default != n.default }
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

    private fun columnDrops(p: Pairing, early: Boolean, at: At): List<Step> =
        p.dropped
            .filter { (it.name in earlyColumns[p.old.origin].orEmpty()) == early }
            .map { c ->
                DropColumn(
                    at,
                    c.name,
                    columnSubject(oldSide, p.old, c),
                    "every value the column holds",
                    p.rekeyed[c]?.let { rekeyHelp(p, it) }
                        ?: if (early || c in p.renewed) DESTRUCTIVE_HELP
                        else destructiveHelp(p.old.origin, c.origin),
                )
            }

    /**
     * A key moved to other fields leaves the columns that copied it holding the old key's values,
     * so they go and the copies of the new key take their place, empty until filled from the
     * parent.
     */
    private fun rekeyHelp(p: Pairing, into: List<Column>): String =
        "populate ${into.joinToString(", ") { "${Naming.quote(p.new.name)}.${Naming.quote(it.name)}" }} from the parent before the foreign keys return, then rerun with --allow-destructive"

    /** Child tables first, though `CASCADE` would take them with their parent anyway. */
    private fun tableDrops(early: Boolean): List<Step> =
        dropped
            .filter { (it.origin in earlyTables) == early }
            .sortedWith(compareByDescending<Table> { it.origin.path.size }.thenBy { it.name })
            .map {
                DropTable(
                    At(old!!.schemaName, it.name),
                    tableSubject(oldSide, it),
                    "every row of the table",
                    if (early || context.renewed(it)) DESTRUCTIVE_HELP
                    else destructiveHelp(it.origin, null),
                )
            }

    /**
     * A field whose shape changed (a list moved between an array column and a child table, an
     * embedded record turned into a json column) drops one shape and creates the other, so the
     * drop's help names where its data belongs. A drop that must precede the create, because the
     * new shape takes its name, leaves no statement between the two, and a column or table whose
     * field now refers to another declaration has no counterpart to move into; both get the plain
     * help. Shapes are related when one's field chain is a prefix of the other's: `billing_street`
     * and `billing_city` both moved into `billing`.
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
        val into = targets.filter { (to, _) -> related(to, from) }.map { it.second }.distinct()
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
