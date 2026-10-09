package io.schemata.importer.sql

import io.schemata.importer.SchemataUnit
import io.schemata.lang.Diagnostic

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
    ): List<SchemataUnit> = Lowering(Catalog(files, diagnostics), namespaces, diagnostics).units()
}
