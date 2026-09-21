package io.schemata.target.sql

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
import io.schemata.core.ir.TypeDecl
import io.schemata.core.ir.UnionType
import io.schemata.lang.Diagnostic
import io.schemata.lang.DiagnosticCode
import io.schemata.lang.Span
import io.schemata.target.Lowered

/**
 * Lowers flat records over `bool`, `int32`, `string`, and `uuid`. Every other IR shape is reported
 * at its span with the ticket that will lower it; the `when`s are exhaustive so a new IR shape
 * fails to compile here rather than being guessed at. Nesting strategy is decided in SCH-28.
 * `reserved` ordinals and names have no relational meaning and are accepted without a diagnostic.
 */
object SqlLowering {
    fun lower(schema: Schema): Lowered<RelationalModel> {
        val diagnostics = mutableListOf<Diagnostic>()
        val schemas = schema.namespaces.map { lower(it, diagnostics) }
        val collisions =
            schemaCollisions(schema.namespaces) + schema.namespaces.flatMap { tableCollisions(it) }
        return Lowered(RelationalModel(schemas), collisions + diagnostics)
    }

    private fun lower(
        namespace: Namespace,
        diagnostics: MutableList<Diagnostic>,
    ): RelationalSchema =
        RelationalSchema(
            path = namespace.name.replace('.', '/') + ".sql",
            schemaName = namespace.name.substringAfterLast('.'),
            tables = namespace.declarations.mapNotNull { lower(it, diagnostics) },
        )

    private fun lower(decl: TypeDecl, diagnostics: MutableList<Diagnostic>): Table? =
        when (decl) {
            is RecordType -> {
                val columns = decl.fields.mapNotNull { lower(decl, it, diagnostics) }
                decl.nested.forEach {
                    unsupported("nested declarations", "SCH-28", it.nameSpan, diagnostics)
                }
                Table(Naming.snakeCase(decl.name), columns)
            }
            is EnumType -> {
                unsupported("enums", "SCH-28", decl.nameSpan, diagnostics)
                null
            }
            is UnionType -> {
                unsupported("unions", "SCH-28", decl.nameSpan, diagnostics)
                null
            }
        }

    private fun lower(
        record: RecordType,
        field: Field,
        diagnostics: MutableList<Diagnostic>,
    ): Column? {
        val where = "field '${record.name}.${field.name}'"
        if (field.default != null) {
            diagnostics +=
                Diagnostic(
                    SqlCodes.UNSUPPORTED_VALUE,
                    "$where: target 'sql' cannot lower field defaults yet (SCH-32)",
                    field.span,
                )
            return null
        }
        if (field.type.hasRefinements()) {
            diagnostics +=
                Diagnostic(
                    SqlCodes.UNSUPPORTED_VALUE,
                    "$where: target 'sql' cannot lower type refinements yet (SCH-31)",
                    field.span,
                )
            return null
        }
        val type =
            when (val t = field.type) {
                is Scalar ->
                    when (t.builtin) {
                        Builtin.BOOL -> ColumnType.BOOLEAN
                        Builtin.INT32 -> ColumnType.INTEGER
                        Builtin.STRING -> ColumnType.TEXT
                        Builtin.UUID -> ColumnType.UUID
                        Builtin.INT64,
                        Builtin.FLOAT32,
                        Builtin.FLOAT64,
                        Builtin.DECIMAL,
                        Builtin.BYTES,
                        Builtin.DATE,
                        Builtin.TIME,
                        Builtin.INSTANT,
                        Builtin.DURATION -> {
                            unsupported(
                                t.builtin.typeName,
                                "SCH-31",
                                field.span,
                                diagnostics,
                                where,
                            )
                            return null
                        }
                    }
                is Ref -> {
                    unsupported("record references", "SCH-28", field.span, diagnostics, where)
                    return null
                }
                is ListOf -> {
                    unsupported("lists", "SCH-28", field.span, diagnostics, where)
                    return null
                }
                is MapOf -> {
                    unsupported("maps", "SCH-28", field.span, diagnostics, where)
                    return null
                }
            }
        return Column(field.name, type, field.nullable)
    }

    private fun Type.hasRefinements(): Boolean =
        when (this) {
            is Scalar -> refinements != Refinements()
            is ListOf -> refinements != Refinements() || element.hasRefinements()
            is MapOf ->
                refinements != Refinements() || key.hasRefinements() || value.hasRefinements()
            is Ref -> false
        }

    private fun unsupported(
        what: String,
        ticket: String,
        span: Span,
        diagnostics: MutableList<Diagnostic>,
        where: String? = null,
    ) {
        val prefix = where?.let { "$it: " } ?: ""
        diagnostics +=
            Diagnostic(
                SqlCodes.UNSUPPORTED_SHAPE,
                "${prefix}target 'sql' cannot lower $what yet ($ticket)",
                span,
            )
    }

    private fun schemaCollisions(namespaces: List<Namespace>): List<Diagnostic> =
        namespaces
            .groupBy { it.name.substringAfterLast('.') }
            .values
            .filter { it.size > 1 }
            .map { colliding ->
                error(
                    SqlCodes.SCHEMA_COLLISION,
                    "namespaces ${englishList(colliding.map { it.name })} ${if (colliding.size > 2) "all" else "both"} lower to schema '${colliding.first().name.substringAfterLast('.')}'",
                    colliding.first().span,
                )
            }

    private fun tableCollisions(namespace: Namespace): List<Diagnostic> =
        namespace.declarations
            .filterIsInstance<RecordType>()
            .groupBy { Naming.snakeCase(it.name) }
            .values
            .filter { it.size > 1 }
            .map { colliding ->
                error(
                    SqlCodes.TABLE_COLLISION,
                    "records ${englishList(colliding.map { it.name })} ${if (colliding.size > 2) "all" else "both"} lower to table '${Naming.snakeCase(colliding.first().name)}'",
                    colliding.first().span,
                )
            }

    private fun englishList(names: List<String>): String =
        if (names.size <= 1) names.joinToString("")
        else names.dropLast(1).joinToString(", ") + " and " + names.last()

    private fun error(code: DiagnosticCode, message: String, span: Span) =
        Diagnostic(code, message, span)
}
