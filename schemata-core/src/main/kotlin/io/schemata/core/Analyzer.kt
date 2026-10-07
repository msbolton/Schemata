package io.schemata.core

import io.schemata.core.annotations.Element
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
import io.schemata.core.ir.selfAndNested
import io.schemata.lang.Diagnostic
import io.schemata.lang.DiagnosticCode
import io.schemata.lang.Span
import io.schemata.lang.ast.AliasDecl
import io.schemata.lang.ast.Annotation
import io.schemata.lang.ast.AnnotationArg
import io.schemata.lang.ast.AnnotationValue as AstValue
import io.schemata.lang.ast.Declaration
import io.schemata.lang.ast.EnumDecl
import io.schemata.lang.ast.Literal
import io.schemata.lang.ast.RecordDecl
import io.schemata.lang.ast.SourceFile
import io.schemata.lang.ast.UnionDecl
import io.schemata.lang.ast.UnionMemberDecl
import io.schemata.lang.hasErrors

/** [schema] is null exactly when [diagnostics] contains an error. */
data class AnalysisResult(val schema: Schema?, val diagnostics: List<Diagnostic>)

/**
 * AST to IR over a whole compilation, plus every check the language performs before any target sees
 * the schema. Files are processed in sorted-path order; namespaces come out sorted by name. Every
 * namespace's declarations are lowered before any service, then each namespace's services in
 * sorted-path then source order.
 */
object Analyzer {
    internal val upperCamel = Regex("[A-Z][A-Za-z0-9]*")
    internal val lowerSnake = Regex("[a-z][a-z0-9]*(_[a-z0-9]+)*")

    // `null` lexes as a name so that `= null` can be read; it is reserved as a field, enum value,
    // and namespace segment name like `true` and `false`, since `= null` always means the literal
    // and never an enum value of that name.
    private const val NULL_NAME = "null"

