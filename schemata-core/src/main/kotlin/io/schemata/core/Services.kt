package io.schemata.core

import io.schemata.core.annotations.Element
import io.schemata.core.ir.EnumType
import io.schemata.core.ir.ErrorResponse
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
import io.schemata.lang.ast.ErrorsDecl
import io.schemata.lang.ast.OperationDecl
import io.schemata.lang.ast.PayloadDecl
import io.schemata.lang.ast.RecordDecl
import io.schemata.lang.ast.ServiceDecl
import io.schemata.lang.ast.TypeExpr
import io.schemata.lang.ast.UnionDecl

/**
 * Lowers a `service` block. A service is named like a type and its operations like fields, with the
 * same ordinal and reserved rules as a record's fields. A payload must resolve to a record or a
 * union, never nullable. A binding's `{name}` segments must each name a non-nullable scalar or enum
 * field of the request record, once; a streamed request has no path parameters and no
 * parameter-only verb; and one verb and path belong to one operation per namespace, paths that
 * differ only in their parameters' names counting as one. A success code is 200, 201, 202 or 204,
 * and 204 only for an operation without a response. An `errors` clause lists codes from 400 to 599
 * or `default`, each once, each body a record or a union as a payload is; a service has at most one
 * clause of its own.
 */
internal object Services {
    // reserved as an operation name, as it is for fields
    private const val NULL_NAME = "null"

    private val SUCCESS_CODES = setOf(200, 201, 202, 204)
    private val ERROR_CODES = 400..599

    /**
     * Routes already taken in the namespace: verb and path template to the service and operation
     * that took them.
     */
    class Routes {
        internal val bound = mutableMapOf<Pair<Verb, String>, Pair<String, String>>()
    }

    /**
     * What every service of a compilation is judged against. [declarations] holds every lowered
     * declaration by qualified name, so a path parameter's field is judged by its resolved type.
     */
    data class Context(
        val index: DeclarationIndex,
        val resolver: Resolver,
        val annotations: AnnotationChecker,
        val options: AnalysisOptions,
        val declarations: Map<QualifiedName, TypeDecl>,
        val diagnostics: MutableList<Diagnostic>,
    )

