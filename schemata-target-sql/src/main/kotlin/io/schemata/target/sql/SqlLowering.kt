package io.schemata.target.sql

import io.schemata.core.ir.AnnotationValue
import io.schemata.core.ir.Annotations
import io.schemata.core.ir.Builtin
import io.schemata.core.ir.EnumType
import io.schemata.core.ir.Field
import io.schemata.core.ir.ListOf
import io.schemata.core.ir.MapOf
import io.schemata.core.ir.Namespace
import io.schemata.core.ir.OnDelete
import io.schemata.core.ir.RecordType
import io.schemata.core.ir.Ref
import io.schemata.core.ir.Refinements
import io.schemata.core.ir.Relation as RefRelation
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
import io.schemata.target.NameClaims
import io.schemata.target.TypeText
import io.schemata.target.collidingNamespaces
import io.schemata.target.string
import io.schemata.target.unionMemberStem

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
                val override =
                    validOverride(
                        it.annotations,
                        "schema",
                        "namespace '${it.name}'",
                        it.span,
                        diagnostics,
                    )
                it.name to identifier(Naming.schemaOf(it, override), it.span, diagnostics)
            }
        schemaCollisions(schema.namespaces, schemaNames, diagnostics)
        val catalog =
            Catalog(
                schema,
                schemaNames,
                identifier = { name, span -> identifier(name, span, diagnostics) },
                override = { annotations, key, where, span ->
                    validOverride(annotations, key, where, span, diagnostics)
                },
            )
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
                        "record '${r.name}' has no primary key and is not used by any field",
                        r.nameSpan,
                        help =
                            "mark its key fields with `{ id }`, or the model with `@@id(a, b)`; a keyless model only lowers when a field embeds it",
                    )
                }
            }
            relationCollisions()
            return RelationalSchema(pathOf(namespace), schemaName, tables) to foreignKeys
        }

        /**
         * Table collisions are reported over final (overridden) names, before any record lowers.
         * Only keyed records have tables, nested ones included; the second record in source order
         * is blamed.
         */
        private fun tableCollisions() {
            records
                .filter { catalog[it.qualifiedName] != null }
                .groupBy { catalog[it.qualifiedName]!!.tableNameRaw }
                .values
                .filter { it.size > 1 }
                .forEach { colliding ->
                    diagnostics +=
                        Diagnostic(
                            SqlCodes.TABLE_COLLISION,
                            "records ${englishList(colliding.map { it.name })} ${if (colliding.size > 2) "all" else "both"} lower to table '${catalog[colliding.first().qualifiedName]!!.tableNameRaw}'",
                            colliding[1].span,
                            help = "set `@sql(table = \"…\")` on one of them",
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
                    help = "rename one of them, or set `@sql(table = \"…\")` on one",
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
                    field to
                        contribute(ctx.copy(where = "field '${record.name}.${field.name}'"), field)
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
                parts.flatMap { (field, part) ->
                    constraintNames(part).map { it to field.nameSpan }
                },
            )
            val columns = parts.flatMap { (_, part) -> part.columns }
            val primaryKey = entry.keyColumns.filter { key -> columns.any { it.name == key } }
            val primaryKeyName =
                if (primaryKey.isEmpty()) null else identifier("pk_$tableName", record.nameSpan)
            val uniques =
                constraints(record, "unique", owned(parts) { it.uniques }, primaryKey) {
                        it.columns
                    }
                    .map { (field, u) -> field.nameSpan to u } +
                    compositeUniques(record, tableName, parts, primaryKey)
            val indexes =
                constraints(record, "index", owned(parts) { it.indexes }, primaryKey) { it.columns }
                    .map { (field, ix) -> field.nameSpan to ix } +
                    compositeIndexes(record, tableName, parts, primaryKey)
            claim(
                TableClaim("table '$tableName'", record.nameSpan, entry.tableNameRaw),
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
                        identifier("uq_${table}_${names.joinToString("_")}", record.nameSpan),
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
                        identifier("ix_${table}_${names.joinToString("_")}", record.nameSpan),
                        columns,
                    )
            }

        /**
         * Each of [lists] (a model's `@@unique` or `@@index` field-name lists) with the columns its
         * fields lowered to on the record's own table: a scalar's column, a reference's key
         * columns, an embed's columns. A field with no column there (a child table, a field that
         * failed to lower) leaves the constraint nothing to stand on, so it is reported and
         * dropped; one over exactly the primary key is redundant and dropped with a warning, as a
         * field's own `{ unique }` is.
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
                            error(
                                SqlCodes.STRATEGY_NOT_ALLOWED,
                                "record '${record.name}': $display names '$name', which has no column on the record's table",
                                record.nameSpan,
                                help = "name fields stored in the table's own columns",
                            )
                            return@mapNotNull null
                        }
                        own.map { it.name }
                    }
                val flat = columns.flatten()
                if (primaryKey.isNotEmpty() && flat == primaryKey) {
                    error(
                        SqlCodes.REDUNDANT_CONSTRAINT,
                        "record '${record.name}': $display duplicates the primary key; dropped",
                        record.nameSpan,
                        help = "remove it; the primary key already enforces it",
                    )
                    return@mapNotNull null
                }
                names to flat
            }

        /**
         * The relation a list's element or a map's value reference carries, so a child table's
         * `value` reference acts on delete as the field asked.
         */
        private fun elementRelation(type: Type): RefRelation =
            when (type) {
                is ListOf -> (type.element as? Ref)?.relation
                is MapOf -> (type.value as? Ref)?.relation
                else -> null
            } ?: RefRelation()

        /** Something that puts [columns] on a table, described by [subject]; [span] locates it. */
        private class ColumnSource(val subject: String, val span: Span, val columns: List<String>)

        /** The CHECK and foreign key names a contribution puts on its own table. */
        private fun constraintNames(part: Contribution): List<String> =
            part.checks.map { it.name } + part.foreignKeys.map { it.fk.name }

        /**
         * CHECK and foreign key names share one namespace per table, so two derivations that land
         * on the same name (a union's kind check and a member named `Kind`'s presence check, say)
         * are reported at the later one.
         */
        private fun constraintCollisions(table: String, names: List<Pair<String, Span>>) {
            val seen = mutableSetOf<String>()
            names.forEach { (name, span) ->
                if (!seen.add(name)) {
                    error(
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
        private fun columnCollisions(sources: List<ColumnSource>) {
            val claims =
                NameClaims(
                    SqlCodes.NAME_COLLISION,
                    "rename one of them, or set `@sql(column = \"…\")` on one",
                    diagnostics,
                )
            sources.forEach { source ->
                source.columns.forEach { column ->
                    claims.claim(column, source.subject, source.span, kind = "column")
                }
            }
        }

        /**
         * Reports a key the table cannot carry: a nullable key field, or one that is not a single
         * scalar column. Only keyed records reach here; the key itself comes from the [Catalog].
         */
        private fun keys(record: RecordType) {
            val keyFields = catalog[record.qualifiedName]!!.keyFields
            keyFields
                .filter { it.nullable }
                .forEach {
                    error(
                        SqlCodes.KEY_COLUMN,
                        "record '${record.name}': key field '${it.name}' is nullable",
                        it.nameSpan,
                        help = "drop the `?`; a primary key column cannot be null",
                    )
                }
            keyFields
                .filter { keyType(it) == null }
                .forEach {
                    error(
                        SqlCodes.KEY_COLUMN,
                        "record '${record.name}': key field '${it.name}' must be a scalar column",
                        it.nameSpan,
                        help =
                            "key a scalar or enum field; reference the record from a keyed one instead",
                    )
                }
        }

        /**
         * The column type a key field has, which a reference to its record copies; null when the
         * field is not a single scalar column (a builtin or an enum). A pattern Postgres cannot
         * express is dropped here as the key's own column drops it, so every copy gets the same
         * type; the key's own column is where that is reported.
         */
        private fun keyType(field: Field): ColumnType? {
            val override = field.annotations.string("sql", "type")
            return when (val type = field.type) {
                is Scalar -> {
                    val refinements =
                        type.refinements.pattern
                            ?.takeIf { PostgresPattern.firstUnsupported(it) != null }
                            ?.let { type.refinements.copy(pattern = null) } ?: type.refinements
                    override?.let { ColumnType.RAW(it) }
                        ?: SqlTypes.scalar(
                                type.copy(refinements = refinements),
                                field.name,
                                overridden = false,
                            )
                            .type
                }
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
                        "field '${record.name}.${field.name}': { $key } duplicates the primary key; dropped",
                        field.nameSpan,
                        help = "remove the option; the primary key already enforces it",
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
            uniques: List<Pair<Span, Unique>>,
            indexes: List<Pair<Span, Index>>,
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
                        help = "rename one of them, or set `@sql(table = \"…\")` on one",
                    )
                }
                return
            }
            val span = claimant.span
            relations += Relation(tableName, claimant.kind, span)
            primaryKeyName?.let { relations += Relation(it, "primary key of '$tableName'", span) }
            uniques.forEach { (at, u) -> relations += Relation(u.name, "unique '${u.name}'", at) }
            indexes.forEach { (at, ix) -> relations += Relation(ix.name, "index '${ix.name}'", at) }
        }

        /**
         * Everything [field] adds to the table [ctx] names, after [strategyOf] its override. A
         * back-reference adds nothing: the forward reference on the other model holds the key.
         */
        private fun contribute(ctx: FieldContext, field: Field): Contribution {
            if (field.virtual) return Contribution.NONE
            val strategy = strategyOf(field)
            return when (val type = field.type) {
                is Scalar -> scalarField(ctx, field, strategy, type, null)
                is Ref ->
                    when (val target = schema.lookup(type.target)) {
                        is EnumType -> scalarField(ctx, field, strategy, null, target)
                        is RecordType -> recordField(ctx, field, strategy, type, target)
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
         * keyless one, which `{ embed }` on the field also asks for; `json` lowers the whole
         * reference to jsonb; `table` keeps the default reference for a keyed target and is not
         * allowed for a keyless one, which has no table to reference. A keyed target's rows live in
         * its own table, so `{ embed }` on a reference to one is not allowed: copying its columns
         * would leave the copy and the table to drift apart.
         */
        private fun recordField(
            ctx: FieldContext,
            field: Field,
            strategy: String?,
            ref: Ref,
            target: RecordType,
        ): Contribution {
            val entry = catalog[target.qualifiedName]
            if (ref.relation.embed && entry != null)
                return forbiddenStrategy(
                    ctx,
                    field,
                    "embed",
                    "a keyed record",
                    "a reference or json",
                )
            return when (strategy) {
                "json" ->
                    json(
                        ctx,
                        field,
                        "record",
                        "remove `strategy = json` to get the default mapping for this field",
                    )
                "table" ->
                    if (entry != null) reference(ctx, field, entry)
                    else forbiddenStrategy(ctx, field, "table", "a keyless record", "embed or json")
                else ->
                    if (entry != null) reference(ctx, field, entry)
                    else embed(ctx, field, target, columnOf(field, ctx.where))
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
                "json" -> json(ctx, field, "union", jsonHelp(type))
                "table" -> forbiddenStrategy(ctx, field, "table", "a union", "embed or json")
                else -> union(ctx, field, type)
            }

        /**
         * A list: the default is an array for a scalar or enum element and a child table for a
         * record one; `table` asks for a child table either way, with a single `value` column
         * carrying a scalar or enum element's own checks instead of an array's stripped bounds;
         * `json` lowers the whole list to jsonb, the only strategy that reaches a union, nested
         * list, or nested map element.
         */
        private fun listField(
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
                            if (
                                element.relation.embed &&
                                    catalog[elementTarget.qualifiedName] != null
                            )
                                forbiddenStrategy(
                                    ctx,
                                    field,
                                    "embed",
                                    "a list of keyed records",
                                    "a child table of references or json",
                                )
                            else
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
         * lowered the way a list's scalar, enum, or record element is, under a `value` column.
         */
        private fun mapField(
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

        /**
         * Whether a list's element or a map's value could lower to a child table: a scalar, enum,
         * or record can, a union or a nested list or map cannot.
         */
        private fun tableable(element: Type): Boolean =
            when (element) {
                is Scalar -> true
                is Ref -> schema.lookup(element.target) !is UnionType
                is ListOf,
                is MapOf -> false
            }

        /**
         * The help for a list or map's `json` lowering: `table` when the element or value could
         * lower to a child table instead, otherwise jsonb is the only mapping this shape has.
         */
        private fun jsonHelp(element: Type): String =
            if (tableable(element))
                "use `@sql(strategy = table)` to lower the entries to a child table"
            else "keep jsonb; Postgres has no typed mapping for this shape"

        /** Whether a union has a member that is itself a union, which has no relational mapping. */
        private fun hasUnionMember(type: UnionType): Boolean =
            type.members.any {
                it.type is Ref && schema.lookup((it.type as Ref).target) is UnionType
            }

        /**
         * The help for a union's `json` lowering: only mandatory when a member is itself a union.
         */
        private fun jsonHelp(type: UnionType): String =
            if (hasUnionMember(type)) "keep jsonb; Postgres has no typed mapping for this shape"
            else "remove `strategy = json` to get the default mapping for this field"

        /** The error a strategy a shape forbids reports; [alternatives] is null for a scalar. */
        private fun forbiddenStrategy(
            ctx: FieldContext,
            field: Field,
            strategy: String,
            shape: String,
            alternatives: String?,
        ): Contribution {
            error(
                SqlCodes.STRATEGY_NOT_ALLOWED,
                "${ctx.where}: strategy '$strategy' is not allowed for $shape",
                field.span,
                help =
                    if (alternatives != null) "use $alternatives"
                    else "remove the strategy annotation",
            )
            return Contribution.NONE
        }

        /**
         * A shape with no relational form at all — a list of unions, nested lists, or nested maps,
         * or a union with a union member — regardless of whether the default or an explicit
         * strategy asked for one; naming a strategy the field never wrote would be misleading, so
         * this names the shape instead.
         */
        private fun noRelationalMapping(
            ctx: FieldContext,
            field: Field,
            shape: String,
        ): Contribution {
            error(
                SqlCodes.STRATEGY_NOT_ALLOWED,
                "${ctx.where}: $shape has no relational mapping",
                field.span,
                help = "add `@sql(strategy = json)` to store the field as jsonb",
            )
            return Contribution.NONE
        }

        /** The validated `@sql(column)` override for [field], or its own name; reported once. */
        private fun columnOf(field: Field, where: String): String =
            Naming.columnOf(
                field,
                validOverride(field.annotations, "column", where, field.nameSpan, diagnostics),
            )

        /**
         * A field's final column name and its pre-truncation form, the latter used to name the
         * checks it adds. The owning record's own key fields reuse the [Catalog]'s
         * already-validated resolution of both, so an empty `@sql(column)` override is reported
         * once even though a key field feeds both its own column and every check built from it, and
         * so the owning table and every table that copies the key agree on the column's name (its
         * type agrees because [keyType] screens the pattern as [column] does); a child table's
         * synthetic `value` field never matches, since its table is never the owner's own.
         */
        private fun columnNames(ctx: FieldContext, field: Field): Pair<String, String> {
            val owner =
                if (ctx.prefix.isEmpty() && ctx.embedding.size == 1) {
                    catalog[ctx.embedding.single()]?.takeIf { it.tableName == ctx.table }
                } else null
            val keyIndex = owner?.keyFields?.indexOf(field) ?: -1
            if (keyIndex >= 0) return owner!!.keyColumns[keyIndex] to owner.keyColumnsRaw[keyIndex]
            val raw = ctx.prefix + columnOf(field, ctx.where)
            return identifier(raw, field.nameSpan) to raw
        }

        /**
         * [refinements] with a pattern Postgres's ARE dialect cannot express replaced by null,
         * reported once at [where]; refinements with no pattern, or one ARE accepts, pass through
         * unchanged.
         */
        private fun screenPattern(
            where: String,
            span: Span,
            refinements: Refinements,
        ): Refinements {
            val pattern = refinements.pattern ?: return refinements
            val bad = PostgresPattern.firstUnsupported(pattern) ?: return refinements
            error(
                SqlCodes.LOSSY,
                "$where: pattern uses $bad, which Postgres regexes cannot express; dropped",
                span,
                help = "rewrite the pattern without $bad, or enforce it in application code",
            )
            return refinements.copy(pattern = null)
        }

        /** One column for a scalar or enum field, with its checks, unique, and index. */
        private fun column(
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
                        error(
                            SqlCodes.TYPE_LIMIT,
                            "${ctx.where}: decimal precision $precision exceeds Postgres's limit of ${SqlTypes.NUMERIC_PRECISION_LIMIT}",
                            field.span,
                            help = "use a precision of at most ${SqlTypes.NUMERIC_PRECISION_LIMIT}",
                        )
                        return Contribution.NONE
                    }
                    SqlTypes.scalar(
                        scalar.copy(refinements = refinements),
                        name,
                        overridden = override != null,
                    )
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
         * `<field>_<key column>` and typed like it, plus a foreign key to the target's table that
         * acts on delete as the reference's `@relation(onDelete: …)` says. A nullable reference
         * over a composite key adds a CHECK that its columns are all null or all set.
         */
        private fun reference(ctx: FieldContext, field: Field, entry: Catalog.Entry): Contribution {
            val rawName = ctx.prefix + columnOf(field, ctx.where)
            val columns =
                entry.keyFields.zip(entry.keyColumns).mapNotNull { (key, keyColumn) ->
                    val type = keyType(key) ?: return@mapNotNull null
                    Column(
                        name = identifier("${rawName}_$keyColumn", field.nameSpan),
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
                    name = identifier("fk_${ctx.table}_$rawName", field.nameSpan),
                    schema = schemaName,
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
                            identifier("ck_${ctx.table}_${rawName}_present", field.nameSpan),
                            "(($allNull) OR ($allSet))",
                        )
                    )
                } else emptyList()
            return Contribution(
                columns = columns,
                checks = present,
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
         * nullable embed forces every produced column nullable and, when two or more of them would
         * otherwise be required, adds one CHECK that those are all present or all absent together.
         * `{ unique }` or `{ index }` on the field itself covers every column it produced.
         * Embedding the same record again inside itself is reported instead of recursing forever.
         */
        private fun embed(
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
                    identifier("ck_${ctx.table}_${outerRaw}_present", field.nameSpan),
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
                "${ctx.where}: embedding '${target.name}' here would recurse ($cycle)",
                field.span,
                help =
                    "use `@sql(strategy = json)` on this field, or give '${target.name}' a key so it becomes a table",
            )
            return true
        }

        /**
         * A reference to a union: a `<field>_kind` text column naming which member is present (and
         * carrying the field's doc, which no member column repeats), a CHECK constraining it to the
         * member names, and each member's own contribution nested under `<field>_<member>`, every
         * column forced nullable since only the member the kind names is ever populated. A member's
         * own required columns (the ones that would be NOT NULL on their own account) back a second
         * CHECK that they are all present exactly when the kind names that member; a member with
         * none (a keyless record with no fields) needs no such check. The union's own
         * [Contribution.required] names only the kind column: a member's columns never make the
         * enclosing table's presence checks, since a member is optional by construction and its own
         * CHECK already enforces it. `{ unique }` or `{ index }` on the field covers the kind
         * column and every member column. A member that is itself a union has no kind column of its
         * own to nest a second one under, so it has no embed strategy and must be lowered with
         * `strategy = json` instead (SCH-28).
         */
        private fun union(ctx: FieldContext, field: Field, type: UnionType): Contribution {
            if (hasUnionMember(type)) {
                return noRelationalMapping(ctx, field, "a union whose member is a union")
            }
            val bare = columnOf(field, ctx.where)
            val outerRaw = ctx.prefix + bare
            val kindName = identifier("${outerRaw}_kind", field.nameSpan)
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
                    identifier("ck_${ctx.table}_${outerRaw}_kind", field.nameSpan),
                    "${Naming.quote(kindName)} IN (${literals.joinToString(", ") { Naming.literal(it) }})",
                )
            val merged =
                merge(
                    type.members.zip(literals).map { (member, literal) ->
                        unionMember(ctx, field, bare, literal, kindName, member)
                    }
                )
            val columns = listOf(kindColumn) + merged.columns
            val names = columns.map { it.name }
            return Contribution(
                columns = columns,
                checks = listOf(kindCheck) + merged.checks,
                uniques = merged.uniques + uniqueOf(ctx, field, outerRaw, names),
                indexes = merged.indexes + indexOf(ctx, field, outerRaw, names),
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
                else -> unionMemberStem(type, schema) { null }
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
                    when (val target = schema.lookup(type.target)) {
                        is EnumType -> unionEnum(memberCtx, field, bare, literal, kindName, target)
                        is RecordType -> {
                            val entry = catalog[target.qualifiedName]
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
            val name = identifier(rawName, field.nameSpan)
            val refinements = screenPattern(ctx.where, field.nameSpan, scalar.refinements)
            val precision = refinements.precision
            if (precision != null && precision > SqlTypes.NUMERIC_PRECISION_LIMIT) {
                error(
                    SqlCodes.TYPE_LIMIT,
                    "${ctx.where}: decimal precision $precision exceeds Postgres's limit of ${SqlTypes.NUMERIC_PRECISION_LIMIT}",
                    field.span,
                    help = "use a precision of at most ${SqlTypes.NUMERIC_PRECISION_LIMIT}",
                )
                return Contribution.NONE
            }
            val mapped =
                SqlTypes.scalar(scalar.copy(refinements = refinements), name, overridden = false)
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
                        origin = ColumnOrigin.FieldPath(ctx.path, "k${key.ordinal}"),
                        span = field.nameSpan,
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
                    onDelete = OnDelete.RESTRICT,
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
            // ctx.path already ends in the member step, so the nested context only extends the
            // prefix.
            val inner =
                ctx.copy(
                    prefix = "${ctx.prefix}${bare}_${literal}_",
                    forceNullable = true,
                    embedding = ctx.embedding + target.qualifiedName,
                )
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
            val (name, rawName) = columnNames(ctx, field)
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
            val elementLossy = elementHasBounds || type.nullableElement
            val lossy = type.refinements.hasBounds || elementLossy
            if (lossy) {
                error(
                    SqlCodes.LOSSY,
                    "${ctx.where}: refinements on ${TypeText.of(type, field.nullable)} are not enforced by Postgres",
                    field.span,
                    help =
                        if (elementLossy)
                            "use `@sql(strategy = table)` so the elements become rows with their own constraints"
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
                uniques = uniqueOf(ctx, field, rawName, listOf(name)),
                indexes = indexOf(ctx, field, rawName, listOf(name)),
                required = if (field.nullable) emptyList() else listOf(name),
            )
        }

        /**
         * The `json` strategy, and every shape's default that already means it (a bare `map`):
         * Postgres has no typed record, list, map, or union, so the field lowers whole to `jsonb`
         * with a lossy note; [shape] names what was lowered away in the message, and [help] fits
         * the fix to what this particular field could actually do instead.
         */
        private fun json(
            ctx: FieldContext,
            field: Field,
            shape: String,
            help: String,
        ): Contribution {
            val (name, rawName) = columnNames(ctx, field)
            error(
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
                    help =
                        "enforce the collection bound in application code; child tables carry no row-count constraints",
                )
            }
            if (rows && elementNullable) {
                error(
                    SqlCodes.LOSSY,
                    "${ctx.where}: nullable elements of ${TypeText.of(field.type, field.nullable)} are not represented by a child table",
                    field.span,
                    help =
                        "declare the elements non-nullable, or use `@sql(strategy = json)` to keep nulls",
                )
            }
            val childName =
                identifier(
                    "${ctx.table}_${ctx.prefix}${columnOf(field, ctx.where)}",
                    field.nameSpan,
                )
            val parentColumns =
                ctx.parentKeys.map { key ->
                    Column(
                        identifier("${ctx.table}_${key.column}", field.nameSpan),
                        key.type,
                        nullable = false,
                        origin = ColumnOrigin.Role("parent:${key.id}"),
                        span = field.nameSpan,
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
                        targetColumns = ctx.parentKeys.map { it.column },
                        onDelete = OnDelete.CASCADE,
                    ),
                    namespace.name,
                    namespace.name,
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
                    val value =
                        when (element) {
                            is Element.Scalar -> column(childCtx, valueField, element.scalar, null)
                            is Element.Enum -> column(childCtx, valueField, null, element.enum)
                            is Element.Record ->
                                if (entry != null) reference(childCtx, valueField, entry)
                                else embed(childCtx, valueField, element.record, "value")
                        }
                    // The synthetic value field is no declared field; its columns are a role.
                    val asRole =
                        if (element is Element.Record) value
                        else
                            value.copy(
                                columns =
                                    value.columns.map {
                                        it.copy(origin = ColumnOrigin.Role("value"))
                                    }
                            )
                    listOf(Triple(ctx.where, field.nameSpan, asRole))
                }
            val position = if (mapKey == null) "position" else "map key"
            columnCollisions(
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
            constraintCollisions(
                childName,
                listOf(parentFk.fk.name to field.nameSpan) +
                    parts.flatMap { (_, span, part) -> constraintNames(part).map { it to span } },
            )
            val merged = merge(parts.map { it.third })
            val childTable =
                Table(
                    name = childName,
                    columns = parentColumns + discriminator + merged.columns,
                    primaryKey = childKeys.map { it.column },
                    primaryKeyName = identifier("pk_$childName", field.nameSpan),
                    checks = merged.checks,
                    uniques = merged.uniques,
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
         * A map's `key` column, typed like [type] — always a bare scalar, so it carries no checks.
         * An unsupported pattern is still screened, since it changes whether the type fits a plain
         * `varchar(n)`.
         */
        private fun keyColumn(ctx: FieldContext, field: Field, type: Scalar): Column {
            val refinements = screenPattern(ctx.where, field.nameSpan, type.refinements)
            return Column(
                "key",
                SqlTypes.scalar(type.copy(refinements = refinements), "key", overridden = false)
                    .type,
                nullable = false,
                origin = ColumnOrigin.Role("key"),
                span = field.nameSpan,
            )
        }

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

        /**
         * The unique [field] asks for over [columns], if any, through `{ unique }`. A list or map
         * field never gets one; the analyzer has already reported the option there.
         */
        private fun uniqueOf(
            ctx: FieldContext,
            field: Field,
            rawName: String,
            columns: List<String>,
        ) =
            if (field.unique && constrainable(field, columns))
                listOf(Unique(identifier("uq_${ctx.table}_$rawName", field.nameSpan), columns))
            else emptyList()

        /** The index [field] asks for over [columns], if any, on the same terms as [uniqueOf]. */
        private fun indexOf(
            ctx: FieldContext,
            field: Field,
            rawName: String,
            columns: List<String>,
        ) =
            if (field.index && constrainable(field, columns))
                listOf(Index(identifier("ix_${ctx.table}_$rawName", field.nameSpan), columns))
            else emptyList()

        private fun constrainable(field: Field, columns: List<String>): Boolean =
            columns.isNotEmpty() && field.type !is ListOf && field.type !is MapOf

        private fun identifier(name: String, span: Span): String =
            SqlLowering.identifier(name, span, diagnostics)

        private fun error(code: DiagnosticCode, message: String, span: Span, help: String? = null) {
            diagnostics += Diagnostic(code, message, span, help)
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
                    help =
                        "shorten the name with `@sql(table = \"…\")` or `@sql(column = \"…\")` to choose it yourself",
                )
        }
        return result
    }

    private fun schemaCollisions(
        namespaces: List<Namespace>,
        names: Map<String, String>,
        diagnostics: MutableList<Diagnostic>,
    ) {
        collidingNamespaces(namespaces) { names.getValue(it.name) }
            .forEach { group ->
                diagnostics +=
                    Diagnostic(
                        SqlCodes.SCHEMA_COLLISION,
                        "namespaces ${englishList(group.map { it.name })} ${if (group.size > 2) "all" else "both"} lower to schema '${names.getValue(group.first().name)}'",
                        group[1].span,
                        help = "set `@sql(schema = \"…\")` on one of them",
                    )
            }
    }

    /** The `@sql(<key>)` override when it is non-empty; an empty one is reported and ignored. */
    private fun validOverride(
        annotations: Annotations,
        key: String,
        where: String,
        span: Span,
        diagnostics: MutableList<Diagnostic>,
    ): String? {
        val value = annotations.string("sql", key) ?: return null
        if (value.isNotEmpty()) return value
        diagnostics +=
            Diagnostic(
                SqlCodes.INVALID_OVERRIDE,
                "$where: @sql($key = \"\") is empty",
                span,
                help = "give the name at least one character",
            )
        return null
    }

    internal fun englishList(names: List<String>): String =
        if (names.size <= 1) names.joinToString("")
        else names.dropLast(1).joinToString(", ") + " and " + names.last()
}
