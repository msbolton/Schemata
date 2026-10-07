package io.schemata.migrate

import io.schemata.core.ir.Field
import io.schemata.core.ir.ListOf
import io.schemata.core.ir.MapOf
import io.schemata.core.ir.QualifiedName
import io.schemata.core.ir.RecordType
import io.schemata.core.ir.Ref
import io.schemata.core.ir.Schema
import io.schemata.core.ir.Type
import io.schemata.core.ir.UnionMember
import io.schemata.core.ir.UnionType
import io.schemata.target.sql.ColumnOrigin
import io.schemata.target.sql.OriginStep
import io.schemata.target.sql.TableOrigin

/**
 * The declarations a field chain passes through: [label] is its dotted suffix (`.shipping.street`,
 * a union member printing as `.#1`), [fields] every field it steps over with the record declaring
 * it, [members] every union member it steps over with its union, [named] every declaration those
 * fields and members reference, [last] the declaration the final step references (an enum for an
 * enum column), and [record] the record the chain ends in, whose fields a child table's own columns
 * start from.
 */
internal class Chain(
    val label: String,
    val fields: List<Pair<RecordType, Field>>,
    val named: Set<QualifiedName>,
    val record: RecordType?,
    val members: List<Pair<UnionType, UnionMember>> = emptyList(),
    val last: QualifiedName? = null,
) {
    operator fun plus(other: Chain) =
        Chain(
            label + other.label,
            fields + other.fields,
            named + other.named,
            other.record,
            members + other.members,
            if (other.label.isEmpty()) last else other.last,
        )
}

/**
 * The IR path a table's or column's provenance names, for messages:
 * `shop.orders.Order.shipping.street`.
 */
object Labels {
    fun table(schema: Schema, origin: TableOrigin): String =
        origin.record.toString() + table(schema, origin.record, origin.path).label

    fun column(schema: Schema, table: TableOrigin, origin: ColumnOrigin): String =
        when (origin) {
            is ColumnOrigin.FieldPath ->
                table.record.toString() + chain(schema, table, origin).label
            is ColumnOrigin.Role -> table(schema, table) + "." + origin.role.substringBefore(':')
        }

    /**
     * The whole chain from the table's record to [origin]: the table's own path, then the column's.
     * A role column is the table's chain alone, since the list or map field that made the table is
     * what decides its values.
     */
    internal fun chain(schema: Schema, table: TableOrigin, origin: ColumnOrigin): Chain {
        val tableChain = table(schema, table.record, table.path)
        if (origin !is ColumnOrigin.FieldPath) return tableChain
        // A child table of a keyed record or a map of keyless records reaches its element through
        // a synthetic `value` field the lowering numbers 0, which no declared field can be.
        val synthetic = origin.path.firstOrNull() == OriginStep.FieldOrdinal(0)
        val rest = if (synthetic) origin.path.drop(1) else origin.path
        val own = walk(schema, tableChain.record, rest)
        return tableChain +
            if (synthetic)
                Chain(
                    ".value" + own.label,
                    own.fields,
                    own.named,
                    own.record,
                    own.members,
                    own.last ?: tableChain.last,
                )
            else own
    }

    internal fun table(schema: Schema, record: QualifiedName, path: List<OriginStep>): Chain =
        walk(schema, schema.lookupOrNull(record) as? RecordType, path)

    /**
     * Walks [path] from [start] by ordinal: a field step moves into the record its type, list
     * element, or map value references; a member step into the member of the union the preceding
     * field references, and on into that member's record when it is one.
     */
    private fun walk(schema: Schema, start: RecordType?, path: List<OriginStep>): Chain {
        val label = StringBuilder()
        val fields = mutableListOf<Pair<RecordType, Field>>()
        val members = mutableListOf<Pair<UnionType, UnionMember>>()
        val named = mutableSetOf<QualifiedName>()
        var record: RecordType? = start
        var union: UnionType? = null
        var last: QualifiedName? = null
        for (step in path) {
            when (step) {
                is OriginStep.FieldOrdinal -> {
                    val owner = record
                    val field = owner?.fields?.firstOrNull { it.ordinal == step.ordinal }
                    label.append('.').append(field?.name ?: "#${step.ordinal}")
                    if (owner != null && field != null) fields += owner to field
                    val target = field?.type?.let { referenced(it) }
                    target?.let { named += it }
                    last = target
                    val decl = target?.let { schema.lookupOrNull(it) }
                    record = decl as? RecordType
                    union = decl as? UnionType
                }
                is OriginStep.MemberOrdinal -> {
                    label.append(".#").append(step.ordinal)
                    val owner = union
                    val member = owner?.members?.firstOrNull { it.ordinal == step.ordinal }
                    if (owner != null && member != null) members += owner to member
                    val target = member?.type?.let { referenced(it) }
                    target?.let { named += it }
                    last = target
                    record = target?.let { schema.lookupOrNull(it) } as? RecordType
                    union = null
                }
            }
        }
        return Chain(label.toString(), fields, named, record, members, last)
    }

    /** The declaration a type names directly or as its list element or map value. */
    internal fun referenced(type: Type): QualifiedName? =
        when (type) {
            is Ref -> type.target
            is ListOf -> (type.element as? Ref)?.target
            is MapOf -> (type.value as? Ref)?.target
            else -> null
        }
}
