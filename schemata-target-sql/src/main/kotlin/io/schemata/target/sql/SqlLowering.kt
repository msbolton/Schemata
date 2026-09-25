package io.schemata.target.sql

import io.schemata.core.ir.AnnotationValue
import io.schemata.core.ir.EnumType
import io.schemata.core.ir.Field
import io.schemata.core.ir.ListOf
import io.schemata.core.ir.MapOf
import io.schemata.core.ir.Namespace
import io.schemata.core.ir.RecordType
import io.schemata.core.ir.Ref
import io.schemata.core.ir.Scalar
import io.schemata.core.ir.Schema
import io.schemata.core.ir.UnionType
import io.schemata.lang.Diagnostic
import io.schemata.lang.DiagnosticCode
import io.schemata.lang.Span
import io.schemata.target.Lowered
import io.schemata.target.TypeText

/**
 * Lowers records to tables. A record has a table exactly when it has a key; a keyless record is a
 * value type that only appears where a field uses it. Lowering runs in two passes: a [Catalog] of
 * every keyed record's table and key columns, then each field's [Contribution] to its table.
 * References to keyed records become key columns and a foreign key. Scalars carry every builtin,
 * refinements as CHECK constraints, defaults, enums as constrained text, and `@sql` overrides.
 * Embedded records, unions, lists, maps, nested declarations, and mapping strategies are reported
 * at the boundary until their lowering lands (SCH-28). `reserved` ordinals and names have no
 * relational meaning and are accepted without a diagnostic.
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
     * names already exists when the files are applied in path order. Within a file, keys keep
     * namespace order, then table and field order.
     */
    private fun placeForeignKeys(
        schema: Schema,
        lowered: List<Pair<RelationalSchema, List<PendingForeignKey>>>,
    ): List<RelationalSchema> {
        val pending = lowered.flatMap { it.second }
        val paths = schema.namespaces.associate { it.name to pathOf(it) }
        return lowered.map { (relational, _) ->
            val mine =
                pending.filter { fk ->
                    val source = paths.getValue(fk.sourceNamespace)
                    val target = paths.getValue(fk.targetNamespace)
                    maxOf(source, target) == relational.path
                }
            relational.copy(foreignKeys = mine.map { it.fk })
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
        private val claimedTables = mutableSetOf<String>()

        fun lower(): Pair<RelationalSchema, List<PendingForeignKey>> {
            tableCollisions()
            val tables = mutableListOf<Table>()
            val foreignKeys = mutableListOf<PendingForeignKey>()
            namespace.declarations.forEach { decl ->
                when (decl) {
                    is RecordType -> {
                        if (catalog[decl.qualifiedName] != null) {
                            val part = record(decl)
                            tables += part.table
                            tables += part.children.map { it.table }
                            foreignKeys += part.foreignKeys
                            foreignKeys += part.children.flatMap { it.foreignKeys }
                        } else if (decl.qualifiedName !in catalog.used) {
                            error(
                                SqlCodes.MISSING_KEY,
                                "record '${decl.name}' has no primary key and is not used by any field; mark key fields with @sql(key) or the record with @sql(key = (...))",
                                decl.nameSpan,
                            )
                        }
                        decl.nested.forEach { unsupported("nested declarations", it.nameSpan) }
                    }
                    is EnumType -> Unit // appears only where it is used
                    is UnionType -> Unit
                }
            }
            relationCollisions()
            return RelationalSchema(pathOf(namespace), schemaName, tables) to foreignKeys
        }

        /**
         * Table collisions are reported over final (overridden) names, before any record lowers.
         * Only keyed records have tables.
         */
        private fun tableCollisions() {
            namespace.declarations
                .filterIsInstance<RecordType>()
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
                    where = "",
                )
            val parts =
                record.fields.map { field ->
                    field to
                        contribute(ctx.copy(where = "field '${record.name}.${field.name}'"), field)
                }
            columnCollisions(record, parts)
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
            claim(tableName, primaryKeyName, uniques, indexes, record.nameSpan)
            parts.forEach { (field, part) ->
                part.children.forEach { child ->
                    val t = child.table
                    claim(
                        t.name,
                        t.primaryKeyName,
                        t.uniques.map { field to it },
                        t.indexes.map { field to it },
                        field.nameSpan,
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

        /** Two fields whose columns land on the same final name. */
        private fun columnCollisions(record: RecordType, parts: List<Pair<Field, Contribution>>) {
            val seen = mutableMapOf<String, Field>()
            parts.forEach { (field, part) ->
                part.columns.forEach { column ->
                    val previous = seen.putIfAbsent(column.name, field)
                    if (previous != null) {
                        error(
                            SqlCodes.NAME_COLLISION,
                            "field '${record.name}.${field.name}' lowers to column '${column.name}', already used by field '${previous.name}' (${previous.nameSpan.file}:${previous.nameSpan.startLine})",
                            field.nameSpan,
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
         * Records a table's names for [relationCollisions]. A table already claimed by an earlier
         * record is a table collision, reported on its own.
         */
        private fun claim(
            tableName: String,
            primaryKeyName: String?,
            uniques: List<Pair<Field, Unique>>,
            indexes: List<Pair<Field, Index>>,
            span: Span,
        ) {
            if (!claimedTables.add(tableName)) return
            relations += Relation(tableName, "table '$tableName'", span)
            primaryKeyName?.let { relations += Relation(it, "primary key of '$tableName'", span) }
            uniques.forEach { (field, u) ->
                relations += Relation(u.name, "unique '${u.name}'", field.nameSpan)
            }
            indexes.forEach { (field, ix) ->
                relations += Relation(ix.name, "index '${ix.name}'", field.nameSpan)
            }
        }

        /** Everything [field] adds to the table [ctx] names. */
        private fun contribute(ctx: FieldContext, field: Field): Contribution {
            if ("strategy" in field.annotations["sql"]) {
                unsupported("mapping strategies", field.span, ctx.where)
                return Contribution.NONE
            }
            return when (val type = field.type) {
                is Scalar -> column(ctx, field, type, null)
                is Ref ->
                    when (val target = schema.lookup(type.target)) {
                        is EnumType -> column(ctx, field, null, target)
                        is RecordType -> {
                            val entry = catalog[target.qualifiedName]
                            if (entry != null) {
                                reference(ctx, field, entry)
                            } else {
                                unsupported("record references", field.span, ctx.where)
                                Contribution.NONE
                            }
                        }
                        is UnionType -> {
                            unsupported("unions", field.span, ctx.where)
                            Contribution.NONE
                        }
                    }
                is ListOf -> {
                    unsupported("lists", field.span, ctx.where)
                    Contribution.NONE
                }
                is MapOf -> {
                    unsupported("maps", field.span, ctx.where)
                    Contribution.NONE
                }
            }
        }

        /**
         * A field's final column name. The owning record's own key fields take the name the
         * [Catalog] already derived, so a truncation is not reported twice.
         */
        private fun columnName(ctx: FieldContext, field: Field): String {
            val owner =
                if (ctx.prefix.isEmpty() && ctx.embedding.size == 1) catalog[ctx.embedding.single()]
                else null
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
            )
        }

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

        private fun unsupported(what: String, span: Span, where: String? = null) {
            val prefix = where?.let { "$it: " } ?: ""
            error(
                SqlCodes.UNSUPPORTED_SHAPE,
                "${prefix}target 'sql' cannot lower $what yet (SCH-28)",
                span,
            )
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
