package io.schemata.target.sql

import io.schemata.core.ir.QualifiedName

/**
 * Where a field is being lowered: which table, under which column prefix, and inside which records.
 * [embedding] starts with the record that owns the table. [parentTable] and [parentKeys] are the
 * table [table] itself is keyed by (its own name and primary key, name then column type); a
 * `list<Record>` field reads them to name and type the column it adds pointing back at that key, so
 * a child of a child points at the child's own key rather than the root's.
 */
data class FieldContext(
    val table: String,
    val prefix: String = "",
    val forceNullable: Boolean = false,
    val embedding: List<QualifiedName> = emptyList(),
    val parentTable: String = table,
    val parentKeys: List<Pair<String, ColumnType>> = emptyList(),
    val where: String,
) {
    fun nested(field: String, nullable: Boolean, into: QualifiedName, where: String) =
        FieldContext(
            table = table,
            prefix = "$prefix${field}_",
            forceNullable = forceNullable || nullable,
            embedding = embedding + into,
            parentTable = parentTable,
            parentKeys = parentKeys,
            where = where,
        )
}

/** A foreign key together with the namespaces it links, so it can be placed in the later file. */
class PendingForeignKey(
    val fk: ForeignKey,
    val sourceNamespace: String,
    val targetNamespace: String,
)

/** A table produced by a field (a list's child), with its own foreign keys. */
class ChildTable(val table: Table, val foreignKeys: List<PendingForeignKey>)

/**
 * Everything one field adds to its table. [required] names the produced columns that are not
 * nullable on their own account (ignoring any outer embed's forced nullability); an embedding
 * consumes it to check that a nullable group is all-present or all-absent, then clears it, so a
 * column an inner optional embed already forced nullable never counts as required one level up.
 */
data class Contribution(
    val columns: List<Column> = emptyList(),
    val checks: List<Check> = emptyList(),
    val uniques: List<Unique> = emptyList(),
    val indexes: List<Index> = emptyList(),
    val foreignKeys: List<PendingForeignKey> = emptyList(),
    val children: List<ChildTable> = emptyList(),
    val required: List<String> = emptyList(),
) {
    companion object {
        val NONE = Contribution()
    }
}
