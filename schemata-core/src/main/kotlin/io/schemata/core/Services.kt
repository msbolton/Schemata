package io.schemata.core

import io.schemata.core.annotations.Element
import io.schemata.core.ir.EnumType
import io.schemata.core.ir.HttpBinding
import io.schemata.core.ir.Operation
import io.schemata.core.ir.Payload
import io.schemata.core.ir.QualifiedName
import io.schemata.core.ir.RecordType
import io.schemata.core.ir.Ref
import io.schemata.core.ir.Scalar
import io.schemata.core.ir.Service
import io.schemata.core.ir.TypeDecl
import io.schemata.core.ir.Verb
import io.schemata.lang.Diagnostic
import io.schemata.lang.DiagnosticCode
import io.schemata.lang.SchemataText
import io.schemata.lang.Span
import io.schemata.lang.ast.BindingDecl
import io.schemata.lang.ast.OperationDecl
import io.schemata.lang.ast.PayloadDecl
import io.schemata.lang.ast.RecordDecl
import io.schemata.lang.ast.ServiceDecl
import io.schemata.lang.ast.UnionDecl

/**
 * Lowers a `service` block. A service is named like a type and its operations like fields, with the
 * same ordinal and reserved rules as a record's fields. A payload must resolve to a record or a
 * union, never nullable. A binding's `{name}` segments must each name a non-nullable scalar or enum
 * field of the request record, once; a streamed request has no path parameters and no
 * parameter-only verb; and one verb and path belong to one operation per namespace, paths that
 * differ only in their parameters' names counting as one.
 */
internal object Services {
    // reserved as an operation name, as it is for fields
    private const val NULL_NAME = "null"

    /**
     * Routes already taken in the namespace: verb and path template to the service and operation
     * that took them.
     */
    class Routes {
        internal val bound = mutableMapOf<Pair<Verb, String>, Pair<String, String>>()
    }

    /**
     * [declarations] holds every lowered declaration of the compilation by qualified name, so a
     * path parameter's field is judged by its resolved type.
     */
    fun analyze(
        decl: ServiceDecl,
        scope: Scope,
        index: DeclarationIndex,
        resolver: Resolver,
        annotations: AnnotationChecker,
        options: AnalysisOptions,
        declarations: Map<QualifiedName, TypeDecl>,
        routes: Routes,
        diagnostics: MutableList<Diagnostic>,
    ): Service {
        if (!Analyzer.upperCamel.matches(decl.name)) {
            val suggestion = Suggest.example(decl.name, Suggest.upperCamel(decl.name))
            diagnostics +=
                error(
                    CoreCodes.TYPE_NAMING,
                    "service name '${decl.name}' must be UpperCamel",
                    decl.nameSpan,
                    help = suggestion?.let { "rename it `$it`" } ?: "rename it in UpperCamel",
                )
        }
        val serviceAnnotations = annotations.check(decl.annotations, Element.SERVICE)
        val reserved = Ordinals.reserved(decl.reserved, CoreCodes.FIELD_NAMING, diagnostics)
        val ordinals =
            Ordinals.assign(
                "service",
                "operation",
                decl.name,
                decl.nameSpan,
                decl.operations.map {
                    Ordinals.Element(it.ordinal, it.ordinalSpan, it.name, it.nameSpan)
                },
                reserved,
                options,
                diagnostics,
            )
        val seen = mutableSetOf<String>()
        val operations =
            decl.operations.mapIndexed { i, op ->
                checkName(op, decl, seen, diagnostics)
                val request =
                    payload(op, op.request, "request", scope, index, resolver, diagnostics)
                val response =
                    payload(op, op.response, "response", scope, index, resolver, diagnostics)
                val binding =
                    op.binding?.let {
                        binding(decl, op, it, request, index, declarations, routes, diagnostics)
                    }
                Operation(
                    ordinal = ordinals[i],
                    name = op.name,
                    request = request,
                    response = response,
                    binding = binding,
                    doc = op.doc,
                    span = op.span,
                    nameSpan = op.nameSpan,
                    annotations = annotations.check(op.annotations, Element.OPERATION),
                )
            }
        return Service(
            qualifiedName = QualifiedName(scope.namespace, listOf(decl.name)),
            name = decl.name,
            operations = operations,
            reserved = reserved,
            doc = decl.doc,
            span = decl.span,
            nameSpan = decl.nameSpan,
            annotations = serviceAnnotations,
        )
    }

    private fun checkName(
        op: OperationDecl,
        service: ServiceDecl,
        seen: MutableSet<String>,
        diagnostics: MutableList<Diagnostic>,
    ) {
        if (op.name == NULL_NAME) {
            diagnostics +=
                error(
                    CoreCodes.FIELD_NAMING,
                    "operation name '${op.name}' is reserved",
                    op.nameSpan,
                    help = "rename it `${Suggest.lowerSnake(op.name)}`",
                )
        } else if (!Analyzer.lowerSnake.matches(op.name)) {
            val suggestion = Suggest.example(op.name, Suggest.lowerSnake(op.name))
            diagnostics +=
                error(
                    CoreCodes.FIELD_NAMING,
                    "operation name '${op.name}' must be lower_snake",
                    op.nameSpan,
                    help = suggestion?.let { "rename it `$it`" } ?: "rename it in lower_snake",
                )
        }
        if (!seen.add(op.name)) {
            diagnostics +=
                error(
                    CoreCodes.DUPLICATE_FIELD,
                    "operation '${op.name}' is declared more than once in service '${service.name}'",
                    op.nameSpan,
                    help = "rename or remove one of the two operations",
                )
        }
    }

