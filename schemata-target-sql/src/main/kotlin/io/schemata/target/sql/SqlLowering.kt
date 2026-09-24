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
        val schemaNames = schema.namespaces.associate { it.name to Naming.schemaOf(it) }
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
        fun lower(): RelationalSchema {
            tableCollisions()
            val tables = namespace.declarations.mapNotNull { decl(it) }
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

        private fun decl(decl: TypeDecl): Table? =
            when (decl) {
                is RecordType -> record(decl)
                is EnumType -> null // appears only where it is used
                is UnionType -> null
            }

        private fun record(record: RecordType): Table {
            val tableName = identifier(Naming.tableOf(record), record.nameSpan)
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
            val checks = mutableListOf<Check>()
            val uniques = mutableListOf<Unique>()
            val indexes = mutableListOf<Index>()
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
            record.nested.forEach { unsupported("nested declarations", it.nameSpan) }
            val keyFields =
                if (recordKeyNames != null) {
                    recordKeyNames.mapNotNull { name ->
                        record.fields.firstOrNull { it.name == name }
                    }
                } else {
                    fieldKeys
                }
            val primaryKey = keyFields.mapNotNull { columnsByField[it]?.name }
            val primaryKeyName =
                if (primaryKey.isEmpty()) null else identifier("pk_$tableName", record.nameSpan)
            return Table(
                name = tableName,
                columns = columnsByField.values.toList(),
                primaryKey = primaryKey,
                primaryKeyName = primaryKeyName,
                checks = checks,
                uniques = uniques,
                indexes = indexes,
                doc = record.doc,
            )
        }

        private fun column(
            record: RecordType,
            table: String,
            field: Field,
            checks: MutableList<Check>,
            uniques: MutableList<Unique>,
            indexes: MutableList<Index>,
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
                    is Scalar -> SqlTypes.scalar(type, name, overridden = override != null)
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
                uniques += Unique(identifier("uq_${table}_$rawName", field.nameSpan), listOf(name))
            }
            if ("index" in sql) {
                indexes += Index(identifier("ix_${table}_$rawName", field.nameSpan), listOf(name))
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

        /** Truncates to Postgres's limit, reporting once per identifier. */
        private fun identifier(name: String, span: Span): String {
            val result = Naming.identifier(name)
            if (result != name) {
                error(
                    SqlCodes.IDENTIFIER_TRUNCATED,
                    "identifier '$name' exceeds 63 bytes; truncated to '$result'",
                    span,
                )
            }
            return result
        }

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