    fun analyze(decl: ServiceDecl, scope: Scope, context: Context, routes: Routes): Service {
        val (index, resolver, annotations, options, declarations, diagnostics) = context
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
        val serviceErrors = serviceErrors(decl, scope, index, resolver, diagnostics)
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
                val status = status(op, diagnostics)
                val errors =
                    op.errors
                        ?.let {
                            clause(
                                it,
                                "operation '${op.name}'",
                                scope,
                                index,
                                resolver,
                                diagnostics,
                            )
                        }
                        .orEmpty()
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
                    status = status,
                    errors = errors,
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
            errors = serviceErrors,
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
                    "operation '${op.name}': $role '${p.type.text()}' is not a model or a union",
                    p.type.span,
                    help = "wrap it in a model",
                )
            return null
        }
        return Payload(ref.target, p.stream)
    }

    /**
     * The written success code when it is allowed; null for none or a broken one, which is
     * reported. A code is only ever written in a binding, so an operation without one has none.
     */
    private fun status(op: OperationDecl, diagnostics: MutableList<Diagnostic>): Int? {
        val b = op.binding ?: return null
        val code = b.status ?: return null
        val span = b.statusSpan ?: return null
        if (code !in SUCCESS_CODES) {
            diagnostics +=
                error(
                    CoreCodes.RESPONSES,
                    "operation '${op.name}': success code $code is not 200, 201, 202 or 204",
                    span,
                    help = "use 200, 201, 202 or 204, or leave the code out",
                )
            return null
        }
        val response = op.response
        if (code == 204 && response != null) {
            diagnostics +=
                error(
                    CoreCodes.RESPONSES,
                    "operation '${op.name}': success code 204 cannot carry the response " +
                        "'${response.type.text()}'",
                    span,
                    help = "use 200 for a response, or remove the response",
                )
            return null
        }
        return code
    }

    /**
     * The service's own clause. A second clause is reported at its `errors` word and left out of
     * the IR, but its bodies are still resolved, so their imports count as used and the language
     * server still finds them.
     */
    private fun serviceErrors(
        decl: ServiceDecl,
        scope: Scope,
        index: DeclarationIndex,
        resolver: Resolver,
        diagnostics: MutableList<Diagnostic>,
    ): List<ErrorResponse> {
        val where = "service '${decl.name}'"
        val lowered =
            decl.errors.mapIndexed { i, written ->
                if (i > 0) {
                    val s = written.span
                    diagnostics +=
                        error(
                            CoreCodes.RESPONSES,
                            "$where: more than one errors clause",
                            Span(
                                s.file,
                                s.startLine,
                                s.startColumn,
                                s.startLine,
                                s.startColumn + 5,
                            ),
                            help = "list every code of the service in one errors clause",
                        )
                }
                clause(written, where, scope, index, resolver, diagnostics)
            }
        return lowered.firstOrNull().orEmpty()
    }

    /**
     * The entries of [clause] that keep every rule: a code from 400 to 599 or `default`, listed
     * once, and a body that resolves to a record or a union. Each broken rule is reported at the
     * code or the body, and that entry is left out. [where] names the operation or the service.
     */
    private fun clause(
        clause: ErrorsDecl,
        where: String,
        scope: Scope,
        index: DeclarationIndex,
        resolver: Resolver,
        diagnostics: MutableList<Diagnostic>,
    ): List<ErrorResponse> {
        val seen = mutableSetOf<Int?>()
        val out = mutableListOf<ErrorResponse>()
        for (r in clause.responses) {
            val code = r.code
            val shown = code?.toString() ?: "default"
            var ok = true
            if (code != null && code !in ERROR_CODES) {
                diagnostics +=
                    error(
                        CoreCodes.RESPONSES,
                        "$where: error code $shown is outside 400 to 599",
                        r.codeSpan,
                        help = "use a code from 400 to 599, or default",
                    )
                ok = false
            } else if (!seen.add(code)) {
                diagnostics +=
                    error(
                        CoreCodes.RESPONSES,
                        "$where: error code $shown is listed twice",
                        r.codeSpan,
                        help = "list each code once in a clause",
                    )
                ok = false
            }
            val written = r.body
            val body = written?.let { body(it, where, scope, index, resolver, diagnostics) }
            if (written != null && body == null) ok = false
            if (ok) out += ErrorResponse(code, body)
        }
        return out
    }

    /**
     * What an error body names, resolved as a payload is, aliases followed; null when it does not
     * resolve or is not a non-nullable record or union, which is reported.
     */
    private fun body(
        type: TypeExpr,
        where: String,
        scope: Scope,
        index: DeclarationIndex,
        resolver: Resolver,
        diagnostics: MutableList<Diagnostic>,
    ): QualifiedName? {
        val resolved = resolver.resolve(type, scope) ?: return null
        val ref = resolved.type as? Ref
        val target = ref?.let { index.find(it.target)?.decl }
        if (ref == null || resolved.nullable || (target !is RecordDecl && target !is UnionDecl)) {
            diagnostics +=
                error(
                    CoreCodes.RESPONSES,
                    "$where: error body '${type.text()}' is not a model or a union",
                    type.span,
                    help = "wrap it in a model",
                )
            return null
        }
        return ref.target
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
        // a payload that did not resolve is still streamed when it was written so
        val streamed = op.request?.stream == true
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
                        "path parameter '$name' needs a request model, not union '${target.name}'",
                        b.pathSpan,
                        "bind nothing in the path, or make the request a model",
                    )
                target is RecordDecl -> {
                    val problem = fieldProblem(name, target, lowered, declarations)
                    if (problem != null) bad(problem.first, b.pathSpan, problem.second)
                }
                else ->
                    bad(
                        "path parameter '$name' needs a request model",
                        b.pathSpan,
                        "add a request model with a field '$name'",
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
                "name a scalar or enum field of the request model"
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
