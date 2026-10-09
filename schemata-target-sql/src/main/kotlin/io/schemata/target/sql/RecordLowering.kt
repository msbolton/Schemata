package io.schemata.target.sql

import io.schemata.core.ir.AnnotationValue
import io.schemata.core.ir.Builtin
import io.schemata.core.ir.EnumType
import io.schemata.core.ir.Field
import io.schemata.core.ir.ListOf
import io.schemata.core.ir.MapOf
import io.schemata.core.ir.OnDelete
import io.schemata.core.ir.RecordType
import io.schemata.core.ir.Ref
import io.schemata.core.ir.Refinements
import io.schemata.core.ir.Scalar
import io.schemata.core.ir.UnionType
import io.schemata.lang.Span
import io.schemata.target.NameClaims
import io.schemata.target.TypeText
import io.schemata.target.string

/** Something that puts [columns] on a table, described by [subject]; [span] locates it. */
internal class ColumnSource(val subject: String, val span: Span, val columns: List<String>)

/**
 * Lowers a keyed record to its own table: the record's constraint bookkeeping (composite and
 * per-field uniques and indexes, name collisions), and the fields that put columns on it directly,
 * whether a scalar, an enum, a reference to a keyed record, or an embedded keyless one. Every other
 * field shape is handed to [UnionLowering] or [ChildTables] through the [LoweringContext].
 */
internal class RecordLowering(private val context: LoweringContext) {
    /** A keyed record's table, the child tables its fields produce, and its foreign keys. */
    internal class RecordTables(
        val table: Table,
        val children: List<ChildTable>,
        val foreignKeys: List<PendingForeignKey>,
    )

    internal fun record(record: RecordType): RecordTables {
        val entry = context.catalog[record.qualifiedName]!!
        val tableName = entry.tableName
        val ctx =
            FieldContext(
                table = tableName,
                tableOrigin = TableOrigin(record.qualifiedName),
                embedding = listOf(record.qualifiedName),
                parentTable = tableName,
                parentKeys =
                    entry.keyFields.zip(entry.keyColumns).mapNotNull { (key, column) ->
                        keyType(key)?.let { ParentKey(column, it, "${key.ordinal}") }
                    },
                where = "",
            )
        val parts =
            record.fields.map { field ->
                field to contribute(ctx.copy(where = "field '${record.name}.${field.name}'"), field)
            }
        columnCollisions(
            parts.map { (field, part) ->
                ColumnSource(
                    "field '${record.name}.${field.name}'",
                    field.nameSpan,
                    part.columns.map { it.name },
                )
            }
        )
        constraintCollisions(
            tableName,
            parts.flatMap { (field, part) -> constraintNames(part).map { it to field.nameSpan } },
        )
        val columns = parts.flatMap { (_, part) -> part.columns }
        val primaryKey = entry.keyColumns.filter { key -> columns.any { it.name == key } }
        val primaryKeyName =
            if (primaryKey.isEmpty()) null else context.identifier("pk_$tableName", record.nameSpan)
        val uniques =
            constraints(record, "unique", owned(parts) { it.uniques }, primaryKey) { it.columns }
                .map { (field, u) -> field.nameSpan to u } +
                compositeUniques(record, tableName, parts, primaryKey)
        val indexes =
            constraints(record, "index", owned(parts) { it.indexes }, primaryKey) { it.columns }
                .map { (field, ix) -> field.nameSpan to ix } +
                compositeIndexes(record, tableName, parts, primaryKey)
        context.claim(
            TableClaim("table '$tableName'", record.nameSpan, entry.tableNameRaw),
            tableName,
            primaryKeyName,
            uniques,
            indexes,
        )
        parts.forEach { (field, part) ->
            part.children.forEach { child ->
                val t = child.table
                context.claim(
                    TableClaim("child table '${t.name}'", field.nameSpan, null),
                    t.name,
                    t.primaryKeyName,
                    t.uniques.map { field.nameSpan to it },
                    t.indexes.map { field.nameSpan to it },
                )
            }
        }
        val table =
            Table(
                name = tableName,
                columns = columns,
                primaryKey = primaryKey,
                primaryKeyName = primaryKeyName,
                checks = parts.flatMap { (_, part) -> part.checks },
                uniques = uniques.map { it.second },
                indexes = indexes.map { it.second },
                doc = record.doc,
                origin = ctx.tableOrigin,
                span = record.nameSpan,
            )
        return RecordTables(
            table,
            parts.flatMap { (_, part) -> part.children },
            parts.flatMap { (_, part) -> part.foreignKeys },
        )
    }

