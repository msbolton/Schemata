package io.schemata.target.sql

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
import io.schemata.lang.Span
import io.schemata.target.Lowered
import io.schemata.target.TypeText

/**
 * Lowers flat records to tables: every builtin, refinements as CHECK constraints, defaults, enums
 * as constrained text, `@sql` overrides. References, unions, lists, maps, nested declarations, and
 * mapping strategies are reported at the boundary until the structural lowering lands (SCH-28).
 * `reserved` ordinals and names have no relational meaning and are accepted without a diagnostic.
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
            val tables = namespace.declarations.mapNotNull { decl(it) }
            return RelationalSchema(
                path = namespace.name.replace('.', '/') + ".sql",
                schemaName = schemaName,
                tables = tables,
            )
        }

        private fun decl(decl: TypeDecl): Table? =
            when (decl) {
                is RecordType -> record(decl)
                is EnumType -> null // appears only where it is used
                is UnionType -> null
            }

        private fun record(record: RecordType): Table {
            val tableName = Naming.tableOf(record)
            val checks = mutableListOf<Check>()
            val columns = record.fields.mapNotNull { column(record, tableName, it, checks) }
            record.nested.forEach { unsupported("nested declarations", it.nameSpan) }
            return Table(name = tableName, columns = columns, checks = checks, doc = record.doc)
        }

        private fun column(
            record: RecordType,
            table: String,
            field: Field,
            checks: MutableList<Check>,
        ): Column? {
            val where = "field '${record.name}.${field.name}'"
            if ("strategy" in field.annotations["sql"]) {
                unsupported("mapping strategies", field.span, where)
                return null
            }
            val name = Naming.columnOf(field)
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
                    Check("ck_${table}_${name}_$suffix", expression)
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

        private fun unsupported(what: String, span: Span, where: String? = null) {
            val prefix = where?.let { "$it: " } ?: ""
            diagnostics +=
                Diagnostic(
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
