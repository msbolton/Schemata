package io.schemata.importer.sql

import io.schemata.importer.ImportCodes
import io.schemata.lang.Diagnostic

internal class CheckInfo(val check: SqlConstraint.Check) {
    var consumed = false
    val expr: SqlExpr
        get() = check.expr
}

internal class FkInfo(val fk: SqlConstraint.ForeignKey) {
    var consumed = false
    val columns: List<String>
        get() = fk.columns
}

/** One table with everything later statements say about it folded in. */
internal class TableInfo(val schema: String, val table: SqlTable, val file: SqlFile) {
    val key = "$schema.${table.name}"
    val name: String
        get() = table.name

    var pk: List<String>? = null
    val uniques = mutableListOf<List<String>>()
    val checks = mutableListOf<CheckInfo>()
    val fks = mutableListOf<FkInfo>()
    val indexes = mutableListOf<SqlStatement.CreateIndex>()
    var doc: String? = null
    val columnDocs = mutableMapOf<String, String>()
    var child: ChildInfo? = null

    fun column(name: String): SqlColumn? = table.columns.firstOrNull { it.name == name }

    fun add(c: SqlConstraint) {
        when (c) {
            is SqlConstraint.PrimaryKey -> if (pk == null) pk = c.columns
            is SqlConstraint.Unique -> uniques += c.columns
            is SqlConstraint.Check -> checks += CheckInfo(c)
            is SqlConstraint.ForeignKey -> fks += FkInfo(c)
        }
    }
}

/**
 * A child table of [parent]: [field] is the parent's field it lowers to, a map when keyed by `key`,
 * a list when keyed by `position`.
 */
internal class ChildInfo(
    val info: TableInfo,
    val parent: TableInfo,
    val field: String,
    val map: Boolean,
    val parentFk: FkInfo,
) {
    val keyColumns: List<String>
        get() = parentFk.columns + (if (map) "key" else "position")
}

/**
 * Every table across the inputs, keyed `schema.table` in the order created, with the `ALTER TABLE …
 * ADD`, `CREATE INDEX`, and `COMMENT ON` statements of every file attached to the table they name,
 * and the child tables found.
 */
internal class Catalog(files: List<SqlFile>, diagnostics: MutableList<Diagnostic>) {
    val tables = LinkedHashMap<String, TableInfo>()
    val children = LinkedHashMap<String, MutableList<ChildInfo>>()

    init {
        for (f in files) {
            for (s in f.statements.filterIsInstance<SqlStatement.CreateTable>()) {
                val info = TableInfo(s.table.schema ?: PUBLIC, s.table, f)
                if (tables.putIfAbsent(info.key, info) != null) {
                    report(
                        diagnostics,
                        f,
                        ImportCodes.UNRESOLVED,
                        "${f.path}: table '${info.key}' is created twice; the second is ignored",
                        s.table.pos,
                        "remove one of them",
                    )
                }
            }
        }
        tables.values.forEach { t -> t.table.constraints.forEach(t::add) }
        for (f in files) {
            for (s in f.statements) {
                fun table(what: String, schema: String?, name: String, pos: SqlPos): TableInfo? =
                    resolve(schema, name)
                        ?: null.also {
                            val why =
                                if (schema == null && tables.values.count { it.name == name } > 1)
                                    "the name matches tables in several schemas"
                                else "the table is not in the inputs"
                            report(
                                diagnostics,
                                f,
                                ImportCodes.DROPPED,
                                "${f.path}: $what on '${schema ?: PUBLIC}.$name' dropped; $why",
                                pos,
                            )
                        }
                when (s) {
                    is SqlStatement.AlterAdd ->
                        table("ALTER TABLE", s.schema, s.table, s.pos)?.add(s.constraint)
                    is SqlStatement.CreateIndex ->
                        table("CREATE INDEX", s.schema, s.table, s.pos)?.indexes?.add(s)
                    is SqlStatement.CommentOn ->
                        table("COMMENT ON ${s.kind}", s.schema, s.table, s.pos)?.let { t ->
                            if (s.column == null) t.doc = s.text
                            else t.columnDocs[s.column] = s.text
                        }
                    else -> {}
                }
            }
        }
        tables.values.forEach(::findParent)
    }

    /**
     * The table [name] in [schema]; unqualified, the one in `public`, else the only table of that
     * name in any schema.
     */
    fun resolve(schema: String?, name: String): TableInfo? =
        if (schema != null) tables["$schema.$name"]
        else tables["$PUBLIC.$name"] ?: tables.values.singleOrNull { it.name == name }

    /**
     * Makes [t] a child of the table its cascading foreign key points at, when the key, the foreign
     * key, and the name all have the shape the SQL target gives a child table: the parent's key
     * columns, each prefixed with the parent's name, then `position` (an integer) or `key`, with
     * the table named `<parent>_<field>`.
     */
    private fun findParent(t: TableInfo) {
        val pk = t.pk ?: return
        for (fk in t.fks) {
            if (fk.fk.onDelete != "CASCADE") continue
            val p = resolve(fk.fk.refSchema, fk.fk.refTable) ?: continue
            if (p === t) continue
            val parentKey = p.pk ?: continue
            if (fk.fk.refColumns.ifEmpty { parentKey } != parentKey) continue
            if (fk.columns != parentKey.map { "${p.name}_$it" }) continue
            if (pk.size != fk.columns.size + 1 || pk.dropLast(1) != fk.columns) continue
            val map =
                when (pk.last()) {
                    "position" -> if (t.column("position")?.type == "integer") false else continue
                    "key" -> true
                    else -> continue
                }
            val prefix = "${p.name}_"
            if (!t.name.startsWith(prefix) || t.name.length == prefix.length) continue
            val child = ChildInfo(t, p, t.name.removePrefix(prefix), map, fk)
            fk.consumed = true
            t.child = child
            children.getOrPut(p.key) { mutableListOf() } += child
            return
        }
    }
}