    private fun <T> owned(
        parts: List<Pair<Field, Contribution>>,
        of: (Contribution) -> List<T>,
    ): List<Pair<Field, T>> = parts.flatMap { (field, part) -> of(part).map { field to it } }

    /** `@@unique(a, b)`: one table-level UNIQUE over the named fields' columns, in order. */
    private fun compositeUniques(
        record: RecordType,
        table: String,
        parts: List<Pair<Field, Contribution>>,
        primaryKey: List<String>,
    ): List<Pair<Span, Unique>> =
        composite(record, "unique", record.uniques, parts, primaryKey).map { (names, columns) ->
            record.nameSpan to
                Unique(
                    context.identifier("uq_${table}_${names.joinToString("_")}", record.nameSpan),
                    columns,
                )
        }

    /** `@@index(a, b)`: one index over the named fields' columns, in order. */
    private fun compositeIndexes(
        record: RecordType,
        table: String,
        parts: List<Pair<Field, Contribution>>,
        primaryKey: List<String>,
    ): List<Pair<Span, Index>> =
        composite(record, "index", record.indexes, parts, primaryKey).map { (names, columns) ->
            record.nameSpan to
                Index(
                    context.identifier("ix_${table}_${names.joinToString("_")}", record.nameSpan),
                    columns,
                )
        }

    /**
     * Each of [lists] (a model's `@@unique` or `@@index` field-name lists) with the columns its
     * fields lowered to on the record's own table: a scalar's column, a reference's key columns, an
     * embed's columns. A field with no column there (a child table, a field that failed to lower)
     * leaves the constraint nothing to stand on, so it is reported and dropped; one over exactly
     * the primary key is redundant and dropped with a warning, as a field's own `{ unique }` is.
     */
    private fun composite(
        record: RecordType,
        key: String,
        lists: List<List<String>>,
        parts: List<Pair<Field, Contribution>>,
        primaryKey: List<String>,
    ): List<Pair<List<String>, List<String>>> =
        lists.mapNotNull { names ->
            val display = "@@$key(${names.joinToString(", ")})"
            val columns =
                names.map { name ->
                    val own = parts.firstOrNull { it.first.name == name }?.second?.columns
                    if (own.isNullOrEmpty()) {
                        context.error(
                            SqlCodes.STRATEGY_NOT_ALLOWED,
                            "model '${record.name}': $display names '$name', which has no column on the model's table",
                            record.nameSpan,
                            // a list or map lowered to a child table, or a field that failed to
                            // lower, has no column here for a composite constraint to cover
                            help =
                                "name only fields stored in the model's own columns; '$name' is stored elsewhere or not at all",
                        )
                        return@mapNotNull null
                    }
                    own.map { it.name }
                }
            val flat = columns.flatten()
            if (primaryKey.isNotEmpty() && flat == primaryKey) {
                context.error(
                    SqlCodes.REDUNDANT_CONSTRAINT,
                    "model '${record.name}': $display duplicates the primary key; dropped",
                    record.nameSpan,
                    help = "remove it; the primary key already enforces it",
                )
                return@mapNotNull null
            }
            names to flat
        }

    /** The CHECK and foreign key names a contribution puts on its own table. */
    internal fun constraintNames(part: Contribution): List<String> =
        part.checks.map { it.name } + part.foreignKeys.map { it.fk.name }

    /**
     * CHECK and foreign key names share one namespace per table, so two derivations that land on
     * the same name (a union's kind check and a member named `Kind`'s presence check, say) are
     * reported at the later one.
     */
    internal fun constraintCollisions(table: String, names: List<Pair<String, Span>>) {
        val seen = mutableSetOf<String>()
        names.forEach { (name, span) ->
            if (!seen.add(name)) {
                context.error(
                    SqlCodes.NAME_COLLISION,
                    "constraint name '$name' is already used on table '$table'",
                    span,
                    help =
                        "rename one of the constrained fields; constraint names derive from field names",
                )
            }
        }
    }

