package io.schemata.target.sql

import io.schemata.core.ir.EnumType
import io.schemata.core.ir.Field
import io.schemata.core.ir.ListOf
import io.schemata.core.ir.MapOf
import io.schemata.core.ir.OnDelete
import io.schemata.core.ir.RecordType
import io.schemata.core.ir.Ref
import io.schemata.core.ir.Scalar
import io.schemata.core.ir.Type
import io.schemata.core.ir.UnionMember
import io.schemata.core.ir.UnionType
import io.schemata.target.unionMemberStem

/**
 * Lowers a reference to a union: a `<field>_kind` discriminator plus each member's own nested,
 * forced-nullable columns, with the CHECKs that tie a member's columns to the kind that names it.
 */
internal class UnionLowering(private val context: LoweringContext) {
    /**
     * A reference to a union: the default and `embed` both lower it to a discriminator plus each
     * member's own columns; `json` lowers the whole union to jsonb instead, the only strategy that
     * also works for a union with a member that is itself a union; `table` has no meaning for a
     * union.
     */
    internal fun unionField(
        ctx: FieldContext,
        field: Field,
        strategy: String?,
        type: UnionType,
    ): Contribution =
        when (strategy) {
            "json" -> context.childTables.json(ctx, field, "union", jsonHelp(type))
            "table" ->
                context.recordLowering.forbiddenStrategy(
                    ctx,
                    field,
                    "table",
                    "a union",
                    "embed or json",
                )
            else -> union(ctx, field, type)
        }

    /** Whether a union has a member that is itself a union, which has no relational mapping. */
    private fun hasUnionMember(type: UnionType): Boolean =
        type.members.any {
            it.type is Ref && context.schema.lookup((it.type as Ref).target) is UnionType
        }

    /** The help for a union's `json` lowering: only mandatory when a member is itself a union. */
    private fun jsonHelp(type: UnionType): String =
        if (hasUnionMember(type)) JSONB_ONLY_HELP else DROP_JSON_HELP

    /**
     * A reference to a union: a `<field>_kind` text column naming which member is present (and
     * carrying the field's doc, which no member column repeats), a CHECK constraining it to the
     * member names, and each member's own contribution nested under `<field>_<member>`, every
     * column forced nullable since only the member the kind names is ever populated. A member's own
     * required columns (the ones that would be NOT NULL on their own account) back a second CHECK
     * that they are all present exactly when the kind names that member; a member with none (a
     * keyless record with no fields) needs no such check. The union's own [Contribution.required]
     * names only the kind column: a member's columns never make the enclosing table's presence
     * checks, since a member is optional by construction and its own CHECK already enforces it. `{
     * unique }` or `{ index }` on the field covers the kind column and every member column. A
     * member that is itself a union has no kind column of its own to nest a second one under, so it
     * has no embed strategy and must be lowered with `strategy: json` instead (SCH-28).
     */
    private fun union(ctx: FieldContext, field: Field, type: UnionType): Contribution {
        if (hasUnionMember(type)) {
            return context.recordLowering.noRelationalMapping(
                ctx,
                field,
                "a union whose member is a union",
            )
        }
        val bare = context.recordLowering.columnOf(field, ctx.where)
        val outerRaw = ctx.prefix + bare
        val kindName = context.identifier("${outerRaw}_kind", field.nameSpan)
        val literals = type.members.map { memberLiteral(it.type) }
        val kindColumn =
            Column(
                name = kindName,
                type = ColumnType.TEXT,
                nullable = field.nullable || ctx.forceNullable,
                doc = field.doc,
                origin =
                    ColumnOrigin.FieldPath(
                        ctx.path + OriginStep.FieldOrdinal(field.ordinal),
                        "kind",
                    ),
                span = field.nameSpan,
            )
        val kindCheck =
            Check(
                context.identifier("ck_${ctx.table}_${outerRaw}_kind", field.nameSpan),
                "${Naming.quote(kindName)} IN (${literals.joinToString(", ") { Naming.literal(it) }})",
            )
        val merged =
            context.recordLowering.merge(
                type.members.zip(literals).map { (member, literal) ->
                    unionMember(ctx, field, bare, literal, kindName, member)
                }
            )
        val columns = listOf(kindColumn) + merged.columns
        val names = columns.map { it.name }
        return Contribution(
            columns = columns,
            checks = listOf(kindCheck) + merged.checks,
            uniques = merged.uniques + context.recordLowering.uniqueOf(ctx, field, outerRaw, names),
            indexes = merged.indexes + context.recordLowering.indexOf(ctx, field, outerRaw, names),
            foreignKeys = merged.foreignKeys,
            children = merged.children,
            required = if (field.nullable) emptyList() else listOf(kindName),
        )
    }

