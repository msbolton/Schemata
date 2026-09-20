package io.schemata.core

import io.schemata.core.ir.Builtin
import io.schemata.core.ir.Field
import io.schemata.core.ir.RecordType
import io.schemata.core.ir.Schema
import io.schemata.lang.Category
import io.schemata.lang.Diagnostic
import io.schemata.lang.Severity
import io.schemata.lang.Span
import io.schemata.lang.ast.RecordDecl
import io.schemata.lang.ast.SourceFile
import io.schemata.lang.hasErrors

/** [schema] is null exactly when [diagnostics] contains an error. */
data class AnalysisResult(val schema: Schema?, val diagnostics: List<Diagnostic>)

/** AST to IR, plus every check the language performs before any target sees the schema. */
object Analyzer {
    private val upperCamel = Regex("[A-Z][A-Za-z0-9]*")
    private val lowerSnake = Regex("[a-z][a-z0-9_]*")

    fun analyze(file: SourceFile): AnalysisResult {
        val diagnostics = mutableListOf<Diagnostic>()
        file.namespace.name.split(".").forEach { segment ->
            if (!lowerSnake.matches(segment)) {
                diagnostics +=
                    error("namespace segment '$segment' must be lower_snake", file.namespace.span)
            }
        }
        val declared = file.declarations.map { it.name }.toSet()
        val seenRecords = mutableSetOf<String>()

        val records =
            file.declarations.filterIsInstance<RecordDecl>().map { record ->
                if (!upperCamel.matches(record.name)) {
                    diagnostics +=
                        error("record name '${record.name}' must be UpperCamel", record.span)
                }
                if (!seenRecords.add(record.name)) {
                    diagnostics +=
                        error("record '${record.name}' is declared more than once", record.span)
                }
                val seenFields = mutableSetOf<String>()
                val fields =
                    record.fields.mapIndexedNotNull { index, field ->
                        if (!lowerSnake.matches(field.name)) {
                            diagnostics +=
                                error("field name '${field.name}' must be lower_snake", field.span)
                        }
                        if (!seenFields.add(field.name)) {
                            diagnostics +=
                                error(
                                    "field '${field.name}' is declared more than once in record '${record.name}'",
                                    field.span,
                                )
                        }
                        val type = Builtin.byName(field.type.name)
                        if (type == null) {
                            diagnostics +=
                                if (field.type.name in declared) {
                                    error(
                                        "'${field.type.name}' is a record; record-typed fields are not supported yet",
                                        field.type.span,
                                    )
                                } else {
                                    error("unknown type '${field.type.name}'", field.type.span)
                                }
                            return@mapIndexedNotNull null
                        }
                        Field(
                            ordinal = index + 1,
                            name = field.name,
                            type = type,
                            nullable = field.type.nullable,
                        )
                    }
                RecordType(record.name, fields)
            }

        val schema = if (diagnostics.hasErrors) null else Schema(file.namespace.name, records)
        return AnalysisResult(schema, diagnostics)
    }

    private fun error(message: String, span: Span) =
        Diagnostic(Severity.ERROR, Category.SEMANTIC, message, span)
}