    fun analyze(
        files: List<SourceFile>,
        options: AnalysisOptions = AnalysisOptions.DEFAULT,
    ): AnalysisResult {
        val diagnostics = mutableListOf<Diagnostic>()
        val sorted = files.sortedBy { it.path }.map { Hoisting.apply(it) { d -> diagnostics += d } }
        val index = DeclarationIndex(sorted, diagnostics)
        val resolver = Resolver(index, sorted, diagnostics, options.references)
        val annotations = AnnotationChecker(options.annotations, diagnostics)
        val groups = sorted.groupBy { it.namespace.name }.toSortedMap()
        val lowered =
            groups.map { (name, group) ->
                analyzeNamespace(name, group, index, resolver, annotations, options, diagnostics)
            }
        // services after every namespace's declarations: a payload may name another namespace's
        // record, and a path parameter is judged by its field's lowered type
        val declarations =
            lowered
                .flatMap { ns -> ns.declarations.flatMap { it.selfAndNested() } }
                .associateBy { it.qualifiedName }
        val namespaces =
            Recursion.mark(
                lowered.map { ns ->
                    val routes = Services.Routes()
                    val services =
                        groups.getValue(ns.name).flatMap { file ->
                            file.services.map {
                                Services.analyze(
                                    it,
                                    Scope(file, ns.name, emptyList()),
                                    index,
                                    resolver,
                                    annotations,
                                    options,
                                    declarations,
                                    routes,
                                    diagnostics,
                                )
                            }
                        }
                    ns.copy(services = services)
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
        annotations: AnnotationChecker,
        options: AnalysisOptions,
        diagnostics: MutableList<Diagnostic>,
    ): Namespace {
        val first = files.first()
        name.split(".").forEach { segment ->
            if (segment == NULL_NAME) {
                diagnostics +=
                    error(
                        CoreCodes.NAMESPACE_SEGMENT_NAMING,
                        "namespace segment '$segment' is reserved",
                        first.namespace.span,
                        help = "rename the segment, for example `${Suggest.lowerSnake(segment)}`",
                    )
            } else if (!lowerSnake.matches(segment)) {
                val suggestion = Suggest.example(segment, Suggest.lowerSnake(segment))
                diagnostics +=
                    error(
                        CoreCodes.NAMESPACE_SEGMENT_NAMING,
                        "namespace segment '$segment' must be lower_snake",
                        first.namespace.span,
                        help =
                            "write the segment in lower_snake" +
                                (suggestion?.let { ", for example `$it`" } ?: ""),
                    )
            }
        }
        val nsAnnotations = annotations.check(files.flatMap { it.annotations }, Element.NAMESPACE)
        val declarations =
            files.flatMap { file ->
                file.declarations.mapNotNull { decl ->
                    analyzeDeclaration(
                        decl,
                        Scope(file, name, emptyList()),
                        index,
                        resolver,
                        annotations,
                        options,
                        diagnostics,
                    )
                }
            }
        return Namespace(
            name,
            declarations,
            first.namespace.span,
            nsAnnotations,
            doc = files.firstNotNullOfOrNull { it.doc },
        )
    }

    private fun analyzeDeclaration(
        decl: Declaration,
        scope: Scope,
        index: DeclarationIndex,
        resolver: Resolver,
        annotations: AnnotationChecker,
        options: AnalysisOptions,
        diagnostics: MutableList<Diagnostic>,
    ): TypeDecl? {
        val kind = DeclarationIndex.kindOf(decl)
        if (!upperCamel.matches(decl.name)) {
            val suggestion = Suggest.example(decl.name, Suggest.upperCamel(decl.name))
            diagnostics +=
                error(
                    CoreCodes.TYPE_NAMING,
                    "$kind name '${decl.name}' must be UpperCamel",
                    decl.nameSpan,
                    help = suggestion?.let { "rename it `$it`" } ?: "rename it in UpperCamel",
                )
        }
        val qualifiedName = QualifiedName(scope.namespace, scope.enclosing + decl.name)
        return when (decl) {
            is RecordDecl ->
                analyzeRecord(
                    decl,
                    qualifiedName,
                    scope,
                    index,
                    resolver,
                    annotations,
                    options,
                    diagnostics,
                )
            is EnumDecl -> analyzeEnum(decl, qualifiedName, annotations, options, diagnostics)
            is UnionDecl ->
                analyzeUnion(
                    decl,
                    qualifiedName,
                    scope,
                    index,
                    resolver,
                    annotations,
                    options,
                    diagnostics,
                )
            is AliasDecl -> {
                annotations.check(
                    decl.annotations,
                    Element.ALIAS,
                ) // checked, then dropped: aliases have no IR node
                resolver.checkAlias(decl, scope)
                null
            }
        }
    }

    private fun analyzeRecord(
        record: RecordDecl,
        qualifiedName: QualifiedName,
        scope: Scope,
        index: DeclarationIndex,
        resolver: Resolver,
        annotations: AnnotationChecker,
        options: AnalysisOptions,
        diagnostics: MutableList<Diagnostic>,
    ): RecordType {
        val (listAnnotations, tuned) =
            record.annotations.partition { it.block && it.name in modelListNames }
        val recordAnnotations = annotations.check(tuned, Element.RECORD)
        val inner = scope.copy(enclosing = scope.enclosing + record.name)
        val reserved = Ordinals.reserved(record.reserved, CoreCodes.FIELD_NAMING, diagnostics)
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
            record.fields.mapIndexedNotNull { i, field ->
                if (field.name == NULL_NAME) {
                    diagnostics +=
                        error(
                            CoreCodes.FIELD_NAMING,
                            "field name '${field.name}' is reserved",
                            field.nameSpan,
                            help = "rename it `${Suggest.lowerSnake(field.name)}`",
                        )
                } else if (!lowerSnake.matches(field.name)) {
                    val suggestion = Suggest.example(field.name, Suggest.lowerSnake(field.name))
                    diagnostics +=
                        error(
                            CoreCodes.FIELD_NAMING,
                            "field name '${field.name}' must be lower_snake",
                            field.nameSpan,
                            help =
                                suggestion?.let { "rename it `$it`" } ?: "rename it in lower_snake",
                        )
                }
                if (!seenFields.add(field.name)) {
                    diagnostics +=
                        error(
                            CoreCodes.DUPLICATE_FIELD,
                            "field '${field.name}' is declared more than once in record '${record.name}'",
                            field.nameSpan,
                            help = "rename or remove one of the two fields",
                        )
                }
                val written = resolver.resolve(field.type, inner) ?: return@mapIndexedNotNull null
                // options bound the type before the default is judged against it
                val (resolved, lowered) =
                    Options.refine(field.options, field.type, written, { index.find(it)?.decl }) {
                        diagnostics += it
                    }
                Field(
                    ordinal = ordinals[i],
                    name = field.name,
                    type = resolved.type,
                    nullable = resolved.nullable,
                    default =
                        field.default?.let {
                            DefaultChecker.check(it, resolved.type, index, diagnostics)
                        },
                    aliasName = resolved.aliasName,
                    doc = field.doc,
                    span = field.span,
                    nameSpan = field.nameSpan,
                    annotations = annotations.check(field.annotations, Element.FIELD),
                    key = lowered.key,
                    unique = lowered.unique,
                    index = lowered.index,
                )
            }
        val lists = modelLists(record, listAnnotations, diagnostics)
        val nested =
            record.nested.mapNotNull {
                analyzeDeclaration(it, inner, index, resolver, annotations, options, diagnostics)
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
            annotations = recordAnnotations,
            compositeKey = lists.key,
            uniques = lists.uniques,
            indexes = lists.indexes,
        )
    }

    // `@@id`, `@@unique`, and `@@index` name a model's fields; they are language facts the
    // analyzer reads itself, never annotations a target tunes, so the checker never sees them. A
    // single-`@` `@id(...)` is not one of them and goes to the checker like any other annotation.
    private val modelListNames = setOf("id", "unique", "index")

    private class ModelLists(
        val key: List<String>,
        val uniques: List<List<String>>,
        val indexes: List<List<String>>,
    )

    /**
     * Reads `@@id(a, b)`, `@@unique(a, b)`, and `@@index(a, b)`: each names fields of the record,
     * each field once. A record has at most one `@@id`; when it has one, it is the key, in its
     * order, whatever `{ id }` its fields carry.
     */
    private fun modelLists(
        record: RecordDecl,
        written: List<Annotation>,
        diagnostics: MutableList<Diagnostic>,
    ): ModelLists {
        var key = emptyList<String>()
        var keyed = false
        val uniques = mutableListOf<List<String>>()
        val indexes = mutableListOf<List<String>>()
        val declared = record.fields.map { it.name }.toSet()
        for (annotation in written) {
            val display = "@@${annotation.name}"
            val example = "write `$display(a, b)` with the fields' names"
            val names =
                annotation.args.map { arg ->
                    ((arg as? AnnotationArg.Positional)?.value as? AstValue.Lit)?.literal
                        as? Literal.NameLit
                }
            if (names.isEmpty() || names.any { it == null }) {
                diagnostics +=
                    error(
                        CoreCodes.ANNOTATION_VALUE,
                        if (names.isEmpty()) "$display names no fields"
                        else "$display takes field names",
                        annotation.span,
                        help = example,
                    )
                continue
            }
            val list = names.map { it!!.name }
            var ok = true
            names.filterNotNull().forEach { name ->
                if (name.name !in declared) {
                    diagnostics +=
                        error(
                            CoreCodes.ANNOTATION_VALUE,
                            "$display names '${name.name}', which is not a field of record '${record.name}'",
                            name.span,
                            help = "name a declared field",
                        )
                    ok = false
                }
            }
            list
                .groupingBy { it }
                .eachCount()
                .filterValues { it > 1 }
                .keys
                .forEach {
                    diagnostics +=
                        error(
                            CoreCodes.ANNOTATION_VALUE,
                            "$display names '$it' more than once",
                            annotation.span,
                            help = "list each field once",
                        )
                    ok = false
                }
            if (!ok) continue
            when (annotation.name) {
                "id" ->
                    if (keyed) {
                        diagnostics +=
                            error(
                                CoreCodes.DUPLICATE_ANNOTATION,
                                "@@id is given more than once",
                                annotation.span,
                                help = "keep one of them",
                            )
                    } else {
                        key = list
                        keyed = true
                    }
                "unique" -> uniques += list
                "index" -> indexes += list
            }
        }
        return ModelLists(key, uniques, indexes)
    }

    private fun analyzeEnum(
        decl: EnumDecl,
        qualifiedName: QualifiedName,
        annotations: AnnotationChecker,
        options: AnalysisOptions,
        diagnostics: MutableList<Diagnostic>,
    ): EnumType {
        val enumAnnotations = annotations.check(decl.annotations, Element.ENUM)
        if (decl.values.isEmpty())
            diagnostics +=
                error(
                    CoreCodes.EMPTY_ENUM,
                    "enum '${decl.name}' has no values",
                    decl.nameSpan,
                    help = "declare at least one value",
                )
        val reserved = Ordinals.reserved(decl.reserved, CoreCodes.ENUM_VALUE_NAMING, diagnostics)
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
                if (value.name == NULL_NAME) {
                    diagnostics +=
                        error(
                            CoreCodes.ENUM_VALUE_NAMING,
                            "enum value '${value.name}' is reserved",
                            value.nameSpan,
                            help = "rename it `${Suggest.lowerSnake(value.name)}`",
                        )
                } else if (!lowerSnake.matches(value.name)) {
                    val suggestion = Suggest.example(value.name, Suggest.lowerSnake(value.name))
                    diagnostics +=
                        error(
                            CoreCodes.ENUM_VALUE_NAMING,
                            "enum value '${value.name}' must be lower_snake",
                            value.nameSpan,
                            help =
                                suggestion?.let { "rename it `$it`" } ?: "rename it in lower_snake",
                        )
                }
                if (!seen.add(value.name)) {
                    diagnostics +=
                        error(
                            CoreCodes.DUPLICATE_ENUM_VALUE,
                            "enum value '${value.name}' is declared more than once in enum '${decl.name}'",
                            value.nameSpan,
                            help = "remove the duplicate value",
                        )
                }
                EnumValue(
                    ordinals[index],
                    value.name,
                    value.doc,
                    value.span,
                    value.nameSpan,
                    annotations.check(value.annotations, Element.ENUM_VALUE),
                )
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
            enumAnnotations,
        )
    }

    private fun analyzeUnion(
        decl: UnionDecl,
        qualifiedName: QualifiedName,
        scope: Scope,
        declarations: DeclarationIndex,
        resolver: Resolver,
        annotations: AnnotationChecker,
        options: AnalysisOptions,
        diagnostics: MutableList<Diagnostic>,
    ): UnionType {
        val unionAnnotations = annotations.check(decl.annotations, Element.UNION)
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
                val written = resolver.resolve(member.type, scope) ?: return@mapIndexedNotNull null
                val resolved = memberOptions(member, written, declarations, diagnostics)
                val type = resolved.type
                val ok =
                    when {
                        resolved.nullable || type is ListOf || type is MapOf -> {
                            diagnostics +=
                                error(
                                    CoreCodes.UNION_MEMBER_KIND,
                                    "union member ${member.type.text()} must be a named type or a scalar",
                                    member.type.span,
                                    help =
                                        "wrap the collection in a record, or drop the `?`; a union is absent through the field, not the member",
                                )
                            false
                        }
                        type is Ref && type.target == qualifiedName -> {
                            diagnostics +=
                                error(
                                    CoreCodes.UNION_SELF_MEMBER,
                                    "union '${decl.name}' may not contain itself",
                                    member.type.nameSpan,
                                    help =
                                        "remove `${decl.name}` from its own members; wrap it in a record if the recursion is intended",
                                )
                            false
                        }
                        !seen.add(type) -> {
                            diagnostics +=
                                error(
                                    CoreCodes.DUPLICATE_UNION_MEMBER,
                                    "union member '${member.type.name}' is repeated",
                                    member.type.nameSpan,
                                    help = "remove the duplicate member",
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
            unionAnnotations,
        )
    }

    /**
     * A union member's options bound its type as a field's options bound the field's, by the same
     * table; the field flags among them (`id`, `unique`, `index`, `embed`) speak of a field's
     * column or reference, which a member does not have, and are reported.
     */
    private fun memberOptions(
        member: UnionMemberDecl,
        resolved: Resolved,
        declarations: DeclarationIndex,
        diagnostics: MutableList<Diagnostic>,
    ): Resolved {
        if (member.options.isEmpty()) return resolved
        val (flags, bounds) = member.options.partition { it.name in Options.FIELD_FLAGS }
        flags.forEach {
            diagnostics +=
                error(
                    CoreCodes.OPTION_NOT_APPLICABLE,
                    "option '${it.name}' belongs to a field, not to a union member",
                    it.span,
                    help = "move it to the options of the field that uses the union",
                )
        }
        return Options.refine(bounds, member.type, resolved, { declarations.find(it)?.decl }) {
                diagnostics += it
            }
            .first
    }

    private fun error(code: DiagnosticCode, message: String, span: Span, help: String? = null) =
        Diagnostic(code, message, span, help)
}
