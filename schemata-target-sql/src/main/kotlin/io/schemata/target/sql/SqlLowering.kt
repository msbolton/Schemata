package io.schemata.target.sql

import io.schemata.core.ir.Builtin
import io.schemata.core.ir.Field
import io.schemata.core.ir.RecordType
import io.schemata.core.ir.Schema
import io.schemata.lang.Category
import io.schemata.lang.Diagnostic
import io.schemata.lang.Severity
import io.schemata.target.Lowered

/**
 * Flat scalar records only. The `when` over [Builtin] is exhaustive on purpose: a new IR type fails
 * to compile here rather than being guessed at. Nesting strategy is decided in SCH-28.
 */
object SqlLowering {
    fun lower(schema: Schema): Lowered<RelationalSchema> {
        val tables = schema.records.map { lower(it) }
        return Lowered(
            RelationalSchema(schema.namespace.substringAfterLast('.'), tables),
            collisionDiagnostics(schema.records),
        )
    }

    private fun collisionDiagnostics(records: List<RecordType>): List<Diagnostic> =
        records
            .groupBy { Naming.snakeCase(it.name) }
            .values
            .filter { it.size > 1 }
            .map { colliding ->
                Diagnostic(
                    Severity.ERROR,
                    Category.SEMANTIC,
                    "records ${englishList(colliding.map { it.name })} both lower to table " +
                        "'${Naming.snakeCase(colliding.first().name)}'",
                    null,
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
}
