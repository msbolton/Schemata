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
import io.schemata.core.ir.TypeDecl
import io.schemata.core.ir.UnionType
import io.schemata.lang.Diagnostic
import io.schemata.lang.DiagnosticCode
import io.schemata.lang.Span
import io.schemata.target.Lowered
import io.schemata.target.TypeText

/**
 * Lowers flat records to tables: every builtin, refinements as CHECK constraints, defaults, enums
 * as constrained text, `@sql` overrides, primary keys, uniques, and indexes. References, unions,
 * lists, maps, nested declarations, and mapping strategies are reported at the boundary until the
 * structural lowering lands (SCH-28). `reserved` ordinals and names have no relational meaning and
 * are accepted without a diagnostic.
 */
object SqlLowering {
    fun lower(schema: Schema): Lowered<RelationalModel> {
        val diagnostics = mutableListOf<Diagnostic>()
        val schemaNames =
            schema.namespaces.associate {
                it.name to identifier(Naming.schemaOf(it), it.span, diagnostics)
            }
        schemaCollisions(schema.namespaces, schemaNames, diagnostics)
        val schemas =
            schema.namespaces.map {
                NamespaceLowering(schema, it, schemaNames.getValue(it.name), diagnostics).lower()
            }
        return Lowered(RelationalModel(schemas), diagnostics)
    }

