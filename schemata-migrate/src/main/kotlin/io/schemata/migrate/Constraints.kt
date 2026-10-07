package io.schemata.migrate

import io.schemata.target.sql.ForeignKey
import io.schemata.target.sql.Naming
import io.schemata.target.sql.Table
import io.schemata.target.sql.TableOrigin

/**
 * The constraint and index steps of one namespace, by the phase each runs in. [early] holds, per
 * table, the drops of constraints and indexes naming a column that is dropped ahead of the renames
 * and adds because a new column takes its name.
 */
internal class ConstraintSteps(
    val early: Map<TableOrigin, List<Step>>,
    val drops: List<Step>,
    val indexDrops: List<Step>,
    val renames: List<Step>,
    val indexRenames: List<Step>,
    val adds: List<Step>,
    val foreignKeyAdds: List<Step>,
    val indexAdds: List<Step>,
)

/**
 * Matches each surviving table's constraints and indexes across the two sides. An OLD constraint's
 * definition is carried through the column and table renames; the NEW constraint with the same
 * carried definition is its counterpart, renamed when the names differ. An OLD constraint with no
 * counterpart (its definition changed, or it names a dropped column) is dropped, and a NEW one with
 * none is added. A created table's own keys and checks are part of its `CREATE TABLE`; only its
 * indexes and foreign keys are added here.
 */
