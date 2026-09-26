package io.schemata.target.sql

import io.schemata.core.ir.AnnotationValue
import io.schemata.core.ir.Field
import io.schemata.core.ir.ListOf
import io.schemata.core.ir.MapOf
import io.schemata.core.ir.QualifiedName
import io.schemata.core.ir.RecordType
import io.schemata.core.ir.Ref
import io.schemata.core.ir.Scalar
import io.schemata.core.ir.Schema
import io.schemata.core.ir.Type
import io.schemata.core.ir.TypeDecl
import io.schemata.core.ir.UnionType
import io.schemata.core.ir.selfAndNested
import io.schemata.lang.Span

/**
 * Every keyed record's table, resolved before any field is lowered so references and child tables
 * can point at tables in any namespace. [used] holds every record some field or union member refers
 * to, so an unused keyless record can be reported.
 *
 * Table and key column names pass through [identifier] here, once; lowering reuses them rather than
 * deriving them again, so a truncation is reported a single time.
 */
class Catalog(
    schema: Schema,
    schemaNames: Map<String, String>,
    identifier: (String, Span) -> String,
) {
    class Entry(
        val qualifiedName: QualifiedName,
        val namespace: String,
        val schemaName: String,
        val tableName: String,
        val keyFields: List<Field>,
        val keyColumns: List<String>,
    )

    private val entries = mutableMapOf<QualifiedName, Entry>()
    val used: Set<QualifiedName>

    init {
        val all =
            schema.namespaces.flatMap { ns ->
                ns.declarations.flatMap { it.selfAndNested() }.map { ns to it }
            }
        all.forEach { (ns, decl) ->
            if (decl is RecordType) {
                val keyFields = keyFields(decl)
                // A record that declares a key, even one whose names all fail to resolve, is still
                // keyed: it gets a table (with no primary key) and lowering reports the bad names,
                // rather than the record silently falling back to a value type.
                if (keyFields.isNotEmpty() || declaresKey(decl)) {
                    val tableName = identifier(Naming.tableOf(decl), decl.nameSpan)
                    entries[decl.qualifiedName] =
                        Entry(
                            decl.qualifiedName,
                            ns.name,
                            schemaNames.getValue(ns.name),
                            tableName,
                            keyFields,
                            keyFields.map { identifier(Naming.columnOf(it), it.nameSpan) },
                        )
                }
            }
        }
        used = all.flatMap { (_, decl) -> targets(decl) }.toSet()
    }

    operator fun get(name: QualifiedName): Entry? = entries[name]

    /**
     * The key fields in key order, or empty when the record has no key. Diagnostics about the key
     * forms are lowering's job.
     */
    private fun keyFields(record: RecordType): List<Field> {
        val recordKey =
            (record.annotations["sql"]["key"] as? AnnotationValue.Names)?.values?.distinct()
        if (recordKey != null) {
            return recordKey.mapNotNull { n -> record.fields.firstOrNull { it.name == n } }
        }
        return record.fields.filter { "key" in it.annotations["sql"] }
    }

    /**
     * Whether the record's own `@sql(key)` says it means to be keyed, whether or not it resolves.
     */
    private fun declaresKey(record: RecordType): Boolean =
        record.annotations["sql"]["key"] is AnnotationValue.Names ||
            record.fields.any { "key" in it.annotations["sql"] }

    private fun targets(decl: TypeDecl): List<QualifiedName> =
        when (decl) {
            is RecordType -> decl.fields.flatMap { refs(it.type) }
            is UnionType -> decl.members.flatMap { refs(it.type) }
            else -> emptyList()
        }

    private fun refs(type: Type): List<QualifiedName> =
        when (type) {
            is Ref -> listOf(type.target)
            is ListOf -> refs(type.element)
            is MapOf -> refs(type.key) + refs(type.value)
            is Scalar -> emptyList()
        }
}
