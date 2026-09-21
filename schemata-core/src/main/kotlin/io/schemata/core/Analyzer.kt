package io.schemata.core

import io.schemata.core.ir.EnumType
import io.schemata.core.ir.EnumValue
import io.schemata.core.ir.Field
import io.schemata.core.ir.ListOf
import io.schemata.core.ir.MapOf
import io.schemata.core.ir.Namespace
import io.schemata.core.ir.QualifiedName
import io.schemata.core.ir.RecordType
import io.schemata.core.ir.Ref
import io.schemata.core.ir.Reserved
import io.schemata.core.ir.Schema
import io.schemata.core.ir.Type
import io.schemata.core.ir.TypeDecl
import io.schemata.core.ir.UnionMember
import io.schemata.core.ir.UnionType
import io.schemata.lang.Diagnostic
import io.schemata.lang.DiagnosticCode
import io.schemata.lang.Span
import io.schemata.lang.ast.AliasDecl
import io.schemata.lang.ast.Declaration
import io.schemata.lang.ast.EnumDecl
import io.schemata.lang.ast.RecordDecl
import io.schemata.lang.ast.SourceFile
import io.schemata.lang.ast.UnionDecl
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

    fun analyze(
        files: List<SourceFile>,
        options: AnalysisOptions = AnalysisOptions.DEFAULT,
    ): AnalysisResult {
        val diagnostics = mutableListOf<Diagnostic>()
        val sorted = files.sortedBy { it.path }
        sorted.forEach { diagnostics += Unsupported.check(it) }
        val index = DeclarationIndex(sorted, diagnostics)
        val resolver = Resolver(index, sorted, diagnostics)
        val namespaces =
            Recursion.mark(
                sorted
                    .groupBy { it.namespace.name }
                    .toSortedMap()
                    .map { (name, group) ->
                        analyzeNamespace(name, group, index, resolver, options, diagnostics)
                    }
            )
        resolver.finish()
        val schema = if (diagnostics.hasErrors) null else Schema(namespaces)
        return AnalysisResult(schema, diagnostics)
    }

    private fun analyzeNamespace(
        name: String,
        files: List<SourceFile>,
        index: DeclarationIndex,
        resolver: Resolver,
        options: AnalysisOptions,
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
        val declarations =
            files.flatMap { file ->
                file.declarations.mapNotNull { decl ->
                    analyzeDeclaration(
                        decl,
                        Scope(file, name, emptyList()),
                        resolver,
                        options,
                        diagnostics,
                    )
                }
            }
        return Namespace(name, declarations, first.namespace.span)
    }

    private fun analyzeDeclaration(
        decl: Declaration,
        scope: Scope,
        resolver: Resolver,
        options: AnalysisOptions,
        diagnostics: MutableList<Diagnostic>,
    ): TypeDecl? {
        val kind = DeclarationIndex.kindOf(decl)
        if (!upperCamel.matches(decl.name)) {
            diagnostics +=
                error(
                    CoreCodes.TYPE_NAMING,
                    "$kind name '${decl.name}' must be UpperCamel",
                    decl.nameSpan,
                )
        }
        val qualifiedName = QualifiedName(scope.namespace, scope.enclosing + decl.name)
        return when (decl) {
            is RecordDecl ->
                analyzeRecord(decl, qualifiedName, scope, resolver, options, diagnostics)
            is EnumDecl -> analyzeEnum(decl, qualifiedName, options, diagnostics)
            is UnionDecl -> analyzeUnion(decl, qualifiedName, scope, resolver, options, diagnostics)
            is AliasDecl -> null // transparent: substituted at every use by the resolver
        }
    }

    private fun analyzeRecord(
        record: RecordDecl,
        qualifiedName: QualifiedName,
        scope: Scope,
        resolver: Resolver,
        options: AnalysisOptions,
        diagnostics: MutableList<Diagnostic>,
    ): RecordType {
        val inner = scope.copy(enclosing = scope.enclosing + record.name)
        val reserved = Ordinals.reserved(record.reserved, diagnostics)
        val ordinals =
            Ordinals.assign(
                "record",
                "field",
                record.name,
                record.nameSpan,
                record.fields.map {
                    Ordinals.Element(it.ordinal, it.ordinalSpan, it.name, it.nameSpan)
                },
                reserved,
                options,
                diagnostics,
            )
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
                val resolved = resolver.resolve(field.type, inner) ?: return@mapIndexedNotNull null
                Field(
                    ordinal = ordinals[index],
                    name = field.name,
                    type = resolved.type,
                    nullable = resolved.nullable,
                    default = field.default,
                    aliasName = resolved.aliasName,
                    doc = field.doc,
                    span = field.span,
                    nameSpan = field.nameSpan,
                )
            }
        val nested =
            record.nested.mapNotNull {
                analyzeDeclaration(it, inner, resolver, options, diagnostics)
            }
        return RecordType(
            qualifiedName = qualifiedName,
            name = record.name,
            fields = fields,
            reserved = reserved,
            recursive = false,
            nested = nested,
            doc = record.doc,
            span = record.span,
            nameSpan = record.nameSpan,
        )
    }

    private fun analyzeEnum(
        decl: EnumDecl,
        qualifiedName: QualifiedName,
        options: AnalysisOptions,
        diagnostics: MutableList<Diagnostic>,
    ): EnumType {
        if (decl.values.isEmpty())
            diagnostics +=
                error(CoreCodes.EMPTY_ENUM, "enum '${decl.name}' has no values", decl.nameSpan)
        val reserved = Ordinals.reserved(decl.reserved, diagnostics)
        val ordinals =
            Ordinals.assign(
                "enum",
                "value",
                decl.name,
                decl.nameSpan,
                decl.values.map {
                    Ordinals.Element(it.ordinal, it.ordinalSpan, it.name, it.nameSpan)
                },
                reserved,
                options,
                diagnostics,
            )
        val seen = mutableSetOf<String>()
        val values =
            decl.values.mapIndexed { index, value ->
                if (!lowerSnake.matches(value.name)) {
                    diagnostics +=
                        error(
                            CoreCodes.ENUM_VALUE_NAMING,
                            "enum value '${value.name}' must be lower_snake",
                            value.nameSpan,
                        )
                }
                if (!seen.add(value.name)) {
                    diagnostics +=
                        error(
                            CoreCodes.DUPLICATE_ENUM_VALUE,
                            "enum value '${value.name}' is declared more than once in enum '${decl.name}'",
                            value.nameSpan,
                        )
                }
                EnumValue(ordinals[index], value.name, value.doc, value.span, value.nameSpan)
            }
        return EnumType(
            qualifiedName,
            decl.name,
            values,
            reserved,
            emptyList(),
            decl.doc,
            decl.span,
            decl.nameSpan,
        )
    }

    private fun analyzeUnion(
        decl: UnionDecl,
        qualifiedName: QualifiedName,
        scope: Scope,
        resolver: Resolver,
        options: AnalysisOptions,
        diagnostics: MutableList<Diagnostic>,
    ): UnionType {
        val ordinals =
            Ordinals.assign(
                "union",
                "member",
                decl.name,
                decl.nameSpan,
                decl.members.map {
                    Ordinals.Element(it.ordinal, it.ordinalSpan, it.type.name, it.type.nameSpan)
                },
                Reserved.NONE,
                options,
                diagnostics,
            )
        val seen = mutableSetOf<Type>()
        val members =
            decl.members.mapIndexedNotNull { index, member ->
                val resolved = resolver.resolve(member.type, scope) ?: return@mapIndexedNotNull null
                val type = resolved.type
                val ok =
                    when {
                        resolved.nullable || type is ListOf || type is MapOf -> {
                            diagnostics +=
                                error(
                                    CoreCodes.UNION_MEMBER_KIND,
                                    "union members must be named types or scalars",
                                    member.type.span,
                                )
                            false
                        }
                        type is Ref && type.target == qualifiedName -> {
                            diagnostics +=
                                error(
                                    CoreCodes.UNION_SELF_MEMBER,
                                    "union '${decl.name}' may not contain itself",
                                    member.type.nameSpan,
                                )
                            false
                        }
                        !seen.add(type) -> {
                            diagnostics +=
                                error(
                                    CoreCodes.DUPLICATE_UNION_MEMBER,
                                    "union member '${member.type.name}' is repeated",
                                    member.type.nameSpan,
                                )
                            false
                        }
                        else -> true
                    }
                if (!ok) return@mapIndexedNotNull null
                UnionMember(ordinals[index], type, member.doc, member.span)
            }
        return UnionType(
            qualifiedName,
            decl.name,
            members,
            emptyList(),
            decl.doc,
            decl.span,
            decl.nameSpan,
        )
    }

    private fun error(code: DiagnosticCode, message: String, span: Span) =
        Diagnostic(code, message, span)
}
