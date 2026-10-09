package io.schemata.target.sql

import io.schemata.core.ir.Builtin
import io.schemata.core.ir.EnumType
import io.schemata.core.ir.Field
import io.schemata.core.ir.ListOf
import io.schemata.core.ir.MapOf
import io.schemata.core.ir.OnDelete
import io.schemata.core.ir.RecordType
import io.schemata.core.ir.Ref
import io.schemata.core.ir.Refinements
import io.schemata.core.ir.Relation as RefRelation
import io.schemata.core.ir.Scalar
import io.schemata.core.ir.Type
import io.schemata.core.ir.UnionType
import io.schemata.lang.Span
import io.schemata.target.TypeText

/**
 * Lowers the collection shapes: a list or map field as an array, a child table, or jsonb, with the
 * `json` fallback every shape shares and the child table builder that keys a child by its parent.
 */
internal class ChildTables(private val context: LoweringContext) {
    /**
     * The relation a list's element or a map's value reference carries, so a child table's `value`
     * reference acts on delete as the field asked.
     */
    private fun elementRelation(type: Type): RefRelation =
        when (type) {
            is ListOf -> (type.element as? Ref)?.relation
            is MapOf -> (type.value as? Ref)?.relation
            else -> null
        } ?: RefRelation()

    /**
     * A list: the default is an array for a scalar or enum element and a child table for a record
     * one; `table` asks for a child table either way, with a single `value` column carrying a
     * scalar or enum element's own checks instead of an array's stripped bounds; `json` lowers the
     * whole list to jsonb, the only strategy that reaches a union, nested list, or nested map
     * element.
     */
    internal fun listField(
        ctx: FieldContext,
        field: Field,
        strategy: String?,
        type: ListOf,
    ): Contribution {
        if (strategy == "json") return json(ctx, field, "list", jsonHelp(type.element))
        return when (val element = type.element) {
            is Scalar ->
                if (strategy == "table") {
                    child(
                        ctx,
                        field,
                        type.refinements,
                        Element.Scalar(element),
                        type.nullableElement,
                        null,
                    )
                } else array(ctx, field, type, element, null)
            is Ref ->
                when (val elementTarget = context.schema.lookup(element.target)) {
                    is EnumType ->
                        if (strategy == "table") {
                            child(
                                ctx,
                                field,
                                type.refinements,
                                Element.Enum(elementTarget),
                                type.nullableElement,
                                null,
                            )
                        } else array(ctx, field, type, null, elementTarget)
                    is RecordType ->
                        child(
                            ctx,
                            field,
                            type.refinements,
                            Element.Record(elementTarget),
                            type.nullableElement,
                            null,
                        )
                    is UnionType ->
                        context.recordLowering.noRelationalMapping(ctx, field, "a list of unions")
                }
            is ListOf,
            is MapOf ->
                context.recordLowering.noRelationalMapping(ctx, field, "a list of lists or maps")
        }
    }

    /**
     * A map: the default and `json` both lower it to jsonb, since Postgres has no typed map;
     * `table` asks for a child table keyed by the parent and the map's own key, with the value
     * lowered the way a list's scalar, enum, or record element is, under a `value` column.
     */
    internal fun mapField(
        ctx: FieldContext,
        field: Field,
        strategy: String?,
        type: MapOf,
    ): Contribution {
        if (strategy == null || strategy == "json")
            return json(ctx, field, "map", jsonHelp(type.value))
        // The resolver only admits string, int32, and int64 keys; the fallback is never taken.
        val key = type.key as? Scalar ?: Scalar(Builtin.STRING)
        return when (val value = type.value) {
            is Scalar ->
                child(ctx, field, type.refinements, Element.Scalar(value), type.nullableValue, key)
            is Ref ->
                when (val valueTarget = context.schema.lookup(value.target)) {
                    is EnumType ->
                        child(
                            ctx,
                            field,
                            type.refinements,
                            Element.Enum(valueTarget),
                            type.nullableValue,
                            key,
                        )
                    is RecordType ->
                        child(
                            ctx,
                            field,
                            type.refinements,
                            Element.Record(valueTarget),
                            type.nullableValue,
                            key,
                        )
                    is UnionType ->
                        context.recordLowering.forbiddenStrategy(
                            ctx,
                            field,
                            "table",
                            "a map of unions",
                            "json",
                        )
                }
            is ListOf,
            is MapOf ->
                context.recordLowering.forbiddenStrategy(
                    ctx,
                    field,
                    "table",
                    "a map of lists or maps",
                    "json",
                )
        }
    }