    /** Two sources whose columns land on the same final name, reported at the later one. */
    internal fun columnCollisions(sources: List<ColumnSource>) {
        val claims =
            NameClaims(
                SqlCodes.NAME_COLLISION,
                "rename one of them, or set `@sql(column: \"…\")` on one",
                context.diagnostics,
            )
        sources.forEach { source ->
            source.columns.forEach { column ->
                claims.claim("column", column, source.subject, source.span, kind = "column")
            }
        }
    }

    /**
     * The column type a key field has, which a reference to its record copies; null when the field
     * is not a single scalar column (a builtin or an enum). A pattern Postgres cannot express is
     * dropped here as the key's own column drops it, so every copy gets the same type; the key's
     * own column is where that is reported.
     */
    internal fun keyType(field: Field): ColumnType? {
        val override = field.annotations.string("sql", "type")
        return when (val type = field.type) {
            is Scalar -> {
                val refinements =
                    type.refinements.pattern
                        ?.takeIf { PostgresPattern.firstUnsupported(it) != null }
                        ?.let { type.refinements.copy(pattern = null) } ?: type.refinements
                override?.let { ColumnType.RAW(it) }
                    ?: SqlTypes.scalar(type.copy(refinements = refinements), field.name).type
            }
            is Ref ->
                when (val target = context.schema.lookup(type.target)) {
                    is EnumType ->
                        override?.let { ColumnType.RAW(it) }
                            ?: SqlTypes.enum(target.values.map { it.name }, field.name).type
                    is RecordType,
                    is UnionType -> null
                }
            is ListOf,
            is MapOf -> null
        }
    }

    /**
     * A unique or index over exactly the primary-key columns adds nothing the key does not already
     * enforce, so it is dropped with a warning.
     */
    private fun <T> constraints(
        record: RecordType,
        key: String,
        constraints: List<Pair<Field, T>>,
        primaryKey: List<String>,
        columns: (T) -> List<String>,
    ): List<Pair<Field, T>> =
        constraints.filter { (field, constraint) ->
            val redundant = primaryKey.isNotEmpty() && columns(constraint) == primaryKey
            if (redundant) {
                context.error(
                    SqlCodes.REDUNDANT_CONSTRAINT,
                    "field '${record.name}.${field.name}': { $key } duplicates the primary key; dropped",
                    field.nameSpan,
                    help = "remove the option; the primary key already enforces it",
                )
            }
            !redundant
        }

    /**
     * Everything [field] adds to the table [ctx] names, after [strategyOf] its override. A
     * back-reference adds nothing: the forward reference on the other model holds the key.
     */
    internal fun contribute(ctx: FieldContext, field: Field): Contribution {
        if (field.virtual) return Contribution.NONE
        val strategy = strategyOf(field)
        return when (val type = field.type) {
            is Scalar -> scalarField(ctx, field, strategy, type, null)
            is Ref ->
                when (val target = context.schema.lookup(type.target)) {
                    is EnumType -> scalarField(ctx, field, strategy, null, target)
                    is RecordType -> recordField(ctx, field, strategy, type, target)
                    is UnionType -> context.unionLowering.unionField(ctx, field, strategy, target)
                }
            is ListOf -> context.childTables.listField(ctx, field, strategy, type)
            is MapOf -> context.childTables.mapField(ctx, field, strategy, type)
        }
    }

    /** The `@sql(strategy)` a field asks for, or null when it takes the default. */
    private fun strategyOf(field: Field): String? =
        (field.annotations["sql"]["strategy"] as? AnnotationValue.Name)?.value

    /** A scalar or enum column takes no strategy; any is an error. */
    private fun scalarField(
        ctx: FieldContext,
        field: Field,
        strategy: String?,
        scalar: Scalar?,
        enum: EnumType?,
    ): Contribution {
        if (strategy != null) return forbiddenStrategy(ctx, field, strategy, "a scalar", null)
        return column(ctx, field, scalar, enum)
    }