    /** The text a member compares the kind column to, and lists in its `IN (...)` check. */
    private fun memberLiteral(type: Type): String =
        when (type) {
            is ListOf,
            is MapOf -> "member"
            else -> unionMemberStem(type, context.schema) { null }
        }

    /** One union member's own columns, checks, and foreign keys, named from [literal]. */
    private fun unionMember(
        ctx: FieldContext,
        field: Field,
        bare: String,
        literal: String,
        kindName: String,
        member: UnionMember,
    ): Contribution {
        // The member's columns hang off the union field, then the member, in the field chain.
        val memberCtx =
            ctx.copy(
                path =
                    ctx.path +
                        OriginStep.FieldOrdinal(field.ordinal) +
                        OriginStep.MemberOrdinal(member.ordinal)
            )
        return when (val type = member.type) {
            is Scalar -> unionScalar(memberCtx, field, bare, literal, kindName, type)
            is Ref ->
                when (val target = context.schema.lookup(type.target)) {
                    is EnumType -> unionEnum(memberCtx, field, bare, literal, kindName, target)
                    is RecordType -> {
                        // a member written `{ embed }` copies a keyed model as a keyless one
                        val entry =
                            context.catalog[target.qualifiedName]?.takeUnless {
                                type.relation.embed
                            }
                        if (entry != null) {
                            unionReference(memberCtx, field, bare, literal, kindName, entry)
                        } else unionEmbed(memberCtx, field, bare, literal, kindName, target)
                    }
                    // A union member that is itself a union already failed the field in
                    // `union`.
                    is UnionType -> Contribution.NONE
                }
            // Unreachable: the analyzer already rejects a union member that is a list or a
            // map before lowering ever sees it; kept for exhaustiveness.
            is ListOf,
            is MapOf -> Contribution.NONE
        }
    }

    /** A raw scalar member: one nullable column named `<field>_<member>`. */
    private fun unionScalar(
        ctx: FieldContext,
        field: Field,
        bare: String,
        literal: String,
        kindName: String,
        scalar: Scalar,
    ): Contribution {
        val rawName = "${ctx.prefix}${bare}_$literal"
        val name = context.identifier(rawName, field.nameSpan)
        val refinements =
            context.recordLowering.screenPattern(ctx.where, field.nameSpan, scalar.refinements)
        val precision = refinements.precision
        if (precision != null && precision > SqlTypes.NUMERIC_PRECISION_LIMIT) {
            context.error(
                SqlCodes.TYPE_LIMIT,
                "${ctx.where}: decimal precision $precision exceeds Postgres's limit of ${SqlTypes.NUMERIC_PRECISION_LIMIT}",
                field.span,
                help = "use a precision of at most ${SqlTypes.NUMERIC_PRECISION_LIMIT}",
            )
            return Contribution.NONE
        }
        val mapped = SqlTypes.scalar(scalar.copy(refinements = refinements), name)
        val column =
            Column(
                name = name,
                type = mapped.type,
                nullable = true,
                origin = ColumnOrigin.FieldPath(ctx.path),
                span = field.nameSpan,
            )
        val checks =
            mapped.checks.map { (suffix, expression) ->
                Check(
                    context.identifier("ck_${ctx.table}_${rawName}_$suffix", field.nameSpan),
                    expression,
                )
            }
        return Contribution(
            columns = listOf(column),
            checks = checks + presenceCheck(ctx, field, kindName, rawName, literal, listOf(name)),
        )
    }