    /**
     * Whether a list's element or a map's value could lower to a child table: a scalar, enum, or
     * record can, a union or a nested list or map cannot.
     */
    private fun tableable(element: Type): Boolean =
        when (element) {
            is Scalar -> true
            is Ref -> context.schema.lookup(element.target) !is UnionType
            is ListOf,
            is MapOf -> false
        }

    /**
     * The help for a list or map's `json` lowering: `table` when the element or value could lower
     * to a child table instead, otherwise jsonb is the only mapping this shape has.
     */
    private fun jsonHelp(element: Type): String =
        if (tableable(element)) "use `@sql(strategy: table)` to lower the entries to a child table"
        else JSONB_ONLY_HELP

    /**
     * A `list<scalar|enum>` field: a Postgres array. The element's own checks make no sense over an
     * array and are discarded; a bound on the list itself, a bound on the element, or a nullable
     * element are all information Postgres will not enforce, reported once with the full type as a
     * note.
     */
    private fun array(
        ctx: FieldContext,
        field: Field,
        type: ListOf,
        scalar: Scalar?,
        enum: EnumType?,
    ): Contribution {
        val (name, rawName) = context.recordLowering.columnNames(ctx, field)
        // The element's own bounds are reported, not enforced, so they never reach its column
        // type either (a bounded string would otherwise narrow to varchar(n)); precision and
        // scale stay, since for a decimal they are the type, not a bound.
        val elementType =
            if (scalar != null) {
                val bare =
                    scalar.copy(
                        refinements =
                            scalar.refinements.copy(min = null, max = null, pattern = null)
                    )
                SqlTypes.scalar(bare, name).type
            } else SqlTypes.enum(enum!!.values.map { it.name }, name).type
        val elementHasBounds = scalar?.refinements?.hasBounds ?: false
        val elementLossy = elementHasBounds || type.nullableElement
        val lossy = type.refinements.hasBounds || elementLossy
        if (lossy) {
            context.error(
                SqlCodes.LOSSY,
                "${ctx.where}: refinements on ${TypeText.of(type, field.nullable)} are not enforced by Postgres",
                field.span,
                help =
                    if (elementLossy)
                        "use `@sql(strategy: table)` so the elements become rows with their own constraints"
                    else
                        "enforce the list's size bound in application code; Postgres arrays carry no length constraint",
            )
        }
        val column =
            Column(
                name = name,
                type = ColumnType.ARRAY(elementType),
                nullable = field.nullable || ctx.forceNullable,
                default = field.default?.let { Naming.literal(it) },
                doc = field.doc,
                notes = if (lossy) listOf(TypeText.of(type, field.nullable)) else emptyList(),
                origin = ctx.columnOrigin(field),
                span = field.nameSpan,
            )
        return Contribution(
            columns = listOf(column),
            uniques = context.recordLowering.uniqueOf(ctx, field, rawName, listOf(name)),
            indexes = context.recordLowering.indexOf(ctx, field, rawName, listOf(name)),
            required = if (field.nullable) emptyList() else listOf(name),
        )
    }

    /**
     * The `json` strategy, and every shape's default that already means it (a bare `map`): Postgres
     * has no typed record, list, map, or union, so the field lowers whole to `jsonb` with a lossy
     * note; [shape] names what was lowered away in the message, and [help] fits the fix to what
     * this particular field could actually do instead.
     */
    internal fun json(ctx: FieldContext, field: Field, shape: String, help: String): Contribution {
        val (name, rawName) = context.recordLowering.columnNames(ctx, field)
        context.error(
            SqlCodes.LOSSY,
            "${ctx.where}: $shape contents are not typed by Postgres; lowered to jsonb",
            field.span,
            help = help,
        )
        val column =
            Column(
                name = name,
                type = ColumnType.JSONB,
                nullable = field.nullable || ctx.forceNullable,
                default = field.default?.let { Naming.literal(it) },
                doc = field.doc,
                notes = listOf(TypeText.of(field.type, field.nullable)),
                origin = ctx.columnOrigin(field),
                span = field.nameSpan,
            )
        return Contribution(
            columns = listOf(column),
            uniques = context.recordLowering.uniqueOf(ctx, field, rawName, listOf(name)),
            indexes = context.recordLowering.indexOf(ctx, field, rawName, listOf(name)),
            required = if (field.nullable) emptyList() else listOf(name),
        )
    }