    /**
     * A reference to a record: the default is a reference for a keyed target and an embed for a
     * keyless one; `{ embed }` on the field copies the target's columns under the field's prefix
     * either way, with no foreign key, a keyed target's key columns among them; `json` lowers the
     * whole reference to jsonb; `table` keeps the default reference for a keyed target and is not
     * allowed for a keyless one or an embedded copy, which have no table to reference.
     */
    private fun recordField(
        ctx: FieldContext,
        field: Field,
        strategy: String?,
        ref: Ref,
        target: RecordType,
    ): Contribution {
        val entry = context.catalog[target.qualifiedName]?.takeUnless { ref.relation.embed }
        return when (strategy) {
            "json" -> context.childTables.json(ctx, field, "model", DROP_JSON_HELP)
            "table" ->
                when {
                    entry != null -> reference(ctx, field, entry)
                    ref.relation.embed ->
                        forbiddenStrategy(ctx, field, "table", "a copy written { embed }", "json")
                    else ->
                        forbiddenStrategy(ctx, field, "table", "a keyless model", "embed or json")
                }
            else ->
                if (entry != null) reference(ctx, field, entry)
                else embed(ctx, field, target, columnOf(field, ctx.where))
        }
    }

    /** The error a strategy a shape forbids reports; [alternatives] is null for a scalar. */
    internal fun forbiddenStrategy(
        ctx: FieldContext,
        field: Field,
        strategy: String,
        shape: String,
        alternatives: String?,
    ): Contribution {
        context.error(
            SqlCodes.STRATEGY_NOT_ALLOWED,
            "${ctx.where}: ${if (strategy == "embed") "{ embed }" else "strategy '$strategy'"} is not allowed for $shape",
            field.span,
            help =
                if (alternatives != null) "use $alternatives" else "remove the strategy annotation",
        )
        return Contribution.NONE
    }

    /**
     * A shape with no relational form at all — a list of unions, nested lists, or nested maps, or a
     * union with a union member — regardless of whether the default or an explicit strategy asked
     * for one; naming a strategy the field never wrote would be misleading, so this names the shape
     * instead.
     */
    internal fun noRelationalMapping(ctx: FieldContext, field: Field, shape: String): Contribution {
        context.error(
            SqlCodes.STRATEGY_NOT_ALLOWED,
            "${ctx.where}: $shape has no relational mapping",
            field.span,
            help = "add `@sql(strategy: json)` to store the field as jsonb",
        )
        return Contribution.NONE
    }

    /** The validated `@sql(column)` override for [field], or its own name; reported once. */
    internal fun columnOf(field: Field, where: String): String =
        Naming.columnOf(
            field,
            context.overrides.overrideName(field.annotations, where, field.nameSpan, key = "column"),
        )

    /**
     * A field's final column name and its pre-truncation form, the latter used to name the checks
     * it adds. The owning record's own key fields reuse the [Catalog]'s already-validated
     * resolution of both, so an empty `@sql(column)` override is reported once even though a key
     * field feeds both its own column and every check built from it, and so the owning table and
     * every table that copies the key agree on the column's name (its type agrees because [keyType]
     * screens the pattern as [column] does); a child table's synthetic `value` field never matches,
     * since its table is never the owner's own.
     */
    internal fun columnNames(ctx: FieldContext, field: Field): Pair<String, String> {
        val owner =
            if (ctx.prefix.isEmpty() && ctx.embedding.size == 1) {
                context.catalog[ctx.embedding.single()]?.takeIf { it.tableName == ctx.table }
            } else null
        val keyIndex = owner?.keyFields?.indexOf(field) ?: -1
        if (keyIndex >= 0) return owner!!.keyColumns[keyIndex] to owner.keyColumnsRaw[keyIndex]
        val raw = ctx.prefix + columnOf(field, ctx.where)
        return context.identifier(raw, field.nameSpan) to raw
    }

    /**
     * [refinements] with a pattern Postgres's ARE dialect cannot express replaced by null, reported
     * once at [where]; refinements with no pattern, or one ARE accepts, pass through unchanged.
     */
    internal fun screenPattern(where: String, span: Span, refinements: Refinements): Refinements {
        val pattern = refinements.pattern ?: return refinements
        val bad = PostgresPattern.firstUnsupported(pattern) ?: return refinements
        context.error(
            SqlCodes.LOSSY,
            "$where: pattern uses $bad, which Postgres regexes cannot express; dropped",
            span,
            help = "rewrite the pattern without $bad, or enforce it in application code",
        )
        return refinements.copy(pattern = null)
    }

