package io.schemata.target.sql

import io.schemata.core.ir.AnnotationValue
import io.schemata.core.ir.Builtin
import io.schemata.core.ir.EnumType
import io.schemata.core.ir.Field
import io.schemata.core.ir.ListOf
import io.schemata.core.ir.MapOf
import io.schemata.core.ir.Namespace
import io.schemata.core.ir.RecordType
import io.schemata.core.ir.Ref
import io.schemata.core.ir.Refinements
import io.schemata.core.ir.Scalar
import io.schemata.core.ir.Schema
import io.schemata.core.ir.Type
import io.schemata.core.ir.UnionMember
import io.schemata.core.ir.UnionType
import io.schemata.core.ir.selfAndNested
import io.schemata.lang.Diagnostic
import io.schemata.lang.DiagnosticCode
import io.schemata.lang.Span
import io.schemata.target.Lowered
import io.schemata.target.TypeText

/**
 * Lowers records to tables. A record has a table exactly when it has a key; a keyless record is a
 * value type that only appears where a field uses it. Lowering runs in two passes: a [Catalog] of
 * every keyed record's table and key columns, then each field's [Contribution] to its table.
 * References to keyed records become key columns and a foreign key; a reference to a keyless record
 * embeds that record's own columns under `<field>_`, recursively. Scalars carry every builtin,
 * refinements as CHECK constraints, defaults, enums as constrained text, and `@sql` overrides. A
 * reference to a union becomes a `<field>_kind` discriminator column plus each member's own nested,
 * forced-nullable contribution, with a CHECK that a member's columns are present exactly when the
 * kind names it. `@sql(strategy)` overrides a field's default shape with `embed`, `table`, or
 * `json` wherever the matrix allows it; a strategy a shape forbids, or any strategy at all on a
 * scalar, is an error. `reserved` ordinals and names have no relational meaning and are accepted
 * without a diagnostic.
 */
object SqlLowering {
    fun lower(schema: Schema): Lowered<RelationalModel> {
        val diagnostics = mutableListOf<Diagnostic>()
        val schemaNames =
            schema.namespaces.associate {
                it.name to identifier(Naming.schemaOf(it), it.span, diagnostics)
            }
        schemaCollisions(schema.namespaces, schemaNames, diagnostics)
        val catalog =
            Catalog(schema, schemaNames) { name, span -> identifier(name, span, diagnostics) }
        val lowered =
            schema.namespaces.map {
                NamespaceLowering(schema, catalog, it, schemaNames.getValue(it.name), diagnostics)
                    .lower()
            }
        return Lowered(RelationalModel(placeForeignKeys(schema, lowered)), diagnostics)
    }

    /**
     * A foreign key is emitted by the file that sorts later of the two it links, so every table it
     * names already exists when the files are applied in path order. Within a file, its own keys
     * come first in table and field order, then the keys moved in from other files.
     */
    private fun placeForeignKeys(
        schema: Schema,
        lowered: List<Pair<RelationalSchema, List<PendingForeignKey>>>,
    ): List<RelationalSchema> {
        val pending = lowered.flatMap { it.second }
        val paths = schema.namespaces.associate { it.name to pathOf(it) }
        return schema.namespaces.zip(lowered).map { (namespace, part) ->
            val relational = part.first
            val mine =
                pending.filter { fk ->
                    val source = paths.getValue(fk.sourceNamespace)
                    val target = paths.getValue(fk.targetNamespace)
                    maxOf(source, target) == relational.path
                }
            val (own, moved) = mine.partition { it.sourceNamespace == namespace.name }
            relational.copy(foreignKeys = (own + moved).map { it.fk })
        }
    }

    private fun pathOf(namespace: Namespace): String = namespace.name.replace('.', '/') + ".sql"