    /** A member that references an enum: one nullable column constrained to its values. */
    private fun unionEnum(
        ctx: FieldContext,
        field: Field,
        bare: String,
        literal: String,
        kindName: String,
        target: EnumType,
    ): Contribution {
        val rawName = "${ctx.prefix}${bare}_$literal"
        val name = context.identifier(rawName, field.nameSpan)
        val mapped = SqlTypes.enum(target.values.map { it.name }, name)
        val column =
            Column(
                name = name,
                type = mapped.type,
                nullable = true,
                origin = ColumnOrigin.FieldPath(ctx.path),
                span = field.nameSpan,
            )
        val checks =
            mapped.checks.map { (suffix, expression) ->
                Check(
                    context.identifier("ck_${ctx.table}_${rawName}_$suffix", field.nameSpan),
                    expression,
                )
            }
        return Contribution(
            columns = listOf(column),
            checks = checks + presenceCheck(ctx, field, kindName, rawName, literal, listOf(name)),
        )
    }

    /**
     * A member that references a keyed record: `<field>_<member>_<key column>` columns and a
     * foreign key, named from the member rather than from the field the way
     * `RecordLowering.reference` is.
     */
    private fun unionReference(
        ctx: FieldContext,
        field: Field,
        bare: String,
        literal: String,
        kindName: String,
        entry: Catalog.Entry,
    ): Contribution {
        val rawName = "${ctx.prefix}${bare}_$literal"
        val columns =
            entry.keyFields.zip(entry.keyColumns).mapNotNull { (key, keyColumn) ->
                val columnType = context.recordLowering.keyType(key) ?: return@mapNotNull null
                Column(
                    name = context.identifier("${rawName}_$keyColumn", field.nameSpan),
                    type = columnType,
                    nullable = true,
                    origin = ColumnOrigin.FieldPath(ctx.path, "k${key.ordinal}"),
                    span = field.nameSpan,
                )
            }
        if (columns.isEmpty()) return Contribution.NONE
        val names = columns.map { it.name }
        val fk =
            ForeignKey(
                name = context.identifier("fk_${ctx.table}_$rawName", field.nameSpan),
                schema = context.schemaName,
                table = ctx.table,
                columns = names,
                targetSchema = entry.schemaName,
                targetTable = entry.tableName,
                targetColumns = entry.keyColumns,
                onDelete = OnDelete.RESTRICT,
            )
        return Contribution(
            columns = columns,
            checks = presenceCheck(ctx, field, kindName, rawName, literal, names),
            foreignKeys = listOf(PendingForeignKey(fk, context.namespace.name, entry.namespace)),
        )
    }

    /**
     * A member that references a keyless record: its fields embed under `<field>_<member>_`, forced
     * nullable throughout, exactly like `RecordLowering.embed` but checked for presence by kind
     * rather than by a nullable embed's own all-or-nothing CHECK.
     */
    private fun unionEmbed(
        ctx: FieldContext,
        field: Field,
        bare: String,
        literal: String,
        kindName: String,
        target: RecordType,
    ): Contribution {
        if (context.recordLowering.recursionError(ctx, field, target)) return Contribution.NONE
        val rawName = "${ctx.prefix}${bare}_$literal"
        // ctx.path already ends in the member step, so the nested context only extends the
        // prefix.
        val inner =
            ctx.copy(
                prefix = "${ctx.prefix}${bare}_${literal}_",
                forceNullable = true,
                embedding = ctx.embedding + target.qualifiedName,
            )
        val merged =
            context.recordLowering.merge(
                target.fields.map {
                    context.recordLowering.contribute(
                        inner.copy(where = "field '${target.name}.${it.name}'"),
                        it,
                    )
                }
            )
        return merged.copy(
            required = emptyList(),
            checks =
                merged.checks +
                    presenceCheck(ctx, field, kindName, rawName, literal, merged.required),
        )
    }

    /**
     * The CHECK that a member's own columns are all present exactly when the kind names it; a
     * member with no such columns needs none.
     */
    private fun presenceCheck(
        ctx: FieldContext,
        field: Field,
        kindName: String,
        rawName: String,
        literal: String,
        required: List<String>,
    ): List<Check> {
        if (required.isEmpty()) return emptyList()
        val present = required.joinToString(" AND ") { "${Naming.quote(it)} IS NOT NULL" }
        return listOf(
            Check(
                context.identifier("ck_${ctx.table}_$rawName", field.nameSpan),
                "(${Naming.quote(kindName)} <> ${Naming.literal(literal)}) OR ($present)",
            )
        )
    }
}