    /** One column for a scalar or enum field, with its checks, unique, and index. */
    internal fun column(
        ctx: FieldContext,
        field: Field,
        scalar: Scalar?,
        enum: EnumType?,
    ): Contribution {
        val (name, rawName) = columnNames(ctx, field)
        val override = field.annotations.string("sql", "type")
        val mapped =
            if (scalar != null) {
                val refinements = screenPattern(ctx.where, field.nameSpan, scalar.refinements)
                val precision = refinements.precision
                if (
                    override == null &&
                        precision != null &&
                        precision > SqlTypes.NUMERIC_PRECISION_LIMIT
                ) {
                    context.error(
                        SqlCodes.TYPE_LIMIT,
                        "${ctx.where}: decimal precision $precision exceeds Postgres's limit of ${SqlTypes.NUMERIC_PRECISION_LIMIT}",
                        field.span,
                        help = "use a precision of at most ${SqlTypes.NUMERIC_PRECISION_LIMIT}",
                    )
                    return Contribution.NONE
                }
                val typed = SqlTypes.scalar(scalar.copy(refinements = refinements), name)
                // An `@sql(type)` override replaces the type, so a string's max can no longer
                // ride on `varchar(n)`: it is spelled out as a CHECK like any other bound.
                if (override != null && scalar.builtin == Builtin.STRING)
                    SqlTypes.Mapped(typed.type, SqlTypes.stringChecks(name, refinements))
                else typed
            } else {
                SqlTypes.enum(enum!!.values.map { it.name }, name)
            }
        val column =
            Column(
                name = name,
                type = override?.let { ColumnType.RAW(it) } ?: mapped.type,
                nullable = field.nullable || ctx.forceNullable,
                default = field.default?.let { Naming.literal(it) },
                doc = field.doc,
                notes =
                    if (override != null) listOf(TypeText.of(field.type, field.nullable))
                    else emptyList(),
                origin = ctx.columnOrigin(field),
                span = field.nameSpan,
            )
        return Contribution(
            columns = listOf(column),
            checks =
                mapped.checks.map { (suffix, expression) ->
                    Check(
                        context.identifier("ck_${ctx.table}_${rawName}_$suffix", field.nameSpan),
                        expression,
                    )
                },
            uniques = uniqueOf(ctx, field, rawName, listOf(name)),
            indexes = indexOf(ctx, field, rawName, listOf(name)),
            required = if (field.nullable) emptyList() else listOf(name),
        )
    }