    private class NamespaceLowering(
        private val schema: Schema,
        private val namespace: Namespace,
        private val schemaName: String,
        private val diagnostics: MutableList<Diagnostic>,
    ) {
        /** Every name a table puts in the schema's relation namespace, with where it came from. */
        private val relations = mutableListOf<Relation>()
        private val claimedTables = mutableSetOf<String>()

        fun lower(): RelationalSchema {
            tableCollisions()
            val tables = namespace.declarations.mapNotNull { decl(it) }
            relationCollisions()
            return RelationalSchema(
                path = namespace.name.replace('.', '/') + ".sql",
                schemaName = schemaName,
                tables = tables,
            )
        }

        /**
         * Table collisions are reported over final (overridden) names, before any record lowers.
         */
        private fun tableCollisions() {
            namespace.declarations
                .filterIsInstance<RecordType>()
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

        private fun decl(decl: TypeDecl): Table? =
            when (decl) {
                is RecordType -> record(decl)
                is EnumType -> null // appears only where it is used
                is UnionType -> null
            }

        private fun record(record: RecordType): Table {
            val tableName = identifier(Naming.tableOf(record), record.nameSpan)
            val keyFields = keys(record)
            val body = columns(record, tableName)
            record.nested.forEach { unsupported("nested declarations", it.nameSpan) }
            val primaryKey = keyFields.mapNotNull { body.columns[it]?.name }
            val primaryKeyName =
                if (primaryKey.isEmpty()) null else identifier("pk_$tableName", record.nameSpan)
            val uniques = constraints(record, "unique", body.uniques, primaryKey) { it.columns }
            val indexes = constraints(record, "index", body.indexes, primaryKey) { it.columns }
            claim(record, tableName, primaryKeyName, uniques, indexes)
            return Table(
                name = tableName,
                columns = body.columns.values.toList(),
                primaryKey = primaryKey,
                primaryKeyName = primaryKeyName,
                checks = body.checks,
                uniques = uniques.map { it.second },
                indexes = indexes.map { it.second },
                doc = record.doc,
            )
        }

        /**
         * The primary key's fields in key order: the `@sql(key)` fields in declaration order, or
         * the fields a record-level `@sql(key = (...))` names, each once.
         */
        private fun keys(record: RecordType): List<Field> {
            val fieldKeys = record.fields.filter { "key" in it.annotations["sql"] }
            val recordKeyNames =
                (record.annotations["sql"]["key"] as? AnnotationValue.Names)?.values
            if (fieldKeys.isNotEmpty() && recordKeyNames != null) {
                error(
                    SqlCodes.KEY_COLUMN,
                    "record '${record.name}' declares @sql(key) on both the record and its fields",
                    record.nameSpan,
                )
            } else if (fieldKeys.isEmpty() && recordKeyNames == null) {
                error(
                    SqlCodes.MISSING_KEY,
                    "record '${record.name}' has no primary key; mark key fields with @sql(key) or the record with @sql(key = (...))",
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
            val keyFields =
                recordKeyNames?.distinct()?.mapNotNull { name ->
                    record.fields.firstOrNull { it.name == name }
                } ?: fieldKeys
            keyFields
                .filter { it.nullable }
                .forEach {
                    error(
                        SqlCodes.KEY_COLUMN,
                        "record '${record.name}': key field '${it.name}' is nullable; a primary key column cannot be",
                        it.nameSpan,
                    )
                }
            return keyFields
        }

        /** A record's columns and the per-field constraints they carry. */
        private class Body(
            val columns: Map<Field, Column>,
            val checks: List<Check>,
            val uniques: List<Pair<Field, Unique>>,
            val indexes: List<Pair<Field, Index>>,
        )

        private fun columns(record: RecordType, tableName: String): Body {
            val checks = mutableListOf<Check>()
            val uniques = mutableListOf<Pair<Field, Unique>>()
            val indexes = mutableListOf<Pair<Field, Index>>()
            val columnsByField = linkedMapOf<Field, Column>()
            val seen = mutableMapOf<String, Field>()
            record.fields.forEach { field ->
                val column =
                    column(record, tableName, field, checks, uniques, indexes) ?: return@forEach
                val previous = seen.putIfAbsent(column.name, field)
                if (previous != null) {
                    error(
                        SqlCodes.NAME_COLLISION,
                        "field '${record.name}.${field.name}' lowers to column '${column.name}', already used by field '${previous.name}' (${previous.nameSpan.file}:${previous.nameSpan.startLine})",
                        field.nameSpan,
                    )
                }
                columnsByField[field] = column
            }
            return Body(columnsByField, checks, uniques, indexes)
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
         * Records the table's names for [relationCollisions]. A table already claimed by an earlier
         * record is a table collision, reported on its own.
         */
        private fun claim(
            record: RecordType,
            tableName: String,
            primaryKeyName: String?,
            uniques: List<Pair<Field, Unique>>,
            indexes: List<Pair<Field, Index>>,
        ) {
            if (!claimedTables.add(tableName)) return
            relations += Relation(tableName, "table '$tableName'", record.nameSpan)
            primaryKeyName?.let {
                relations += Relation(it, "primary key of '$tableName'", record.nameSpan)
            }
            uniques.forEach { (field, u) ->
                relations += Relation(u.name, "unique '${u.name}'", field.nameSpan)
            }
            indexes.forEach { (field, ix) ->
                relations += Relation(ix.name, "index '${ix.name}'", field.nameSpan)
            }
        }

        private fun column(
            record: RecordType,
            table: String,
            field: Field,
            checks: MutableList<Check>,
            uniques: MutableList<Pair<Field, Unique>>,
            indexes: MutableList<Pair<Field, Index>>,
        ): Column? {
            val where = "field '${record.name}.${field.name}'"
            val sql = field.annotations["sql"]
            if ("strategy" in sql) {
                unsupported("mapping strategies", field.span, where)
                return null
            }
            val rawName = Naming.columnOf(field)
            val name = identifier(rawName, field.nameSpan)
            val override = Naming.override(field.annotations, "type")
            val mapped =
                when (val type = field.type) {
                    is Scalar -> {
                        val precision = type.refinements.precision
                        if (
                            override == null &&
                                precision != null &&
                                precision > SqlTypes.NUMERIC_PRECISION_LIMIT
                        ) {
                            error(
                                SqlCodes.TYPE_LIMIT,
                                "$where: decimal precision $precision exceeds Postgres's limit of ${SqlTypes.NUMERIC_PRECISION_LIMIT}",
                                field.span,
                            )
                            return null
                        }
                        SqlTypes.scalar(type, name, overridden = override != null)
                    }
                    is Ref ->
                        when (val target = schema.lookup(type.target)) {
                            is EnumType -> SqlTypes.enum(target.values.map { it.name }, name)
                            is RecordType -> {
                                unsupported("record references", field.span, where)
                                return null
                            }
                            is UnionType -> {
                                unsupported("unions", field.span, where)
                                return null
                            }
                        }
                    is ListOf -> {
                        unsupported("lists", field.span, where)
                        return null
                    }
                    is MapOf -> {
                        unsupported("maps", field.span, where)
                        return null
                    }
                }
            checks +=
                mapped.checks.map { (suffix, expression) ->
                    Check(identifier("ck_${table}_${rawName}_$suffix", field.nameSpan), expression)
                }
            if ("unique" in sql) {
                uniques +=
                    field to
                        Unique(identifier("uq_${table}_$rawName", field.nameSpan), listOf(name))
            }
            if ("index" in sql) {
                indexes +=
                    field to Index(identifier("ix_${table}_$rawName", field.nameSpan), listOf(name))
            }
            return Column(
                name = name,
                type = override?.let { ColumnType.RAW(it) } ?: mapped.type,
                nullable = field.nullable,
                default = field.default?.let { Naming.literal(it) },
                doc = field.doc,
                notes =
                    if (override != null) listOf(TypeText.of(field.type, field.nullable))
                    else emptyList(),
            )
        }

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
