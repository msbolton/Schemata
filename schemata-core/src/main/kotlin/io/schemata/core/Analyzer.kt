package io.schemata.core

import io.schemata.core.ir.Builtin
import io.schemata.core.ir.Field
import io.schemata.core.ir.Namespace
import io.schemata.core.ir.RecordType
import io.schemata.core.ir.Schema
import io.schemata.lang.Diagnostic
import io.schemata.lang.DiagnosticCode
import io.schemata.lang.Span
import io.schemata.lang.ast.RecordDecl
import io.schemata.lang.ast.SourceFile
import io.schemata.lang.hasErrors

/** [schema] is null exactly when [diagnostics] contains an error. */
data class AnalysisResult(val schema: Schema?, val diagnostics: List<Diagnostic>)

/**
 * AST to IR over a whole compilation, plus every check the language performs before any target sees
 * the schema. Files are processed in sorted-path order; namespaces come out sorted by name.
 */
object Analyzer {
    private val upperCamel = Regex("[A-Z][A-Za-z0-9]*")
    private val lowerSnake = Regex("[a-z][a-z0-9_]*")

    fun analyze(files: List<SourceFile>): AnalysisResult {
        val diagnostics = mutableListOf<Diagnostic>()
        files.sortedBy { it.path }.forEach { diagnostics += Unsupported.check(it) }
        val namespaces =
            files
                .sortedBy { it.path }
                .groupBy { it.namespace.name }
                .toSortedMap()
                .map { (name, group) -> analyzeNamespace(name, group, diagnostics) }
        val schema = if (diagnostics.hasErrors) null else Schema(namespaces)
        return AnalysisResult(schema, diagnostics)
    }

    private fun analyzeNamespace(
        name: String,
        files: List<SourceFile>,
        diagnostics: MutableList<Diagnostic>,
    ): Namespace {
        val first = files.first()
        name.split(".").forEach { segment ->
            if (!lowerSnake.matches(segment)) {
                diagnostics +=
                    error(
                        CoreCodes.NAMESPACE_SEGMENT_NAMING,
                        "namespace segment '$segment' must be lower_snake",
                        first.namespace.span,
                    )
            }
        }
        val declared = files.flatMap { it.declarations }.map { it.name }.toSet()
        val seen = mutableMapOf<String, Span>()
        val records =
            files.flatMap { file ->
                file.declarations.filterIsInstance<RecordDecl>().map { record ->
                    analyzeRecord(record, declared, seen, diagnostics)
                }
            }
        return Namespace(name, records, first.namespace.span)
    }

    private fun analyzeRecord(
        record: RecordDecl,
        declared: Set<String>,
        seen: MutableMap<String, Span>,
        diagnostics: MutableList<Diagnostic>,
    ): RecordType {
        if (!upperCamel.matches(record.name)) {
            diagnostics +=
                error(
                    CoreCodes.RECORD_NAMING,
                    "record name '${record.name}' must be UpperCamel",
                    record.nameSpan,
                )
        }
        val previous = seen.putIfAbsent(record.name, record.nameSpan)
        if (previous != null) {
            diagnostics +=
                if (previous.file == record.span.file) {
                    error(
                        CoreCodes.DUPLICATE_RECORD,
                        "record '${record.name}' is declared more than once",
                        record.nameSpan,
                    )
                } else {
                    error(
                        CoreCodes.DUPLICATE_RECORD,
                        "record '${record.name}' is declared in both " +
                            "${previous.file}:${previous.startLine} and " +
                            "${record.span.file}:${record.span.startLine}",
                        record.nameSpan,
                    )
                }
        }
        val seenFields = mutableSetOf<String>()
        val fields =
            record.fields.mapIndexedNotNull { index, field ->
                if (!lowerSnake.matches(field.name)) {
                    diagnostics +=
                        error(
                            CoreCodes.FIELD_NAMING,
                            "field name '${field.name}' must be lower_snake",
                            field.nameSpan,
                        )
                }
                if (!seenFields.add(field.name)) {
                    diagnostics +=
                        error(
                            CoreCodes.DUPLICATE_FIELD,
                            "field '${field.name}' is declared more than once in record '${record.name}'",
                            field.nameSpan,
                        )
                }
                val type = Builtin.byName(field.type.name)
                if (type == null) {
                    diagnostics +=
                        if (field.type.name in declared) {
                            error(
                                CoreCodes.RECORD_TYPED_FIELD,
                                "'${field.type.name}' is a record; record-typed fields are not supported yet",
                                field.type.nameSpan,
                            )
                        } else {
                            error(
                                CoreCodes.UNKNOWN_TYPE,
                                "unknown type '${field.type.name}'",
                                field.type.nameSpan,
                            )
                        }
                    return@mapIndexedNotNull null
                }
                Field(
                    ordinal = index + 1,
                    name = field.name,
                    type = type,
                    nullable = field.type.nullable,
                    span = field.span,
                )
            }
        return RecordType(record.name, fields, record.span)
    }

    private fun error(code: DiagnosticCode, message: String, span: Span) =
        Diagnostic(code, message, span)
}
