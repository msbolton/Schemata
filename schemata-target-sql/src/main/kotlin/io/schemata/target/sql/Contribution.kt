package io.schemata.target.sql

import io.schemata.core.ir.QualifiedName

/**
 * Where a field is being lowered: which table, under which column prefix, and inside which records.
 * [embedding] starts with the record that owns the table.
 */
data class FieldContext(
    val table: String,
    val prefix: String = "",
    val forceNullable: Boolean = false,
    val embedding: List<QualifiedName> = emptyList(),
    val where: String,
) {
    fun nested(field: String, nullable: Boolean, into: QualifiedName, where: String) =
        FieldContext(table, "$prefix${field}_", forceNullable || nullable, embedding + into, where)
}

/** A foreign key together with the namespaces it links, so it can be placed in the later file. */
class PendingForeignKey(
    val fk: ForeignKey,
    val sourceNamespace: String,
    val targetNamespace: String,
)

/** A table produced by a field (a list's child), with its own foreign keys. */
class ChildTable(val table: Table, val foreignKeys: List<PendingForeignKey>)

/** Everything one field adds to its table. */
data class Contribution(
    val columns: List<Column> = emptyList(),
    val checks: List<Check> = emptyList(),
    val uniques: List<Unique> = emptyList(),
    val indexes: List<Index> = emptyList(),
    val foreignKeys: List<PendingForeignKey> = emptyList(),
    val children: List<ChildTable> = emptyList(),
) {
    companion object {
        val NONE = Contribution()
    }
}