    /**
     * Null when [p] is absent or does not resolve to a record or a union; an alias is followed to
     * what it names, so an alias of a record is a record payload and an alias of a scalar is not.
     */
    private fun payload(
        op: OperationDecl,
        p: PayloadDecl?,
        role: String,
        scope: Scope,
        index: DeclarationIndex,
        resolver: Resolver,
        diagnostics: MutableList<Diagnostic>,
    ): Payload? {
        if (p == null) return null
        val resolved = resolver.resolve(p.type, scope) ?: return null
        val ref = resolved.type as? Ref
        val target = ref?.let { index.find(it.target)?.decl }
        if (ref == null || resolved.nullable || (target !is RecordDecl && target !is UnionDecl)) {
            diagnostics +=
                error(
                    CoreCodes.PAYLOAD_KIND,
                    "operation '${op.name}': $role '${p.type.text()}' is not a record or a union",
                    p.type.span,
                    help = "wrap it in a record",
                )
            return null
        }
        return Payload(ref.target, p.stream)
    }

    /** Null when the binding breaks a rule; every broken rule is reported. */
    private fun binding(
        service: ServiceDecl,
        op: OperationDecl,
        b: BindingDecl,
        request: Payload?,
        index: DeclarationIndex,
        declarations: Map<QualifiedName, TypeDecl>,
        routes: Routes,
        diagnostics: MutableList<Diagnostic>,
    ): HttpBinding? {
        // an unknown verb was reported by the parser
        val verb = Verb.entries.firstOrNull { it.lower == b.verb } ?: return null
        var ok = true
        fun bad(message: String, span: Span, help: String) {
            ok = false
            diagnostics += error(CoreCodes.BINDING, "operation '${op.name}': $message", span, help)
        }
        val streamed = request?.stream == true || (request == null && op.request?.stream == true)
        if (streamed && verb.parameterised) {
            bad(
                "a streamed request cannot use ${verb.lower}",
                b.verbSpan,
                "use post, put, or patch",
            )
        }
        if (streamed && b.parameters.isNotEmpty()) {
            bad(
                "a streamed request cannot bind path parameters",
                b.pathSpan,
                "bind nothing in the path, or do not stream the request",
            )
        }
        val target = request?.let { index.find(it.target)?.decl }
        val lowered = request?.let { declarations[it.target] as? RecordType }
        val seen = mutableSetOf<String>()
        b.parameters.forEach { name ->
            if (!seen.add(name)) {
                bad("path parameter '$name' appears twice", b.pathSpan, "name each parameter once")
                return@forEach
            }
            when {
                // a request that did not resolve to a record or a union was reported already
                op.request != null && request == null -> Unit
                target is UnionDecl ->
                    bad(
                        "path parameter '$name' needs a request record, not union '${target.name}'",
                        b.pathSpan,
                        "bind nothing in the path, or make the request a record",
                    )
                target is RecordDecl -> {
                    val problem = fieldProblem(name, target, lowered, declarations)
                    if (problem != null) bad(problem.first, b.pathSpan, problem.second)
                }
                else ->
                    bad(
                        "path parameter '$name' needs a request record",
                        b.pathSpan,
                        "add a request record with a field '$name'",
                    )
            }
        }
        // `/orders/{id}` and `/orders/{order_id}` match the same requests, so routes compare by
        // template; the other operation is qualified when another service holds it
        val other =
            routes.bound.putIfAbsent(verb to HttpBinding.template(b.path), service.name to op.name)
        if (other != null) {
            val shown =
                if (other.first == service.name) other.second else "${other.first}.${other.second}"
            bad(
                "${verb.lower} ${SchemataText.string(b.path)} is already bound by operation '$shown'",
                b.span,
                "give each operation its own verb and path",
            )
        }
        return if (ok) HttpBinding(verb, b.path, b.parameters) else null
    }

    /**
     * Message and help when [name] cannot be bound from [record]; null when it can. [lowered] is
     * the record after lowering, where a field whose type failed to resolve is missing: that
     * failure was reported, so the field is passed over. Nullability and kind are the resolved
     * type's, so an alias counts as what it names.
     */
    private fun fieldProblem(
        name: String,
        record: RecordDecl,
        lowered: RecordType?,
        declarations: Map<QualifiedName, TypeDecl>,
    ): Pair<String, String>? {
        if (record.fields.none { it.name == name }) {
            return "path parameter '$name' is not a field of '${record.name}'" to
                "name a scalar or enum field of the request record"
        }
        val field = lowered?.fields?.firstOrNull { it.name == name } ?: return null
        if (field.nullable) {
            return "path parameter '$name' is nullable" to
                "a path segment is always present; make the field non-nullable"
        }
        val type = field.type
        val scalarOrEnum = type is Scalar || (type is Ref && declarations[type.target] is EnumType)
        if (!scalarOrEnum) {
            return "path parameter '$name' is not a scalar or enum field" to
                "bind a scalar or enum field"
        }
        return null
    }

    private fun error(code: DiagnosticCode, message: String, span: Span, help: String) =
        Diagnostic(code, message, span, help)
}
