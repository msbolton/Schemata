package io.schemata.target.sql

import io.schemata.core.ir.Builtin
import io.schemata.core.ir.Field
import io.schemata.core.ir.Namespace
import io.schemata.core.ir.RecordType
import io.schemata.core.ir.Schema
import io.schemata.lang.Diagnostic
import io.schemata.lang.DiagnosticCode
import io.schemata.lang.Span
import io.schemata.target.Lowered

/**
 * Flat scalar records only. The `when` over [Builtin] is exhaustive on purpose: a new IR type fails
 * to compile here rather than being guessed at. Nesting strategy is decided in SCH-28.
 */
object SqlLowering {
    fun lower(schema: Schema): Lowered<RelationalModel> {
        val schemas = schema.namespaces.map { lower(it) }
        val diagnostics =
            schemaCollisions(schema.namespaces) + schema.namespaces.flatMap { tableCollisions(it) }
        return Lowered(RelationalModel(schemas), diagnostics)
    }

    private fun lower(namespace: Namespace): RelationalSchema =
        RelationalSchema(
            path = namespace.name.replace('.', '/') + ".sql",
            schemaName = namespace.name.substringAfterLast('.'),
            tables = namespace.records.map { lower(it) },
        )

    private fun schemaCollisions(namespaces: List<Namespace>): List<Diagnostic> =
        namespaces
            .groupBy { it.name.substringAfterLast('.') }
            .values
            .filter { it.size > 1 }
            .map { colliding ->
                error(
                    SqlCodes.SCHEMA_COLLISION,
                    "namespaces ${englishList(colliding.map { it.name })} " +
                        "${if (colliding.size > 2) "all" else "both"} lower to schema " +
                        "'${colliding.first().name.substringAfterLast('.')}'",
                    colliding.first().span,
                )
            }

    private fun tableCollisions(namespace: Namespace): List<Diagnostic> =
        namespace.records
            .groupBy { Naming.snakeCase(it.name) }
            .values
            .filter { it.size > 1 }
            .map { colliding ->
                error(
                    SqlCodes.TABLE_COLLISION,
                    "records ${englishList(colliding.map { it.name })} " +
                        "${if (colliding.size > 2) "all" else "both"} lower to table " +
                        "'${Naming.snakeCase(colliding.first().name)}'",
                    colliding.first().span,
                )
            }

    private fun englishList(names: List<String>): String =
        if (names.size <= 1) names.joinToString("")
        else names.dropLast(1).joinToString(", ") + " and " + names.last()

    private fun lower(record: RecordType): Table =
        Table(Naming.snakeCase(record.name), record.fields.map { lower(it) })

    private fun lower(field: Field): Column {
        val type =
            when (val t = field.type) {
                is Builtin ->
                    when (t) {
                        Builtin.BOOL -> ColumnType.BOOLEAN
                        Builtin.INT32 -> ColumnType.INTEGER
                        Builtin.STRING -> ColumnType.TEXT
                        Builtin.UUID -> ColumnType.UUID
                    }
            }
        return Column(field.name, type, field.nullable)
    }

    private fun error(code: DiagnosticCode, message: String, span: Span) =
        Diagnostic(code, message, span)
}