    private class NamespaceLowering(
        private val schema: Schema,
        private val catalog: Catalog,
        private val namespace: Namespace,
        private val schemaName: String,
        private val diagnostics: MutableList<Diagnostic>,
    ) {
        /** Every name a table puts in the schema's relation namespace, with where it came from. */
        private val relations = mutableListOf<Relation>()

        /** Every table name claimed so far, with the claim that took it first. */
        private val claimedTables = mutableMapOf<String, TableClaim>()

        /**
         * Every record in the namespace, top-level or nested, each top-level declaration's tree in
         * order: a record first, then the records declared inside it.
         */
        private val records: List<RecordType> =
            namespace.declarations.flatMap { it.selfAndNested() }.filterIsInstance<RecordType>()

        /**
         * Every keyed record has a table, whether it is declared at the top level or nested inside
         * another record; a nested keyed record's table follows its parent's table and children.
         * Keyless records, top-level or nested, are value types, each reported if unused.
         */
        fun lower(): Pair<RelationalSchema, List<PendingForeignKey>> {
            tableCollisions()
            val tables = mutableListOf<Table>()
            val foreignKeys = mutableListOf<PendingForeignKey>()
            records.forEach { r ->
                if (catalog[r.qualifiedName] != null) {
                    val part = record(r)
                    tables += part.table
                    tables += part.children.map { it.table }
                    foreignKeys += part.foreignKeys
                    foreignKeys += part.children.flatMap { it.foreignKeys }
                } else if (r.qualifiedName !in catalog.used) {
                    error(
                        SqlCodes.MISSING_KEY,
                        "record '${r.name}' has no primary key and is not used by any field; mark key fields with @sql(key) or the record with @sql(key = (...))",
                        r.nameSpan,
                    )
                }
            }
            relationCollisions()
            return RelationalSchema(pathOf(namespace), schemaName, tables) to foreignKeys
        }

        /**
         * Table collisions are reported over final (overridden) names, before any record lowers.
         * Only keyed records have tables, nested ones included.
         */
        private fun tableCollisions() {
            records
                .filter { catalog[it.qualifiedName] != null }
                .groupBy { Naming.tableOf(it) }
                .values
                .filter { it.size > 1 }
                .forEach { colliding ->
                    diagnostics +=
                        Diagnostic(
                            SqlCodes.TABLE_COLLISION,
                            "records ${englishList(colliding.map { it.name })} ${if (colliding.size > 2) "all" else "both"} lower to table '${Naming.tableOf(colliding.first())}'",
                            colliding.first().span,
                        )
                }
        }

        /**
         * Tables, primary keys, uniques, and indexes share one Postgres namespace per schema, so a
         * derived name such as `uq_order_line_id` can be claimed by two tables. Records that lower
         * to the same table are already reported by [tableCollisions]; only the first of them
         * contributes names here.
         */
        private fun relationCollisions() {
            val holders = mutableMapOf<String, Relation>()
            relations.forEach { relation ->
                val previous = holders.putIfAbsent(relation.name, relation) ?: return@forEach
                error(
                    SqlCodes.NAME_COLLISION,
                    "relation name '${relation.name}' is already used by ${previous.kind} (${previous.span.file}:${previous.span.startLine})",
                    relation.span,
                )
            }
        }

        /** A keyed record's table, the child tables its fields produce, and its foreign keys. */
        private class RecordTables(
            val table: Table,
            val children: List<ChildTable>,
            val foreignKeys: List<PendingForeignKey>,
        )

        private fun record(record: RecordType): RecordTables {
            val entry = catalog[record.qualifiedName]!!
            val tableName = entry.tableName
            keys(record)
            val ctx =
                FieldContext(
                    table = tableName,
                    embedding = listOf(record.qualifiedName),
                    parentTable = tableName,
                    parentKeys =
                        entry.keyFields.zip(entry.keyColumns).mapNotNull { (key, column) ->
                            keyType(key)?.let { column to it }
                        },
                    where = "",
                )
            val parts =
                record.fields.map { field ->
                    field to
                        contribute(ctx.copy(where = "field '${record.name}.${field.name}'"), field)
                }
            columnCollisions(
                parts.map { (field, part) ->
                    ColumnSource(
                        "field '${record.name}.${field.name}'",
                        "field '${field.name}'",
                        field.nameSpan,
                        part.columns.map { it.name },
                    )
                }
            )
            val columns = parts.flatMap { (_, part) -> part.columns }
            val primaryKey = entry.keyColumns.filter { key -> columns.any { it.name == key } }
            val primaryKeyName =
                if (primaryKey.isEmpty()) null else identifier("pk_$tableName", record.nameSpan)
            val uniques =
                constraints(record, "unique", owned(parts) { it.uniques }, primaryKey) {
                    it.columns
                }
            val indexes =
                constraints(record, "index", owned(parts) { it.indexes }, primaryKey) { it.columns }
            claim(
                TableClaim("table '$tableName'", record.nameSpan, Naming.tableOf(record)),
                tableName,
                primaryKeyName,
                uniques,
                indexes,
            )
            parts.forEach { (field, part) ->
                part.children.forEach { child ->
                    val t = child.table
                    claim(
                        TableClaim("child table '${t.name}'", field.nameSpan, null),
                        t.name,
                        t.primaryKeyName,
                        t.uniques.map { field to it },
                        t.indexes.map { field to it },
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

        /**
         * Something that puts [columns] on a table: [subject] names it when it is the later of two
         * claimants of a column name, [holder] when it is the earlier, and [span] locates it.
         */
        private class ColumnSource(
            val subject: String,
            val holder: String,
            val span: Span,
            val columns: List<String>,
        )

        /** Two sources whose columns land on the same final name, reported at the later one. */
        private fun columnCollisions(sources: List<ColumnSource>) {
            val seen = mutableMapOf<String, ColumnSource>()
            sources.forEach { source ->
                source.columns.forEach { column ->
                    val previous = seen.putIfAbsent(column, source)
                    if (previous != null) {
                        error(
                            SqlCodes.NAME_COLLISION,
                            "${source.subject} lowers to column '$column', already used by ${previous.holder} (${previous.span.file}:${previous.span.startLine})",
                            source.span,
                        )
                    }
                }
            }
        }

        /**
         * Reports the key's form: the `@sql(key)` fields in declaration order, or the fields a
         * record-level `@sql(key = (...))` names, each once. Only keyed records reach here; the key
         * itself comes from the [Catalog].
         */
        private fun keys(record: RecordType) {
            val fieldKeys = record.fields.filter { "key" in it.annotations["sql"] }
            val recordKeyNames =
                (record.annotations["sql"]["key"] as? AnnotationValue.Names)?.values
            if (fieldKeys.isNotEmpty() && recordKeyNames != null) {
                error(
                    SqlCodes.KEY_COLUMN,
                    "record '${record.name}' declares @sql(key) on both the record and its fields",
                    record.nameSpan,
                )
            }
            recordKeyNames
                ?.filter { name -> record.fields.none { it.name == name } }
                ?.forEach {
                    error(
                        SqlCodes.KEY_COLUMN,
                        "record '${record.name}': @sql(key) names '$it', which is not a field of the record",
                        record.nameSpan,
                    )
                }
            recordKeyNames
                ?.groupingBy { it }
                ?.eachCount()
                ?.filterValues { it > 1 }
                ?.keys
                ?.forEach {
                    error(
                        SqlCodes.KEY_COLUMN,
                        "record '${record.name}': @sql(key) names '$it' more than once",
                        record.nameSpan,
                    )
                }
            val keyFields = catalog[record.qualifiedName]!!.keyFields
            keyFields
                .filter { it.nullable }
                .forEach {
                    error(
                        SqlCodes.KEY_COLUMN,
                        "record '${record.name}': key field '${it.name}' is nullable; a primary key column cannot be",
                        it.nameSpan,
                    )
                }
            keyFields
                .filter { keyType(it) == null }
                .forEach {
                    error(
                        SqlCodes.KEY_COLUMN,
                        "record '${record.name}': key field '${it.name}' must be a scalar column",
                        it.nameSpan,
                    )
                }
        }

        /**
         * The column type a key field has, which a reference to its record copies; null when the
         * field is not a single scalar column (a builtin or an enum).
         */
        private fun keyType(field: Field): ColumnType? {
            val override = Naming.override(field.annotations, "type")
            return when (val type = field.type) {
                is Scalar ->
                    override?.let { ColumnType.RAW(it) }
                        ?: SqlTypes.scalar(type, field.name, overridden = false).type
                is Ref ->
                    when (val target = schema.lookup(type.target)) {
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
         * A unique or index over exactly the primary-key columns adds nothing the key does not
         * already enforce, so it is dropped with a warning.
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
                    error(
                        SqlCodes.REDUNDANT_CONSTRAINT,
                        "field '${record.name}.${field.name}': @sql($key) duplicates the primary key; dropped",
                        field.nameSpan,
                    )
                }
                !redundant
            }

        /**
         * Who claims a table name: [kind] names it in messages, and [recordTable] is the record's
         * own table name before truncation, or null for a child table.
         */
        private class TableClaim(val kind: String, val span: Span, val recordTable: String?)

        /**
         * Records a table's names for [relationCollisions]. Two records that lower to the same
         * table are a table collision, already reported by [tableCollisions]; any other second
         * claim on a table name (a child table against a record's table, or two child tables) is a
         * name collision reported here, at the later claimant. Either way only the first claimant
         * contributes names.
         */
        private fun claim(
            claimant: TableClaim,
            tableName: String,
            primaryKeyName: String?,
            uniques: List<Pair<Field, Unique>>,
            indexes: List<Pair<Field, Index>>,
        ) {
            val previous = claimedTables.putIfAbsent(tableName, claimant)
            if (previous != null) {
                val sameRecordTable =
                    claimant.recordTable != null && claimant.recordTable == previous.recordTable
                if (!sameRecordTable) {
                    error(
                        SqlCodes.NAME_COLLISION,
                        "relation name '$tableName' is already used by ${previous.kind} (${previous.span.file}:${previous.span.startLine})",
                        claimant.span,
                    )
                }
                return
            }
            val span = claimant.span
            relations += Relation(tableName, claimant.kind, span)
            primaryKeyName?.let { relations += Relation(it, "primary key of '$tableName'", span) }
            uniques.forEach { (field, u) ->
                relations += Relation(u.name, "unique '${u.name}'", field.nameSpan)
            }
            indexes.forEach { (field, ix) ->
                relations += Relation(ix.name, "index '${ix.name}'", field.nameSpan)
            }
        }

        /** Everything [field] adds to the table [ctx] names, after [strategyOf] its override. */
        private fun contribute(ctx: FieldContext, field: Field): Contribution {
            val strategy = strategyOf(field)
            return when (val type = field.type) {
                is Scalar -> scalarField(ctx, field, strategy, type, null)
                is Ref ->
                    when (val target = schema.lookup(type.target)) {
                        is EnumType -> scalarField(ctx, field, strategy, null, target)
                        is RecordType -> recordField(ctx, field, strategy, target)
                        is UnionType -> unionField(ctx, field, strategy, target)
                    }
                is ListOf -> listField(ctx, field, strategy, type)
                is MapOf -> mapField(ctx, field, strategy, type)
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
         * keyless one. `embed` flattens either kind's columns under the prefix with no foreign key,
         * even a keyed target's own key columns; `json` lowers the whole reference to jsonb;
         * `table` keeps the default reference for a keyed target and is not allowed for a keyless
         * one, which has no table to reference.
         */
        private fun recordField(
            ctx: FieldContext,
            field: Field,
            strategy: String?,
            target: RecordType,
        ): Contribution {
            val entry = catalog[target.qualifiedName]
            return when (strategy) {
                "json" -> json(ctx, field, "record")
                "embed" -> embed(ctx, field, target, Naming.columnOf(field))
                "table" ->
                    if (entry != null) reference(ctx, field, entry)
                    else forbiddenStrategy(ctx, field, "table", "a keyless record", "embed or json")
                else ->
                    if (entry != null) reference(ctx, field, entry)
                    else embed(ctx, field, target, Naming.columnOf(field))
            }
        }

        /**
         * A reference to a union: the default and `embed` both lower it to a discriminator plus
         * each member's own columns; `json` lowers the whole union to jsonb instead, the only
         * strategy that also works for a union with a member that is itself a union; `table` has no
         * meaning for a union.
         */
        private fun unionField(
            ctx: FieldContext,
            field: Field,
            strategy: String?,
            type: UnionType,
        ): Contribution =
            when (strategy) {
                "json" -> json(ctx, field, "union")
                "table" -> forbiddenStrategy(ctx, field, "table", "a union", "embed or json")
                else -> union(ctx, field, type)
            }

        /**
         * A list: the default is an array for a scalar or enum element and a child table for a
         * record one; `table` asks for a child table either way, with a single `value` column
         * carrying a scalar or enum element's own checks instead of an array's stripped bounds;
         * `json` lowers the whole list to jsonb, the only strategy that reaches a union, nested
         * list, or nested map element; `embed` has no meaning for a list.
         */
        private fun listField(
            ctx: FieldContext,
            field: Field,
            strategy: String?,
            type: ListOf,
        ): Contribution {
            if (strategy == "embed") {
                return forbiddenStrategy(ctx, field, "embed", "a list", "table or json")
            }
            if (strategy == "json") return json(ctx, field, "list")
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
                    when (val elementTarget = schema.lookup(element.target)) {
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
                        is UnionType -> noRelationalMapping(ctx, field, "a list of unions")
                    }
                is ListOf,
                is MapOf -> noRelationalMapping(ctx, field, "a list of lists or maps")
            }
        }

        /**
         * A map: the default and `json` both lower it to jsonb, since Postgres has no typed map;
         * `table` asks for a child table keyed by the parent and the map's own key, with the value
         * lowered the way a list's scalar, enum, or record element is, under a `value` column;
         * `embed` has no meaning for a map.
         */
        private fun mapField(
            ctx: FieldContext,
            field: Field,
            strategy: String?,
            type: MapOf,
        ): Contribution {
            if (strategy == "embed") {
                return forbiddenStrategy(ctx, field, "embed", "a map", "table or json")
            }
            if (strategy == null || strategy == "json") return json(ctx, field, "map")
            // The resolver only admits string, int32, and int64 keys; the fallback is never taken.
            val key = type.key as? Scalar ?: Scalar(Builtin.STRING)
            return when (val value = type.value) {
                is Scalar ->
                    child(
                        ctx,
                        field,
                        type.refinements,
                        Element.Scalar(value),
                        type.nullableValue,
                        key,
                    )
                is Ref ->
                    when (val valueTarget = schema.lookup(value.target)) {
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
                            forbiddenStrategy(ctx, field, "table", "a map of unions", "json")
                    }
                is ListOf,
                is MapOf -> forbiddenStrategy(ctx, field, "table", "a map of lists or maps", "json")
            }
        }

        /** The error a strategy a shape forbids reports; [alternatives] is null for a scalar. */
        private fun forbiddenStrategy(
            ctx: FieldContext,
            field: Field,
            strategy: String,
            shape: String,
            alternatives: String?,
        ): Contribution {
            val suffix = if (alternatives != null) "; use $alternatives" else "; remove it"
            error(
                SqlCodes.STRATEGY_NOT_ALLOWED,
                "${ctx.where}: strategy '$strategy' is not allowed for $shape$suffix",
                field.span,
            )
            return Contribution.NONE
        }

        /**
         * A list element with no relational form at all — a union, a nested list, or a nested map —
         * regardless of whether the default or an explicit `table` asked for one; only naming a
         * strategy the field never wrote would be misleading, so this names the shape instead.
         */
        private fun noRelationalMapping(
            ctx: FieldContext,
            field: Field,
            shape: String,
        ): Contribution {
            error(
                SqlCodes.STRATEGY_NOT_ALLOWED,
                "${ctx.where}: $shape has no relational mapping; use strategy = json",
                field.span,
            )
            return Contribution.NONE
        }

        /**
         * A field's final column name. The owning record's own key fields take the name the
         * [Catalog] already derived, so a truncation is not reported twice; a child table's
         * synthetic `value` field never matches, since its table is never the owner's own.
         */
        private fun columnName(ctx: FieldContext, field: Field): String {
            val owner =
                if (ctx.prefix.isEmpty() && ctx.embedding.size == 1) {
                    catalog[ctx.embedding.single()]?.takeIf { it.tableName == ctx.table }
                } else null
            val keyIndex = owner?.keyFields?.indexOf(field) ?: -1
            return if (keyIndex >= 0) owner!!.keyColumns[keyIndex]
            else identifier(ctx.prefix + Naming.columnOf(field), field.nameSpan)
        }

        /** One column for a scalar or enum field, with its checks, unique, and index. */
        private fun column(
            ctx: FieldContext,
            field: Field,
            scalar: Scalar?,
            enum: EnumType?,
        ): Contribution {
            val rawName = ctx.prefix + Naming.columnOf(field)
            val name = columnName(ctx, field)
            val override = Naming.override(field.annotations, "type")
            val mapped =
                if (scalar != null) {
                    val precision = scalar.refinements.precision
                    if (
                        override == null &&
                            precision != null &&
                            precision > SqlTypes.NUMERIC_PRECISION_LIMIT
                    ) {
                        error(
                            SqlCodes.TYPE_LIMIT,
                            "${ctx.where}: decimal precision $precision exceeds Postgres's limit of ${SqlTypes.NUMERIC_PRECISION_LIMIT}",
                            field.span,
                        )
                        return Contribution.NONE
                    }
                    SqlTypes.scalar(scalar, name, overridden = override != null)
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
                )
            return Contribution(
                columns = listOf(column),
                checks =
                    mapped.checks.map { (suffix, expression) ->
                        Check(
                            identifier("ck_${ctx.table}_${rawName}_$suffix", field.nameSpan),
                            expression,
                        )
                    },
                uniques = uniqueOf(ctx, field, rawName, listOf(name)),
                indexes = indexOf(ctx, field, rawName, listOf(name)),
                required = if (field.nullable) emptyList() else listOf(name),
            )
        }

        /**
         * A reference to a keyed record: one column per key column of the target, named
         * `<field>_<key column>` and typed like it, plus a foreign key to the target's table.
         */
        private fun reference(ctx: FieldContext, field: Field, entry: Catalog.Entry): Contribution {
            val rawName = ctx.prefix + Naming.columnOf(field)
            val columns =
                entry.keyFields.zip(entry.keyColumns).mapNotNull { (key, keyColumn) ->
                    val type = keyType(key) ?: return@mapNotNull null
                    Column(
                        name = identifier("${rawName}_$keyColumn", field.nameSpan),
                        type = type,
                        nullable = field.nullable || ctx.forceNullable,
                        doc = field.doc,
                    )
                }
            if (columns.isEmpty()) return Contribution.NONE
            val names = columns.map { it.name }
            val fk =
                ForeignKey(
                    name = identifier("fk_${ctx.table}_$rawName", field.nameSpan),
                    schema = schemaName,
                    table = ctx.table,
                    columns = names,
                    targetSchema = entry.schemaName,
                    targetTable = entry.tableName,
                    targetColumns = entry.keyColumns,
                    cascade = false,
                )
            return Contribution(
                columns = columns,
                uniques = uniqueOf(ctx, field, rawName, names),
                indexes = indexOf(ctx, field, rawName, names),
                foreignKeys = listOf(PendingForeignKey(fk, namespace.name, entry.namespace)),
                required = if (field.nullable) emptyList() else names,
            )
        }

        /**
         * A reference to a keyless record: its own columns are embedded under `<field>_`,
         * recursively, since a keyless record is a value type rather than a table of its own.
         * Defaults, docs, and constraint names all carry over, renamed to the embedded columns. A
         * nullable embed forces every produced column nullable and adds one CHECK that the columns
         * which would otherwise be required are all present or all absent together; embedding the
         * same record again inside itself is reported instead of recursing forever.
         */
        private fun embed(
            ctx: FieldContext,
            field: Field,
            target: RecordType,
            rawName: String,
        ): Contribution {
            if (recursionError(ctx, field, target)) return Contribution.NONE
            val inner = ctx.nested(rawName, field.nullable, target.qualifiedName, ctx.where)
            val parts =
                target.fields.map {
                    contribute(inner.copy(where = "field '${target.name}.${it.name}'"), it)
                }
            val merged = merge(parts)
            if (!field.nullable) return merged
            val required = merged.required
            val cleared = merged.copy(required = emptyList())
            if (required.isEmpty()) return cleared
            val allNull = required.joinToString(" AND ") { "${Naming.quote(it)} IS NULL" }
            val allSet = required.joinToString(" AND ") { "${Naming.quote(it)} IS NOT NULL" }
            val present =
                Check(
                    identifier("ck_${ctx.table}_${ctx.prefix}${rawName}_present", field.nameSpan),
                    "(($allNull) OR ($allSet))",
                )
            return cleared.copy(checks = cleared.checks + present)
        }

        /**
         * True, having reported [SqlCodes.RECURSIVE_EMBED], when embedding [target] here would
         * recurse forever: [target] is already somewhere in [ctx]'s embedding chain, whether that
         * chain got here through nested value types or through child tables.
         */
        private fun recursionError(ctx: FieldContext, field: Field, target: RecordType): Boolean {
            if (target.qualifiedName !in ctx.embedding) return false
            val cycle =
                (ctx.embedding.dropWhile { it != target.qualifiedName } + target.qualifiedName)
                    .joinToString(" → ") { it.simpleName }
            error(
                SqlCodes.RECURSIVE_EMBED,
                "${ctx.where}: embedding '${target.name}' here would recurse ($cycle); use strategy = json or give '${target.name}' a key",
                field.span,
            )
            return true
        }

        /**
         * A reference to a union: a `<field>_kind` text column naming which member is present, a
         * CHECK constraining it to the member names, and each member's own contribution nested
         * under `<field>_<member>`, every column forced nullable since only the member the kind
         * names is ever populated. A member's own required columns (the ones that would be NOT NULL
         * on their own account) back a second CHECK that they are all present exactly when the kind
         * names that member; a member with none (a keyless record with no fields) needs no such
         * check. The union's own [Contribution.required] names only the kind column: a member's
         * columns never make the enclosing table's presence checks, since a member is optional by
         * construction and its own CHECK already enforces it. A member that is itself a union has
         * no kind column of its own to nest a second one under, so it has no embed strategy and
         * must be lowered with `strategy = json` instead (SCH-28).
         */
        private fun union(ctx: FieldContext, field: Field, type: UnionType): Contribution {
            if (
                type.members.any {
                    it.type is Ref && schema.lookup((it.type as Ref).target) is UnionType
                }
            ) {
                error(
                    SqlCodes.STRATEGY_NOT_ALLOWED,
                    "${ctx.where}: strategy 'embed' is not allowed for a union whose member is a union; use json",
                    field.span,
                )
                return Contribution.NONE
            }
            val bare = Naming.columnOf(field)
            val outerRaw = ctx.prefix + bare
            val kindName = identifier("${outerRaw}_kind", field.nameSpan)
            val literals = type.members.map { memberLiteral(it.type) }
            val kindColumn =
                Column(
                    name = kindName,
                    type = ColumnType.TEXT,
                    nullable = field.nullable || ctx.forceNullable,
                    doc = field.doc,
                )
            val kindCheck =
                Check(
                    identifier("ck_${ctx.table}_${outerRaw}_kind", field.nameSpan),
                    "${Naming.quote(kindName)} IN (${literals.joinToString(", ") { Naming.literal(it) }})",
                )
            val merged =
                merge(
                    type.members.zip(literals).map { (member, literal) ->
                        unionMember(ctx, field, bare, literal, kindName, member)
                    }
                )
            return Contribution(
                columns = listOf(kindColumn) + merged.columns,
                checks = listOf(kindCheck) + merged.checks,
                uniques = merged.uniques,
                indexes = merged.indexes,
                foreignKeys = merged.foreignKeys,
                children = merged.children,
                required = if (field.nullable) emptyList() else listOf(kindName),
            )
        }

        /** The text a member compares the kind column to, and lists in its `IN (...)` check. */
        private fun memberLiteral(type: Type): String =
            when (type) {
                is Scalar -> type.builtin.typeName
                is Ref -> Naming.snakeCase(type.target.simpleName)
                is ListOf,
                is MapOf -> "member"
            }

        /** One union member's own columns, checks, and foreign keys, named from [literal]. */
        private fun unionMember(
            ctx: FieldContext,
            field: Field,
            bare: String,
            literal: String,
            kindName: String,
            member: UnionMember,
        ): Contribution =
            when (val type = member.type) {
                is Scalar -> unionScalar(ctx, field, bare, literal, kindName, type)
                is Ref ->
                    when (val target = schema.lookup(type.target)) {
                        is EnumType -> unionEnum(ctx, field, bare, literal, kindName, target)
                        is RecordType -> {
                            val entry = catalog[target.qualifiedName]
                            if (entry != null) {
                                unionReference(ctx, field, bare, literal, kindName, entry)
                            } else unionEmbed(ctx, field, bare, literal, kindName, target)
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
            val name = identifier(rawName, field.nameSpan)
            val precision = scalar.refinements.precision
            if (precision != null && precision > SqlTypes.NUMERIC_PRECISION_LIMIT) {
                error(
                    SqlCodes.TYPE_LIMIT,
                    "${ctx.where}: decimal precision $precision exceeds Postgres's limit of ${SqlTypes.NUMERIC_PRECISION_LIMIT}",
                    field.span,
                )
                return Contribution.NONE
            }
            val mapped = SqlTypes.scalar(scalar, name, overridden = false)
            val column = Column(name = name, type = mapped.type, nullable = true, doc = field.doc)
            val checks =
                mapped.checks.map { (suffix, expression) ->
                    Check(
                        identifier("ck_${ctx.table}_${rawName}_$suffix", field.nameSpan),
                        expression,
                    )
                }
            return Contribution(
                columns = listOf(column),
                checks =
                    checks + presenceCheck(ctx, field, kindName, rawName, literal, listOf(name)),
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
            val name = identifier(rawName, field.nameSpan)
            val mapped = SqlTypes.enum(target.values.map { it.name }, name)
            val column = Column(name = name, type = mapped.type, nullable = true, doc = field.doc)
            val checks =
                mapped.checks.map { (suffix, expression) ->
                    Check(
                        identifier("ck_${ctx.table}_${rawName}_$suffix", field.nameSpan),
                        expression,
                    )
                }
            return Contribution(
                columns = listOf(column),
                checks =
                    checks + presenceCheck(ctx, field, kindName, rawName, literal, listOf(name)),
            )
        }

        /**
         * A member that references a keyed record: `<field>_<member>_<key column>` columns and a
         * foreign key, named from the member rather than from the field the way [reference] is.
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
                    val columnType = keyType(key) ?: return@mapNotNull null
                    Column(
                        name = identifier("${rawName}_$keyColumn", field.nameSpan),
                        type = columnType,
                        nullable = true,
                        doc = field.doc,
                    )
                }
            if (columns.isEmpty()) return Contribution.NONE
            val names = columns.map { it.name }
            val fk =
                ForeignKey(
                    name = identifier("fk_${ctx.table}_$rawName", field.nameSpan),
                    schema = schemaName,
                    table = ctx.table,
                    columns = names,
                    targetSchema = entry.schemaName,
                    targetTable = entry.tableName,
                    targetColumns = entry.keyColumns,
                    cascade = false,
                )
            return Contribution(
                columns = columns,
                checks = presenceCheck(ctx, field, kindName, rawName, literal, names),
                foreignKeys = listOf(PendingForeignKey(fk, namespace.name, entry.namespace)),
            )
        }

        /**
         * A member that references a keyless record: its fields embed under `<field>_<member>_`,
         * forced nullable throughout, exactly like [embed] but checked for presence by kind rather
         * than by a nullable embed's own all-or-nothing CHECK.
         */
        private fun unionEmbed(
            ctx: FieldContext,
            field: Field,
            bare: String,
            literal: String,
            kindName: String,
            target: RecordType,
        ): Contribution {
            if (recursionError(ctx, field, target)) return Contribution.NONE
            val rawName = "${ctx.prefix}${bare}_$literal"
            val inner = ctx.nested("${bare}_$literal", true, target.qualifiedName, ctx.where)
            val merged =
                merge(
                    target.fields.map {
                        contribute(inner.copy(where = "field '${target.name}.${it.name}'"), it)
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
                    identifier("ck_${ctx.table}_$rawName", field.nameSpan),
                    "(${Naming.quote(kindName)} <> ${Naming.literal(literal)}) OR ($present)",
                )
            )
        }

        /**
         * A `list<scalar|enum>` field: a Postgres array. The element's own checks make no sense
         * over an array and are discarded; a bound on the list itself, a bound on the element, or a
         * nullable element are all information Postgres will not enforce, reported once with the
         * full type as a note.
         */
        private fun array(
            ctx: FieldContext,
            field: Field,
            type: ListOf,
            scalar: Scalar?,
            enum: EnumType?,
        ): Contribution {
            val rawName = ctx.prefix + Naming.columnOf(field)
            val name = columnName(ctx, field)
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
                    SqlTypes.scalar(bare, name, overridden = false).type
                } else SqlTypes.enum(enum!!.values.map { it.name }, name).type
            val elementHasBounds = scalar?.refinements?.hasBounds ?: false
            val lossy = type.refinements.hasBounds || elementHasBounds || type.nullableElement
            if (lossy) {
                error(
                    SqlCodes.LOSSY,
                    "${ctx.where}: refinements on ${TypeText.of(type, field.nullable)} are not enforced by Postgres",
                    field.span,
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
                )
            return Contribution(
                columns = listOf(column),
                uniques = uniqueOf(ctx, field, rawName, listOf(name)),
                indexes = indexOf(ctx, field, rawName, listOf(name)),
                required = if (field.nullable) emptyList() else listOf(name),
            )
        }

        /**
         * The `json` strategy, and every shape's default that already means it (a bare `map`):
         * Postgres has no typed record, list, map, or union, so the field lowers whole to `jsonb`
         * with a lossy note; [shape] names what was lowered away in the message.
         */
        private fun json(ctx: FieldContext, field: Field, shape: String): Contribution {
            val rawName = ctx.prefix + Naming.columnOf(field)
            val name = columnName(ctx, field)
            error(
                SqlCodes.LOSSY,
                "${ctx.where}: $shape contents are not typed by Postgres; lowered to jsonb",
                field.span,
            )
            val column =
                Column(
                    name = name,
                    type = ColumnType.JSONB,
                    nullable = field.nullable || ctx.forceNullable,
                    default = field.default?.let { Naming.literal(it) },
                    doc = field.doc,
                    notes = listOf(TypeText.of(field.type, field.nullable)),
                )
            return Contribution(
                columns = listOf(column),
                uniques = uniqueOf(ctx, field, rawName, listOf(name)),
                indexes = indexOf(ctx, field, rawName, listOf(name)),
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
         * The `table` strategy for a list or a map, and a list of records' default: a child table
         * named `<table>_<field>`. It is keyed by the table it is declared on (its own key columns,
         * each renamed `<table>_<key column>`) plus either a `position` (a list) or a `key` typed
         * like [mapKey] (a map), followed by the [element]'s own columns:
         * - a scalar or enum is one `value` column carrying the element's own checks rather than an
         *   array's stripped bounds;
         * - a keyless record in a list contributes its own fields unprefixed, since the row already
         *   is the record; a nullable element has no row to stand for its null, which is reported;
         * - a keyless record as a map value embeds under `value_`, like any nullable or required
         *   embed;
         * - a keyed record is a reference through a synthetic `value` field: `value_<key column>`
         *   columns, nullable when the element is, and a foreign key `fk_<child>_value`, so a list
         *   of a record's own type never repeats the parent-key column or its foreign key name.
         *
         * Every column the element adds is checked against the parent-key and position or key
         * columns ahead of it. A list or map bound ([refinements]) is reported since there is no
         * column left to carry a note on. A child's own list or map fields make grandchildren the
         * same way, through a fresh context whose table and key are the child's, so the grandchild
         * points back at the child rather than the root; [ctx]'s embedding chain still catches a
         * keyless element that would embed itself.
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
            val entry = record?.let { catalog[it.qualifiedName] }
            val rows = record != null && entry == null && mapKey == null
            if (rows && recursionError(ctx, field, record!!)) return Contribution.NONE
            if (refinements.hasBounds) {
                error(
                    SqlCodes.LOSSY,
                    "${ctx.where}: refinements on ${TypeText.of(field.type, field.nullable)} are not enforced by Postgres",
                    field.span,
                )
            }
            if (rows && elementNullable) {
                error(
                    SqlCodes.LOSSY,
                    "${ctx.where}: nullable elements of ${TypeText.of(field.type, field.nullable)} are not represented by a child table",
                    field.span,
                )
            }
            val childName =
                identifier("${ctx.table}_${ctx.prefix}${Naming.columnOf(field)}", field.nameSpan)
            val parentColumns =
                ctx.parentKeys.map { (key, keyType) ->
                    Column(
                        identifier("${ctx.table}_$key", field.nameSpan),
                        keyType,
                        nullable = false,
                    )
                }
            val parentFk =
                PendingForeignKey(
                    ForeignKey(
                        name = identifier("fk_${childName}_${ctx.parentTable}", field.nameSpan),
                        schema = schemaName,
                        table = childName,
                        columns = parentColumns.map { it.name },
                        targetSchema = schemaName,
                        targetTable = ctx.parentTable,
                        targetColumns = ctx.parentKeys.map { it.first },
                        cascade = true,
                    ),
                    namespace.name,
                    namespace.name,
                )
            val discriminator =
                mapKey?.let { keyColumn(it) }
                    ?: Column("position", ColumnType.INTEGER, nullable = false)
            val childKeys = (parentColumns + discriminator).map { it.name to it.type }
            val childCtx =
                FieldContext(
                    table = childName,
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
                        Triple(where, it.nameSpan, contribute(childCtx.copy(where = where), it))
                    }
                } else {
                    val valueField =
                        Field(
                            0,
                            "value",
                            when (element) {
                                is Element.Scalar -> element.scalar
                                is Element.Enum -> Ref(element.enum.qualifiedName)
                                is Element.Record -> Ref(element.record.qualifiedName)
                            },
                            elementNullable,
                            null,
                            null,
                            null,
                            field.span,
                            field.nameSpan,
                        )
                    val value =
                        when (element) {
                            is Element.Scalar -> column(childCtx, valueField, element.scalar, null)
                            is Element.Enum -> column(childCtx, valueField, null, element.enum)
                            is Element.Record ->
                                if (entry != null) reference(childCtx, valueField, entry)
                                else embed(childCtx, valueField, element.record, "value")
                        }
                    listOf(Triple(ctx.where, field.nameSpan, value))
                }
            val position = if (mapKey == null) "position" else "map key"
            columnCollisions(
                listOf(
                    ColumnSource(
                        "",
                        "the child table's parent key column",
                        field.nameSpan,
                        parentColumns.map { it.name },
                    ),
                    ColumnSource(
                        "",
                        "the child table's $position column",
                        field.nameSpan,
                        listOf(discriminator.name),
                    ),
                ) +
                    parts.map { (where, span, part) ->
                        ColumnSource(where, where, span, part.columns.map { it.name })
                    }
            )
            val merged = merge(parts.map { it.third })
            val childTable =
                Table(
                    name = childName,
                    columns = parentColumns + discriminator + merged.columns,
                    primaryKey = childKeys.map { it.first },
                    primaryKeyName = identifier("pk_$childName", field.nameSpan),
                    checks = merged.checks,
                    uniques = merged.uniques,
                    indexes = merged.indexes,
                    doc = record?.doc,
                )
            return Contribution(
                children =
                    listOf(ChildTable(childTable, listOf(parentFk) + merged.foreignKeys)) +
                        merged.children
            )
        }

        /**
         * A map's `key` column, typed like [type] — always a bare scalar, so it carries no checks.
         */
        private fun keyColumn(type: Scalar): Column =
            Column("key", SqlTypes.scalar(type, "key", overridden = false).type, nullable = false)

        /** Every list of a set of contributions, concatenated in order. */
        private fun merge(parts: List<Contribution>): Contribution =
            Contribution(
                columns = parts.flatMap { it.columns },
                checks = parts.flatMap { it.checks },
                uniques = parts.flatMap { it.uniques },
                indexes = parts.flatMap { it.indexes },
                foreignKeys = parts.flatMap { it.foreignKeys },
                children = parts.flatMap { it.children },
                required = parts.flatMap { it.required },
            )

        private fun uniqueOf(
            ctx: FieldContext,
            field: Field,
            rawName: String,
            columns: List<String>,
        ) =
            if ("unique" in field.annotations["sql"])
                listOf(Unique(identifier("uq_${ctx.table}_$rawName", field.nameSpan), columns))
            else emptyList()

        private fun indexOf(
            ctx: FieldContext,
            field: Field,
            rawName: String,
            columns: List<String>,
        ) =
            if ("index" in field.annotations["sql"])
                listOf(Index(identifier("ix_${ctx.table}_$rawName", field.nameSpan), columns))
            else emptyList()

        private fun identifier(name: String, span: Span): String =
            SqlLowering.identifier(name, span, diagnostics)

        private fun error(code: DiagnosticCode, message: String, span: Span) {
            diagnostics += Diagnostic(code, message, span)
        }
    }

    /** A name in the schema's relation namespace and the declaration that put it there. */
    private class Relation(val name: String, val kind: String, val span: Span)

    /** Truncates to Postgres's limit, reporting once per identifier. */
    private fun identifier(name: String, span: Span, diagnostics: MutableList<Diagnostic>): String {
        val result = Naming.identifier(name)
        if (result != name) {
            diagnostics +=
                Diagnostic(
                    SqlCodes.IDENTIFIER_TRUNCATED,
                    "identifier '$name' exceeds 63 bytes; truncated to '$result'",
                    span,
                )
        }
        return result
    }

    private fun schemaCollisions(
        namespaces: List<Namespace>,
        names: Map<String, String>,
        diagnostics: MutableList<Diagnostic>,
    ) {
        namespaces
            .groupBy { names.getValue(it.name) }
            .values
            .filter { it.size > 1 }
            .forEach { colliding ->
                diagnostics +=
                    Diagnostic(
                        SqlCodes.SCHEMA_COLLISION,
                        "namespaces ${englishList(colliding.map { it.name })} ${if (colliding.size > 2) "all" else "both"} lower to schema '${names.getValue(colliding.first().name)}'",
                        colliding.first().span,
                    )
            }
    }

    internal fun englishList(names: List<String>): String =
        if (names.size <= 1) names.joinToString("")
        else names.dropLast(1).joinToString(", ") + " and " + names.last()
}