    /**
     * A reference to a keyed record: one column per key column of the target, named `<field>_<key
     * column>` and typed like it, plus a foreign key to the target's table that acts on delete as
     * the reference's `@relation(onDelete: …)` says. A nullable reference over a composite key adds
     * a CHECK that its columns are all null or all set.
     */
    internal fun reference(ctx: FieldContext, field: Field, entry: Catalog.Entry): Contribution {
        val rawName = ctx.prefix + columnOf(field, ctx.where)
        val columns =
            entry.keyFields.zip(entry.keyColumns).mapNotNull { (key, keyColumn) ->
                val type = keyType(key) ?: return@mapNotNull null
                Column(
                    name = context.identifier("${rawName}_$keyColumn", field.nameSpan),
                    type = type,
                    nullable = field.nullable || ctx.forceNullable,
                    doc = field.doc,
                    origin = ctx.columnOrigin(field, "k${key.ordinal}"),
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
                onDelete = (field.type as? Ref)?.relation?.onDelete ?: OnDelete.RESTRICT,
            )
        // A composite foreign key with only some of its columns null is not checked at all, so
        // a nullable reference over more than one column is all-or-none.
        val present =
            if ((field.nullable || ctx.forceNullable) && names.size > 1) {
                val allNull = names.joinToString(" AND ") { "${Naming.quote(it)} IS NULL" }
                val allSet = names.joinToString(" AND ") { "${Naming.quote(it)} IS NOT NULL" }
                listOf(
                    Check(
                        context.identifier("ck_${ctx.table}_${rawName}_present", field.nameSpan),
                        "(($allNull) OR ($allSet))",
                    )
                )
            } else emptyList()
        return Contribution(
            columns = columns,
            checks = present,
            uniques = uniqueOf(ctx, field, rawName, names),
            indexes = indexOf(ctx, field, rawName, names),
            foreignKeys = listOf(PendingForeignKey(fk, context.namespace.name, entry.namespace)),
            required = if (field.nullable) emptyList() else names,
        )
    }

    /**
     * A reference to a keyless record: its own columns are embedded under `<field>_`, recursively,
     * since a keyless record is a value type rather than a table of its own. Defaults, docs, and
     * constraint names all carry over, renamed to the embedded columns. A nullable embed forces
     * every produced column nullable and, when two or more of them would otherwise be required,
     * adds one CHECK that those are all present or all absent together. `{ unique }` or `{ index }`
     * on the field itself covers every column it produced. Embedding the same record again inside
     * itself is reported instead of recursing forever.
     */
    internal fun embed(
        ctx: FieldContext,
        field: Field,
        target: RecordType,
        rawName: String,
    ): Contribution {
        if (recursionError(ctx, field, target)) return Contribution.NONE
        val inner =
            ctx.nested(
                rawName,
                field.nullable,
                target.qualifiedName,
                ctx.where,
                OriginStep.FieldOrdinal(field.ordinal),
            )
        val parts =
            target.fields.map {
                contribute(inner.copy(where = "field '${target.name}.${it.name}'"), it)
            }
        val own = merge(parts)
        val outerRaw = ctx.prefix + rawName
        val names = own.columns.map { it.name }
        val merged =
            own.copy(
                uniques = own.uniques + uniqueOf(ctx, field, outerRaw, names),
                indexes = own.indexes + indexOf(ctx, field, outerRaw, names),
            )
        if (!field.nullable) return merged
        val required = merged.required
        val cleared = merged.copy(required = emptyList())
        // One column's all-or-none is always true once it is forced nullable.
        if (required.size < 2) return cleared
        val allNull = required.joinToString(" AND ") { "${Naming.quote(it)} IS NULL" }
        val allSet = required.joinToString(" AND ") { "${Naming.quote(it)} IS NOT NULL" }
        val present =
            Check(
                context.identifier("ck_${ctx.table}_${outerRaw}_present", field.nameSpan),
                "(($allNull) OR ($allSet))",
            )
        return cleared.copy(checks = cleared.checks + present)
    }

    /**
     * True, having reported [SqlCodes.RECURSIVE_EMBED], when embedding [target] here would recurse
     * forever: [target] is already somewhere in [ctx]'s embedding chain, whether that chain got
     * here through nested value types or through child tables.
     */
    internal fun recursionError(ctx: FieldContext, field: Field, target: RecordType): Boolean {
        if (target.qualifiedName !in ctx.embedding) return false
        val cycle =
            (ctx.embedding.dropWhile { it != target.qualifiedName } + target.qualifiedName)
                .joinToString(" → ") { it.simpleName }
        context.error(
            SqlCodes.RECURSIVE_EMBED,
            "${ctx.where}: embedding '${target.name}' here would recurse ($cycle)",
            field.span,
            help =
                if (context.catalog[target.qualifiedName] != null)
                    "reference '${target.name}' by key instead of embedding it: remove `{ embed }` from this field, or use `@sql(strategy: json)`"
                else
                    "use `@sql(strategy: json)` on this field, or give '${target.name}' a key so it becomes a table",
        )
        return true
    }

    /** Every list of a set of contributions, concatenated in order. */
    internal fun merge(parts: List<Contribution>): Contribution =
        Contribution(
            columns = parts.flatMap { it.columns },
            checks = parts.flatMap { it.checks },
            uniques = parts.flatMap { it.uniques },
            indexes = parts.flatMap { it.indexes },
            foreignKeys = parts.flatMap { it.foreignKeys },
            children = parts.flatMap { it.children },
            required = parts.flatMap { it.required },
        )

    /**
     * The unique [field] asks for over [columns], if any, through `{ unique }`. A list or map field
     * never gets one here: a list of a keyed model puts its set constraint on its child table, and
     * the analyzer has reported the option on any other.
     */
    internal fun uniqueOf(ctx: FieldContext, field: Field, rawName: String, columns: List<String>) =
        if (field.unique && constrainable(field, columns))
            listOf(Unique(context.identifier("uq_${ctx.table}_$rawName", field.nameSpan), columns))
        else emptyList()

    /** The index [field] asks for over [columns], if any, on the same terms as [uniqueOf]. */
    internal fun indexOf(ctx: FieldContext, field: Field, rawName: String, columns: List<String>) =
        if (field.index && constrainable(field, columns))
            listOf(Index(context.identifier("ix_${ctx.table}_$rawName", field.nameSpan), columns))
        else emptyList()

    private fun constrainable(field: Field, columns: List<String>): Boolean =
        columns.isNotEmpty() && field.type !is ListOf && field.type !is MapOf
}