internal class Constraints(
    private val context: Context,
    private val schemaName: String,
    private val earlyColumns: Map<TableOrigin, Set<String>>,
) {
    private val early = mutableMapOf<TableOrigin, MutableList<Step>>()
    private val drops = mutableListOf<Step>()
    private val keyDrops = mutableListOf<Step>()
    private val indexDrops = mutableListOf<Step>()
    private val renames = mutableListOf<Rename>()
    private val indexRenames = mutableListOf<Rename>()
    private val adds = mutableListOf<Step>()
    private val keyAdds = mutableListOf<Step>()
    private val indexAdds = mutableListOf<Step>()

    fun plan(
        matched: List<Pairing>,
        added: List<Table>,
        oldKeys: List<ForeignKey>,
        newKeys: List<ForeignKey>,
    ): ConstraintSteps {
        foreignKeys(oldKeys, newKeys)
        matched.forEach { table(it) }
        added.forEach { t ->
            t.indexes.forEach {
                indexAdds += CreateIndex(At(schemaName, t.name), it, tableSubject(context.new, t))
            }
        }
        // A foreign key may hang off a key or unique dropped below, so it goes first.
        return ConstraintSteps(
            early,
            keyDrops + drops,
            indexDrops,
            ordered(renames),
            ordered(indexRenames),
            adds,
            keyAdds,
            indexAdds,
        )
    }

    private fun table(p: Pairing) {
        val old = context.old
        val new = context.new
        val constraints =
            match(constraintsOf(p.old), constraintsOf(p.new), { it.name }, { carried(it, p) }) {
                definition(it)
            }
        // A column dropped early goes before the table's renames, under the table's old name.
        val gone = earlyColumns[p.old.origin].orEmpty()
        val earlyAt = At(schemaName, p.old.name)
        constraints.dropped.forEach {
            val columns = columnsOf(it, p.old)
            val cascade = it is Constraint.PrimaryKey || it is Constraint.UniqueKey
            val subject = subjectOf(old, p.old, columns)
            if (columns.any { c -> c in gone })
                early.getOrPut(p.old.origin) { mutableListOf() } +=
                    DropConstraint(earlyAt, it.name, false, cascade, subject)
            else drops += DropConstraint(p.at, it.name, false, cascade, subject)
        }
        constraints.renamed.forEach { (o, n) ->
            val subject = subjectOf(new, p.new, columnsOf(n, p.new))
            renames +=
                Rename(p.at, o.name, n.name) { f, t -> RenameConstraint(p.at, f, t, subject) }
        }
        constraints.added.forEach { adds += add(p, it) }

        val indexes =
            match(p.old.indexes, p.new.indexes, { it.name }, { carry(it.columns, p.names) }) {
                it.columns
            }
        indexes.dropped.forEach {
            val drop = DropIndex(schemaName, it.name, subjectOf(old, p.old, it.columns))
            if (it.columns.any { c -> c in gone })
                early.getOrPut(p.old.origin) { mutableListOf() } += drop
            else indexDrops += drop
        }
        indexes.renamed.forEach { (o, n) ->
            val subject = subjectOf(new, p.new, n.columns)
            indexRenames +=
                Rename(schemaName, o.name, n.name) { f, t ->
                    RenameIndex(schemaName, f, t, subject)
                }
        }
        indexes.added.forEach {
            indexAdds += CreateIndex(p.at, it, subjectOf(new, p.new, it.columns))
        }
    }

    /**
     * A foreign key is planned in the file that lists it, after both tables it links exist. One
     * whose own table is dropped goes with that table's `DROP TABLE … CASCADE`; every foreign key
     * drop is `IF EXISTS`, since a `CASCADE` in an earlier file may already have taken it.
     */
    private fun foreignKeys(oldKeys: List<ForeignKey>, newKeys: List<ForeignKey>) {
        val surviving = oldKeys.filter { source(it) != null }
        val keys = match(surviving, newKeys, { it.name }, { carried(it) }) { it.copy(name = "") }
        keys.dropped.forEach { fk ->
            val p = source(fk)!!
            keyDrops +=
                DropConstraint(
                    p.at,
                    fk.name,
                    ifExists = true,
                    cascade = false,
                    subjectOf(context.old, p.old, fk.columns),
                )
        }
        keys.renamed.forEach { (o, n) ->
            val at = At(n.schema, n.table)
            val subject = subjectOf(context.new, context.newTable(at)!!, n.columns)
            renames += Rename(at, o.name, n.name) { f, t -> RenameConstraint(at, f, t, subject) }
        }
        keys.added.forEach { fk ->
            val at = At(fk.schema, fk.table)
            val table = context.newTable(at)!!
            val p = context.pairing(table.origin)
            val constraint = Constraint.Foreign(fk)
            keyAdds +=
                if (p == null)
                    AddConstraint(
                        at,
                        constraint,
                        tableSubject(context.new, table),
                        Risk.CLEAN,
                        null,
                        null,
                    )
                else if (fk.columns.any { it in nullAdded(p) })
                    AddConstraint(
                        at,
                        constraint,
                        subjectOf(context.new, table, fk.columns),
                        Risk.CLEAN,
                        null,
                        null,
                    )
                else
                    AddConstraint(
                        at,
                        constraint,
                        subjectOf(context.new, table, fk.columns),
                        Risk.MAY_FAIL,
                        "a row references a missing parent",
                        "fix or delete the rows whose parent is missing before applying",
                    )
        }
    }

    /** The surviving table an OLD foreign key hangs off, or null when that table is dropped. */
    private fun source(fk: ForeignKey): Pairing? =
        context.oldTable(At(fk.schema, fk.table))?.let { context.pairing(it.origin) }

    /** [fk] as NEW would spell it, or null when either table or any column it names is gone. */
    private fun carried(fk: ForeignKey): ForeignKey? {
        val source = source(fk) ?: return null
        val target =
            context.oldTable(At(fk.targetSchema, fk.targetTable))?.let {
                context.pairing(it.origin)
            } ?: return null
        return fk.copy(
            name = "",
            schema = source.newSchema,
            table = source.new.name,
            columns = carry(fk.columns, source.names) ?: return null,
            targetSchema = target.newSchema,
            targetTable = target.new.name,
            targetColumns = carry(fk.targetColumns, target.names) ?: return null,
        )
    }

    /**
     * A key or unique added to a table with rows can meet duplicates, unless one of its columns was
     * just added without a default: every row holds NULL there, and NULLs never collide. A check
     * can fail unless it names only new columns, whose values are NULL or a default the analyzer
     * checked against the same refinements, or the change it encodes only widens what is allowed.
     */
    private fun add(p: Pairing, constraint: Constraint): Step {
        val columns = columnsOf(constraint, p.new)
        val subject = subjectOf(context.new, p.new, columns)
        val clean = AddConstraint(p.at, constraint, subject, Risk.CLEAN, null, null)
        val duplicates =
            AddConstraint(
                p.at,
                constraint,
                subject,
                Risk.MAY_FAIL,
                "rows duplicate the key",
                "remove duplicate rows before applying",
            )
        return when (constraint) {
            is Constraint.PrimaryKey -> duplicates
            is Constraint.UniqueKey -> if (columns.any { it in nullAdded(p) }) clean else duplicates
            is Constraint.CheckConstraint ->
                if (checkIsClean(p, columns)) clean
                else
                    AddConstraint(
                        p.at,
                        constraint,
                        subject,
                        Risk.MAY_FAIL,
                        "a row violates it",
                        "fix or delete the rows the new constraint rejects before applying",
                    )
            is Constraint.Foreign -> error("foreign keys are planned per file")
        }
    }

    private fun checkIsClean(p: Pairing, columns: List<String>): Boolean {
        val added = p.added.map { it.name }.toSet()
        if (columns.isNotEmpty() && columns.all { it in added }) return true
        val chains =
            columns.mapNotNull { name ->
                p.new.columns
                    .firstOrNull { it.name == name }
                    ?.let { Labels.chain(context.new.schema, p.new.origin, it.origin) }
            }
        val tightening = context.tightening
        return chains.none { tightening.tightens(it) } && chains.any { tightening.loosens(it) }
    }

    /** Columns added in this migration without a default: every existing row holds NULL there. */
    private fun nullAdded(p: Pairing): Set<String> =
        p.added.filter { it.default == null }.map { it.name }.toSet()

    private fun constraintsOf(table: Table): List<Constraint> =
        listOfNotNull(
            table.primaryKeyName
                ?.takeIf { table.primaryKey.isNotEmpty() }
                ?.let { Constraint.PrimaryKey(it, table.primaryKey) }
        ) +
            table.uniques.map { Constraint.UniqueKey(it) } +
            table.checks.map { Constraint.CheckConstraint(it) }

    /**
     * What a constraint enforces, without its name; a kind tag keeps a key from matching a unique.
     */
    private fun definition(constraint: Constraint): Any =
        when (constraint) {
            is Constraint.PrimaryKey -> "primary" to constraint.columns
            is Constraint.UniqueKey -> "unique" to constraint.unique.columns
            is Constraint.CheckConstraint -> "check" to constraint.check.expression
            is Constraint.Foreign -> constraint.fk.copy(name = "")
        }

    /**
     * An OLD constraint's definition as NEW would spell it, or null when it names a dropped column.
     */
    private fun carried(constraint: Constraint, p: Pairing): Any? =
        when (constraint) {
            is Constraint.PrimaryKey -> carry(constraint.columns, p.names)?.let { "primary" to it }
            is Constraint.UniqueKey ->
                carry(constraint.unique.columns, p.names)?.let { "unique" to it }
            is Constraint.CheckConstraint ->
                carry(constraint.check.expression, p)?.let { "check" to it }
            is Constraint.Foreign -> null
        }

    /** The columns a constraint names, in order; a check's are its quoted identifiers. */
    private fun columnsOf(constraint: Constraint, table: Table): List<String> =
        when (constraint) {
            is Constraint.PrimaryKey -> constraint.columns
            is Constraint.UniqueKey -> constraint.unique.columns
            is Constraint.CheckConstraint -> {
                val names = table.columns.map { it.name }.toSet()
                identifiers(constraint.check.expression)
                    .map { it.second }
                    .filter { it in names }
                    .distinct()
            }
            is Constraint.Foreign -> constraint.fk.columns
        }

    private fun carry(columns: List<String>, names: Map<String, String>): List<String>? =
        columns.map { names[it] ?: return null }

    /** A check's expression with every OLD column it quotes renamed, or null if one was dropped. */
    private fun carry(expression: String, p: Pairing): String? {
        val columns = p.old.columns.map { it.name }.toSet()
        val out = StringBuilder()
        var last = 0
        identifiers(expression).forEach { (range, name) ->
            out.append(expression, last, range.first)
            out.append(Naming.quote(if (name in columns) p.names[name] ?: return null else name))
            last = range.last + 1
        }
        return out.append(expression.substring(last)).toString()
    }

    private class Matched<T>(
        val dropped: List<T>,
        val renamed: List<Pair<T, T>>,
        val added: List<T>,
    )

    /**
     * Pairs each OLD item with the first unclaimed NEW item whose definition equals the OLD one's
     * [carried] definition, preferring one with the same name.
     */
    private fun <T> match(
        old: List<T>,
        new: List<T>,
        name: (T) -> String,
        carried: (T) -> Any?,
        definition: (T) -> Any,
    ): Matched<T> {
        val remaining = new.toMutableList()
        val dropped = mutableListOf<T>()
        val renamed = mutableListOf<Pair<T, T>>()
        old.forEach { o ->
            val target = carried(o)
            val candidates =
                if (target == null) emptyList() else remaining.filter { definition(it) == target }
            val pick = candidates.firstOrNull { name(it) == name(o) } ?: candidates.firstOrNull()
            if (pick == null) dropped += o
            else {
                remaining.remove(pick)
                if (name(pick) != name(o)) renamed += o to pick
            }
        }
        return Matched(dropped, renamed, remaining)
    }

    companion object {
        /**
         * Every double-quoted identifier in a CHECK expression, with where it stands; single-quoted
         * literals are skipped so a quote inside one is never mistaken for an identifier.
         */
        fun identifiers(expression: String): List<Pair<IntRange, String>> {
            val out = mutableListOf<Pair<IntRange, String>>()
            var i = 0
            while (i < expression.length) {
                val quote = expression[i]
                if (quote != '"' && quote != '\'') {
                    i++
                    continue
                }
                val start = i
                val text = StringBuilder()
                i++
                while (i < expression.length) {
                    if (expression[i] == quote) {
                        if (i + 1 < expression.length && expression[i + 1] == quote) {
                            text.append(quote)
                            i += 2
                            continue
                        }
                        break
                    }
                    text.append(expression[i])
                    i++
                }
                if (quote == '"') out += (start..minOf(i, expression.lastIndex)) to text.toString()
                i++
            }
            return out
        }
    }
}
