package io.schemata.importer.sql

import io.schemata.importer.ImportNames
import io.schemata.importer.SchemataUnit
import io.schemata.lang.Diagnostic
import io.schemata.lang.DiagnosticCode

/**
 * Postgres tables read back into Schemata records. Every table becomes a record unless it is a
 * child table; then each record's columns are read left to right by the structures the SQL target
 * writes, recognised by their shape (key columns, foreign-key targets, check expressions) and never
 * by a constraint's name, so a hand-written file reads as the target's own output does:
 * - a `<f>_kind` text column whose `IN` check lists the members, with member columns `<f>_<m>` or
 *   `<f>_<m>_…` and per-member presence checks, is a union field;
 * - a column group under one prefix with an all-or-none check is a nullable embedded record;
 * - a foreign key to a table's primary key is a reference;
 * - any other column is a scalar, an enum (`text` with an `IN` check), a list (an array), or a
 *   value stored as json, its checks becoming refinements;
 * - a table keyed by a parent's key plus `position` (a list) or `key` (a map), with a cascading
 *   foreign key to that parent, is a list or map field of the parent.
 */
internal object SqlImport {
    fun lower(
        files: List<SqlFile>,
        namespaces: Map<String, SchemaNamespace>,
        diagnostics: MutableList<Diagnostic>,
    ): List<SchemataUnit> {
        val context = ImportContext(Catalog(files, diagnostics), namespaces, diagnostics)
        return units(context, RecordLowering(context))
    }

    private fun units(context: ImportContext, lowering: RecordLowering): List<SchemataUnit> =
        context.namespaces.entries.groupBy({ it.value.name }, { it.key }).mapNotNull {
            (namespace, schemas) ->
            val tables =
                context.catalog.tables.values.filter { it.schema in schemas && it.child == null }
            if (tables.isEmpty()) return@mapNotNull null
            val first = context.namespaces.getValue(schemas.first())
            val topNames = tables.map { context.recordNames.getValue(it.key) }.toSet()
            val imports = LinkedHashSet<String>()
            val lowered = tables.map { lowering.record(it, Run(namespace, imports, topNames)) }
            SchemataUnit(
                namespace,
                first.annotations,
                null,
                imports.toList(),
                lowered,
                sourcePath = first.sourcePath,
            )
        }
}

/**
 * What the lowering shares: the catalog, the schema namespaces, where diagnostics go, and the
 * record each table lowers to.
 */
internal class ImportContext(
    val catalog: Catalog,
    val namespaces: Map<String, SchemaNamespace>,
    val diagnostics: MutableList<Diagnostic>,
) {
    /** Where diagnostics go; a column group collects its own to report them in column order. */
    var sink: MutableList<Diagnostic> = diagnostics

    /** The record each table that is not a child table lowers to, unique within its namespace. */
    val recordNames = LinkedHashMap<String, String>()

    init {
        catalog.tables.values
            .filter { it.child == null }
            .groupBy { namespaceOf(it) }
            .values
            .forEach { group ->
                val taken = mutableSetOf<String>()
                group.forEach { t ->
                    val base = ImportNames.upperCamel(t.name)
                    val name =
                        generateSequence(1) { it + 1 }
                            .map { if (it == 1) base else "$base$it" }
                            .first { it !in taken }
                    taken += name
                    recordNames[t.key] = name
                }
            }
    }

    fun namespaceOf(t: TableInfo): String = namespaces.getValue(t.schema).name

    fun say(ctx: TableCtx, column: SqlColumn, code: DiagnosticCode, message: String) {
        report(
            sink,
            ctx.info.file,
            code,
            "column '${ctx.label}.${column.name}': $message",
            column.pos,
        )
    }

    fun sayTable(info: TableInfo, code: DiagnosticCode, message: String) {
        report(sink, info.file, code, "table '${info.name}': $message", info.table.pos)
    }

    inline fun <T> capture(into: MutableList<Diagnostic>, block: () -> T): T {
        val saved = sink
        sink = into
        try {
            return block()
        } finally {
            sink = saved
        }
    }
}
