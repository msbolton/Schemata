package io.schemata.migrate

import io.schemata.core.ir.QualifiedName
import io.schemata.core.ir.Schema
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

    fun steps(): List<Step> {
        val (schemaSteps, dropSchema) = schemas()
        val (adds, queuedNotNull) = adds()
        val constraints =
            Constraints(context, schemaName, earlyColumns)
                .plan(matched, added, old?.foreignKeys.orEmpty(), new?.foreignKeys.orEmpty())
        val earlyColumnDrops =
            matched.flatMap { p ->
                constraints.early[p.old.origin].orEmpty() +
                    columnDrops(p, early = true, At(schemaName, p.old.name))
            }
        // Table renames precede creates, so a created table may take a renamed table's old name.
        // Constraint and index drops precede type changes, since Postgres re-checks every check and
        // foreign key on a column whose type changes.
        return schemaSteps +
            tableDrops(early = true) +
            earlyColumnDrops +
            tableRenames() +
            added.map { CreateTable(schemaName, it, tableSubject(newSide, it)) } +
            columnRenames() +
            adds +
            constraints.drops +
            constraints.indexDrops +
            types() +
            defaults() +
            nullability() +
            queuedNotNull +
            constraints.renames +
            constraints.indexRenames +
            constraints.adds +
            constraints.foreignKeyAdds +
            constraints.indexAdds +
            matched.flatMap { columnDrops(it, early = false, it.at) } +
            tableDrops(early = false) +
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

    private fun columnDrops(p: Pairing, early: Boolean, at: At): List<Step> =
        p.dropped
            .filter { (it.name in earlyColumns[p.old.origin].orEmpty()) == early }
            .map { c ->
                DropColumn(
                    at,
                    c.name,
                    columnSubject(oldSide, p.old, c),
                    "every value the column holds",
                    destructiveHelp(p.old.origin, c.origin),
                )
            }

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