    /** What a child table holds per row: a list's element or a map's value. */
    private sealed interface Element {
        class Scalar(val scalar: io.schemata.core.ir.Scalar) : Element

        class Enum(val enum: EnumType) : Element

        class Record(val record: RecordType) : Element
    }

    /**
     * The `table` strategy for a list or a map, and a list of records' default: a child table named
     * `<table>_<field>`. It is keyed by the table it is declared on (its own key columns, each
     * renamed `<table>_<key column>`) plus either a `position` (a list) or a `key` typed like
     * [mapKey] (a map), followed by the [element]'s own columns:
     * - a scalar or enum is one `value` column carrying the element's own checks rather than an
     *   array's stripped bounds;
     * - a keyless record in a list contributes its own fields unprefixed, since the row already is
     *   the record; a nullable element has no row to stand for its null, which is reported;
     * - a keyless record as a map value embeds under `value_`, like any nullable or required embed;
     * - a keyed record is a reference through a synthetic `value` field: `value_<key column>`
     *   columns, nullable when the element is, and a foreign key `fk_<child>_value`, so a list of a
     *   record's own type never repeats the parent-key column or its foreign key name; `{ unique }`
     *   on the list adds a unique over the parent key and those columns, so each parent holds each
     *   key once. Written `{ embed }`, it is copied as a keyless one is.
     *
     * Every column the element adds is checked against the parent-key and position or key columns
     * ahead of it. A list or map bound ([refinements]) is reported since there is no column left to
     * carry a note on. A child's own list or map fields make grandchildren the same way, through a
     * fresh context whose table and key are the child's, so the grandchild points back at the child
     * rather than the root; [ctx]'s embedding chain still catches a keyless element that would
     * embed itself.
     */
    private fun child(
        ctx: FieldContext,
        field: Field,
        refinements: Refinements,
        element: Element,
        elementNullable: Boolean,
        mapKey: Scalar?,
    ): Contribution {
        val record = (element as? Element.Record)?.record
        // `{ embed }` copies a keyed element as it copies a keyless one: no reference
        val embedded = elementRelation(field.type).embed
        val entry = record?.let { context.catalog[it.qualifiedName] }?.takeUnless { embedded }
        val rows = record != null && entry == null && mapKey == null
        if (rows && context.recordLowering.recursionError(ctx, field, record!!))
            return Contribution.NONE
        if (refinements.hasBounds) {
            context.error(
                SqlCodes.LOSSY,
                "${ctx.where}: refinements on ${TypeText.of(field.type, field.nullable)} are not enforced by Postgres",
                field.span,
                help =
                    "enforce the collection bound in application code; child tables carry no row-count constraints",
            )
        }
        if (rows && elementNullable) {
            context.error(
                SqlCodes.LOSSY,
                "${ctx.where}: nullable elements of ${TypeText.of(field.type, field.nullable)} are not represented by a child table",
                field.span,
                help =
                    "declare the elements non-nullable, or use `@sql(strategy: json)` to keep nulls",
            )
        }
        val childName =
            context.identifier(
                "${ctx.table}_${ctx.prefix}${context.recordLowering.columnOf(field, ctx.where)}",
                field.nameSpan,
            )
        val parentColumns =
            ctx.parentKeys.map { key ->
                Column(
                    context.identifier("${ctx.table}_${key.column}", field.nameSpan),
                    key.type,
                    nullable = false,
                    origin = ColumnOrigin.Role("parent:${key.id}"),
                    span = field.nameSpan,
                )
            }
        val parentFk =
            PendingForeignKey(
                ForeignKey(
                    name = context.identifier("fk_${childName}_${ctx.parentTable}", field.nameSpan),
                    schema = context.schemaName,
                    table = childName,
                    columns = parentColumns.map { it.name },
                    targetSchema = context.schemaName,
                    targetTable = ctx.parentTable,
                    targetColumns = ctx.parentKeys.map { it.column },
                    onDelete = OnDelete.CASCADE,
                ),
                context.namespace.name,
                context.namespace.name,
            )
        val discriminator =
            mapKey?.let { keyColumn(ctx, field, it) }
                ?: Column(
                    "position",
                    ColumnType.INTEGER,
                    nullable = false,
                    origin = ColumnOrigin.Role("position"),
                    span = field.nameSpan,
                )
        // The child's own key copies keep their ids, so a grandchild's copies match by them.
        val childKeys =
            ctx.parentKeys.zip(parentColumns).map { (key, column) ->
                ParentKey(column.name, column.type, key.id)
            } +
                ParentKey(
                    discriminator.name,
                    discriminator.type,
                    if (mapKey == null) "position" else "key",
                )
        val childOrigin =
            TableOrigin(
                ctx.tableOrigin.record,
                ctx.tableOrigin.path + ctx.path + OriginStep.FieldOrdinal(field.ordinal),
            )
        val childCtx =
            FieldContext(
                table = childName,
                tableOrigin = childOrigin,
                embedding = if (rows) ctx.embedding + record!!.qualifiedName else ctx.embedding,
                parentTable = childName,
                parentKeys = childKeys,
                where = ctx.where,
            )
        // Each part of the element's columns, with what names it and where it is declared.
        val parts: List<Triple<String, Span, Contribution>> =
            if (rows) {
                record!!.fields.map {
                    val where = "field '${record.name}.${it.name}'"
                    Triple(
                        where,
                        it.nameSpan,
                        context.recordLowering.contribute(childCtx.copy(where = where), it),
                    )
                }
            } else {
                val valueField =
                    Field(
                        0,
                        "value",
                        when (element) {
                            is Element.Scalar -> element.scalar
                            is Element.Enum -> Ref(element.enum.qualifiedName)
                            is Element.Record ->
                                Ref(element.record.qualifiedName, elementRelation(field.type))
                        },
                        elementNullable,
                        null,
                        null,
                        null,
                        field.span,
                        field.nameSpan,
                    )
                // The synthetic value field is no declared field; its column is a role.
                val valueCtx = childCtx.copy(role = "value")
                val value =
                    when (element) {
                        is Element.Scalar ->
                            context.recordLowering.column(
                                valueCtx,
                                valueField,
                                element.scalar,
                                null,
                            )
                        is Element.Enum ->
                            context.recordLowering.column(valueCtx, valueField, null, element.enum)
                        is Element.Record ->
                            if (entry != null)
                                context.recordLowering.reference(childCtx, valueField, entry)
                            else
                                context.recordLowering.embed(
                                    childCtx,
                                    valueField,
                                    element.record,
                                    "value",
                                )
                    }
                listOf(Triple(ctx.where, field.nameSpan, value))
            }
        val position = if (mapKey == null) "position" else "map key"
        context.recordLowering.columnCollisions(
            listOf(
                ColumnSource(
                    "the child table's parent key column",
                    field.nameSpan,
                    parentColumns.map { it.name },
                ),
                ColumnSource(
                    "the child table's $position column",
                    field.nameSpan,
                    listOf(discriminator.name),
                ),
            ) +
                parts.map { (where, span, part) ->
                    ColumnSource(where, span, part.columns.map { it.name })
                }
        )
        context.recordLowering.constraintCollisions(
            childName,
            listOf(parentFk.fk.name to field.nameSpan) +
                parts.flatMap { (_, span, part) ->
                    context.recordLowering.constraintNames(part).map { it to span }
                },
        )
        val merged = context.recordLowering.merge(parts.map { it.third })
        // `{ unique }` on a list of a keyed model: each parent holds each key once, a set
        val set =
            if (field.unique && entry != null && mapKey == null)
                listOf(
                    Unique(
                        context.identifier("uq_${childName}_value", field.nameSpan),
                        parentColumns.map { it.name } + merged.columns.map { it.name },
                    )
                )
            else emptyList()
        val childTable =
            Table(
                name = childName,
                columns = parentColumns + discriminator + merged.columns,
                primaryKey = childKeys.map { it.column },
                primaryKeyName = context.identifier("pk_$childName", field.nameSpan),
                checks = merged.checks,
                uniques = merged.uniques + set,
                indexes = merged.indexes,
                doc = record?.doc,
                origin = childOrigin,
                span = field.nameSpan,
            )
        return Contribution(
            children =
                listOf(ChildTable(childTable, listOf(parentFk) + merged.foreignKeys)) +
                    merged.children
        )
    }

    /**
     * A map's `key` column, typed like [type] — always a bare scalar, so it carries no checks. An
     * unsupported pattern is still screened, since it changes whether the type fits a plain
     * `varchar(n)`.
     */
    private fun keyColumn(ctx: FieldContext, field: Field, type: Scalar): Column {
        val refinements =
            context.recordLowering.screenPattern(ctx.where, field.nameSpan, type.refinements)
        return Column(
            "key",
            SqlTypes.scalar(type.copy(refinements = refinements), "key").type,
            nullable = false,
            origin = ColumnOrigin.Role("key"),
            span = field.nameSpan,
        )
    }
}
