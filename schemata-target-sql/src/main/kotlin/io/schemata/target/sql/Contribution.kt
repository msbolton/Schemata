package io.schemata.target.sql

import io.schemata.core.ir.Field
import io.schemata.core.ir.QualifiedName

/**
 * Where a field is being lowered: which table, under which column prefix, and inside which records.
 * [embedding] starts with the record that owns the table. [parentTable] and [parentKeys] are the
 * table [table] itself is keyed by (its own name and primary key, name then column type); a
 * `list<Record>` field reads them to name and type the column it adds pointing back at that key, so
 * a child of a child points at the child's own key rather than the root's. A [role] names the
 * column that stands for something no declared field is (a child table's `value`), so its origin is
 * that role rather than a field path.
 */
data class FieldContext(
    val table: String,
    val tableOrigin: TableOrigin,
    val prefix: String = "",
    val forceNullable: Boolean = false,
    val embedding: List<QualifiedName> = emptyList(),
    val parentTable: String = table,
    val parentKeys: List<ParentKey> = emptyList(),
    val path: List<OriginStep> = emptyList(),
    val where: String,
    val role: String? = null,
) {
    fun nested(
        field: String,
        nullable: Boolean,
        into: QualifiedName,
        where: String,
        step: OriginStep,
    ) =
        FieldContext(
            table = table,
            tableOrigin = tableOrigin,
            prefix = "$prefix${field}_",
            forceNullable = forceNullable || nullable,
            embedding = embedding + into,
            parentTable = parentTable,
            parentKeys = parentKeys,
            path = path + step,
            where = where,
        )

    /**
     * The origin of a column [field] produces directly; [part] tells several such columns apart.
     */
    fun columnOrigin(field: Field, part: String? = null): ColumnOrigin =
        if (role != null) ColumnOrigin.Role(role)
        else ColumnOrigin.FieldPath(path + OriginStep.FieldOrdinal(field.ordinal), part)
}

/**
 * One key column a child table copies from the table it hangs off: its [column] name and [type],
 * and [id], the identity the copy's origin carries (`parent:<id>`). A record's own key column is
 * identified by its key field's ordinal, so moving the key to another field is a new column rather
 * than a rename; a child table's own key columns keep their copy's id, or are `position` or `key`.
 */
data class ParentKey(val column: String, val type: ColumnType, val id: String)

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
