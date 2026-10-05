package io.schemata.target.openapi

import io.schemata.core.ir.EnumType
import io.schemata.core.ir.Field
import io.schemata.core.ir.HttpBinding
import io.schemata.core.ir.ListOf
import io.schemata.core.ir.MapOf
import io.schemata.core.ir.Namespace
import io.schemata.core.ir.Operation
import io.schemata.core.ir.QualifiedName
import io.schemata.core.ir.RecordType
import io.schemata.core.ir.Ref
import io.schemata.core.ir.Scalar
import io.schemata.core.ir.Schema
import io.schemata.core.ir.Service
import io.schemata.core.ir.Type
import io.schemata.core.ir.TypeDecl
import io.schemata.core.ir.UnionType
import io.schemata.core.ir.Verb
import io.schemata.core.ir.kindWord
import io.schemata.core.ir.selfAndNested
import io.schemata.lang.Diagnostic
import io.schemata.target.Lowered
import io.schemata.target.deprecated
import io.schemata.target.jsonschema.DocumentLowering
import io.schemata.target.jsonschema.JsonSchema
import io.schemata.target.jsonschema.JsonSchemaNames
import io.schemata.target.jsonschema.LoweringCodes
import io.schemata.target.jsonschema.RefSchema
import io.schemata.target.jsonschema.SchemaNames
import io.schemata.target.jsonschema.withCommon
import io.schemata.target.string

/**
 * Lowers every namespace that declares a service to one [OpenApiDocument]; every decision and every
 * report lives here. Component schemas come from the JSON Schema lowering, filed under
 * `#/components/schemas/<ns>.<key>` and reported under this target's codes.
 */
object OpenApiLowering {
    private val codes =
        LoweringCodes(
            lossy = OpenApiCodes.LOSSY,
            nameCollision = OpenApiCodes.COLLISION,
            invalidOverride = OpenApiCodes.INVALID_OVERRIDE,
            idCollision = OpenApiCodes.COLLISION,
        )

    fun lower(schema: Schema): Lowered<OpenApiModel> {
        val diagnostics = mutableListOf<Diagnostic>()
        val names = SchemaNames(schema, codes, diagnostics)
        val documents =
            schema.namespaces
                .filter { it.services.isNotEmpty() }
                .map { NamespaceLowering(schema, names, codes, it, diagnostics).lower() }
        // a declaration filed in several documents reports its problems once
        return Lowered(OpenApiModel(documents), diagnostics.distinct())
    }
}

/** One namespace's services as one document. */
private class NamespaceLowering(
    private val schema: Schema,
    private val names: SchemaNames,
    codes: LoweringCodes,
    private val namespace: Namespace,
    private val diagnostics: MutableList<Diagnostic>,
) {
    private fun keyOf(qn: QualifiedName): String = "${qn.namespace}.${names.defsKey(qn)}"

    private fun refOf(qn: QualifiedName): String = "#/components/schemas/${keyOf(qn)}"

    private val document =
        DocumentLowering(
            schema,
            names,
            namespace,
            codes,
            diagnostics,
            refs = ::refOf,
            keys = ::keyOf,
        )

    /** The declarations the operations use directly, in first-use order. */
    private val needed = LinkedHashSet<QualifiedName>()
    private val operationIds = mutableMapOf<String, Pair<Service, Operation>>()
    private val routes = mutableMapOf<Pair<Verb, String>, Pair<Service, Operation>>()
    /** Each path template to the first path that lowered to it and that path's operation. */
    private val templates = mutableMapOf<String, Pair<String, Pair<Service, Operation>>>()

    fun lower(): OpenApiDocument {
        // every operation is planned first, so the components are filed before any operation
        // lowers a field schema or a partial body: a name collision is then blamed as the JSON
        // Schema target blames it
        val plans = namespace.services.flatMap { s -> s.operations.mapNotNull { plan(s, it) } }
        val closure = closure(needed)
        val components = document.lower(closure)
        checkKeys(closure)
        val paths = LinkedHashMap<String, MutableList<OpenApiOperation>>()
        plans.forEach { paths.getOrPut(it.path) { mutableListOf() } += operation(it) }
        return OpenApiDocument(
            path = JsonSchemaNames.pathOf(namespace).removeSuffix(".schema.json") + ".openapi.json",
            title = namespace.name,
            version = namespace.annotations.string("openapi", "version") ?: "1.0.0",
            description = namespace.doc,
            server = server(),
            tags = tags(),
            paths = paths.map { (path, ops) -> PathItem(path, ops.sortedBy { it.verb.ordinal }) },
            components = components,
        )
    }

    /** What an operation becomes, decided before any schema is lowered. */
    private class Plan(
        val service: Service,
        val operation: Operation,
        val operationId: String,
        val verb: Verb,
        val path: String,
        val record: RecordType?,
        val pathFields: List<Field>,
        val queryFields: List<Field>,
        val body: BodyPlan?,
    )

    private sealed interface BodyPlan {
        /** The whole payload as its component, in [mediaType]. */
        data class Whole(val mediaType: String, val target: QualifiedName) : BodyPlan

        /** The request record's fields the path did not take, as an inline object. */
        data class Partial(val fields: List<Field>) : BodyPlan
    }

    /**
     * Names, route, parameters, and body of [op], with what they reference added to [needed]; null
     * when its route repeats another operation's.
     */
    private fun plan(service: Service, op: Operation): Plan? {
        val operationId = operationId(service, op)
        val verb = op.binding?.verb ?: Verb.POST
        val path = op.binding?.path ?: "/${tagName(service)}/${op.name}"
        val routeTaken = claimRoute(service, op, verb, path)
        val request = op.request
        val decl = request?.let { schema.lookup(it.target) }
        val record = decl as? RecordType
        val pathFields =
            if (request == null || request.stream || record == null) emptyList()
            else
                op.binding?.parameters.orEmpty().map { name ->
                    record.fields.first { it.name == name }
                }
        val remaining =
            if (request == null || request.stream) emptyList()
            else record?.fields.orEmpty().filter { it !in pathFields }
        val queryFields =
            if (verb.parameterised) remaining.filter { queryable(op, it) } else emptyList()
        val body: BodyPlan? =
            when {
                request == null -> null
                request.stream -> BodyPlan.Whole("application/x-ndjson", request.target)
                verb.parameterised -> {
                    if (record == null) unionAsQuery(op, decl!!)
                    null
                }
                record == null -> BodyPlan.Whole("application/json", request.target)
                pathFields.isEmpty() -> BodyPlan.Whole("application/json", request.target)
                remaining.isEmpty() -> null
                else -> BodyPlan.Partial(remaining)
            }
        (pathFields + queryFields).forEach { needed += refsIn(it.type) }
        when (body) {
            is BodyPlan.Whole -> needed += body.target
            is BodyPlan.Partial -> body.fields.forEach { needed += refsIn(it.type) }
            null -> {}
        }
        op.response?.let { needed += it.target }
        if (routeTaken) return null
        return Plan(service, op, operationId, verb, path, record, pathFields, queryFields, body)
    }

    /**
     * `@openapi(name)` when valid, else `<Service>_<operation>` with the service's tag name; a
     * second operation with the same id is reported.
     */
    private fun operationId(service: Service, op: Operation): String {
        val override = op.annotations.string("openapi", "name")
        val valid =
            override?.takeIf { OPERATION_ID.matches(it) }
                ?: run {
                    if (override != null) {
                        diagnostics +=
                            Diagnostic(
                                OpenApiCodes.INVALID_OVERRIDE,
                                "operation '${op.name}': @openapi(name = \"$override\") is not a valid operationId",
                                op.nameSpan,
                                help = "use letters, digits, `_`, `.`, and `-`",
                            )
                    }
                    null
                }
        val id = valid ?: "${tagName(service)}_${op.name}"
        val previous = operationIds.putIfAbsent(id, service to op)
        if (previous != null) {
            diagnostics +=
                Diagnostic(
                    OpenApiCodes.COLLISION,
                    "operations ${both(previous, service to op)} both lower to operationId '$id'",
                    op.nameSpan,
                    help = "rename one, or set `@openapi(name = \"…\")` on one",
                )
        }
        return id
    }

    /**
     * Claims [verb] on [path] for [op]; true, after reporting, when another operation has it. Paths
     * that differ only in their parameters' names are one path to OpenAPI, which allows only one of
     * them whatever their verbs, so the first such path keeps its template.
     */
    private fun claimRoute(service: Service, op: Operation, verb: Verb, path: String): Boolean {
        val template = HttpBinding.template(path)
        val first = templates.putIfAbsent(template, path to (service to op))
        if (first != null && first.first != path) {
            diagnostics +=
                Diagnostic(
                    OpenApiCodes.COLLISION,
                    "operations ${both(first.second, service to op)} both lower to path \"$template\" with different parameter names",
                    op.nameSpan,
                    help = "use the same parameter names in both paths",
                )
            return true
        }
        val previous = routes.putIfAbsent(verb to path, service to op) ?: return false
        diagnostics +=
            Diagnostic(
                OpenApiCodes.COLLISION,
                "operations ${both(previous, service to op)} both lower to ${verb.lower} \"$path\"",
                op.nameSpan,
                help = "bind one of them to another path or verb",
            )
        return true
    }

    /** `'a' and 'b'` within one service, `'S.a' and 'T.b'` across two. */
    private fun both(first: Pair<Service, Operation>, second: Pair<Service, Operation>): String {
        val qualify = first.first.qualifiedName != second.first.qualifiedName
        fun shown(p: Pair<Service, Operation>) =
            if (qualify) "'${p.first.name}.${p.second.name}'" else "'${p.second.name}'"
        return "${shown(first)} and ${shown(second)}"
    }

    /**
     * Whether [field] can be a query parameter: a scalar, an enum, or a list of those; anything
     * else is reported.
     */
    private fun queryable(op: Operation, field: Field): Boolean {
        if (flat(field.type)) return true
        diagnostics +=
            Diagnostic(
                OpenApiCodes.QUERY_SHAPE,
                "operation '${op.name}': field '${field.name}' cannot be a query parameter",
                op.nameSpan,
                help = "use post, or bind it in the path, or flatten it",
            )
        return false
    }

    /** A union request has no fields to spread over query parameters; it is reported. */
    private fun unionAsQuery(op: Operation, union: TypeDecl) {
        diagnostics +=
            Diagnostic(
                OpenApiCodes.QUERY_SHAPE,
                "operation '${op.name}': union '${union.name}' cannot be query parameters; use a body verb",
                op.nameSpan,
                help = "request a record, or use post, put, or patch",
            )
    }

    private fun flat(type: Type): Boolean =
        when (type) {
            is Scalar -> true
            is Ref -> schema.lookup(type.target) is EnumType
            is ListOf -> type.element !is ListOf && flat(type.element)
            is MapOf -> false
        }

    /** Every declaration [type] names, through list elements and map values. */
    private fun refsIn(type: Type): List<QualifiedName> =
        when (type) {
            is Scalar -> emptyList()
            is Ref -> listOf(type.target)
            is ListOf -> refsIn(type.element)
            is MapOf -> refsIn(type.key) + refsIn(type.value)
        }

    /**
     * [roots] and everything they reach, depth first in field order: what a declaration references,
     * then its nested declarations, which its component carries with it.
     */
    private fun closure(roots: Collection<QualifiedName>): List<TypeDecl> {
        val seen = LinkedHashSet<QualifiedName>()
        fun visit(qn: QualifiedName) {
            if (!seen.add(qn)) return
            val decl = schema.lookup(qn)
            val types =
                when (decl) {
                    is RecordType -> decl.fields.map { it.type }
                    is UnionType -> decl.members.map { it.type }
                    is EnumType -> emptyList()
                }
            types.flatMap(::refsIn).forEach(::visit)
            decl.nested.forEach { visit(it.qualifiedName) }
        }
        roots.forEach(::visit)
        return seen.map(schema::lookup)
    }

    /** Every component key, nested declarations included, must be one OpenAPI accepts. */
    private fun checkKeys(closure: List<TypeDecl>) {
        closure
            .flatMap { it.selfAndNested() }
            .distinctBy { it.qualifiedName }
            .forEach { decl ->
                val key = keyOf(decl.qualifiedName)
                if (!COMPONENT_KEY.matches(key)) {
                    diagnostics +=
                        Diagnostic(
                            OpenApiCodes.INVALID_OVERRIDE,
                            "${decl.kindWord} '${decl.name}': component key '$key' is not a valid component key",
                            decl.nameSpan,
                            help =
                                "use letters, digits, `_`, `.`, and `-` in `@jsonschema(name = \"…\")`",
                        )
                }
            }
    }

    private fun operation(plan: Plan): OpenApiOperation {
        val op = plan.operation
        val (summary, description) = paragraphs(op.doc)
        val parameters =
            plan.pathFields.map { parameter(plan, it, "path") } +
                plan.queryFields.map { parameter(plan, it, "query") }
        val requestBody =
            when (val body = plan.body) {
                null -> null
                is BodyPlan.Whole -> Body(body.mediaType, RefSchema(refOf(body.target)))
                is BodyPlan.Partial ->
                    Body(
                        "application/json",
                        document.partialRecord(plan.record!!, body.fields, "operation '${op.name}'"),
                    )
            }
        val response =
            op.response?.let {
                val name = it.target.simpleName
                if (it.stream)
                    Response.Content(
                        200,
                        "Stream of $name",
                        "text/event-stream",
                        RefSchema(refOf(it.target)),
                    )
                else Response.Content(200, name, "application/json", RefSchema(refOf(it.target)))
            } ?: Response.Empty("No content")
        return OpenApiOperation(
            verb = plan.verb,
            operationId = plan.operationId,
            tag = tagName(plan.service),
            summary = summary,
            description = description,
            deprecated = op.annotations.deprecated || plan.service.annotations.deprecated,
            parameters = parameters,
            requestBody = requestBody,
            response = response,
        )
    }

    /**
     * [field] as a parameter named by its Schemata name; its doc and deprecation go on the
     * parameter, its type, refinements, nullability, and default stay in the schema.
     */
    private fun parameter(plan: Plan, field: Field, location: String): Parameter {
        if (field.annotations.string("jsonschema", "name") != null) {
            diagnostics +=
                Diagnostic(
                    OpenApiCodes.LOSSY,
                    "operation '${plan.operation.name}': @jsonschema(name) on field '${field.name}' does not rename the parameter",
                    field.nameSpan,
                    help = "a parameter is named by the field; rename the field to rename it",
                )
        }
        val schema: JsonSchema = document.fieldSchema(plan.record!!, field)
        val query = location == "query"
        return Parameter(
            name = field.name,
            location = location,
            required = !query || (!field.nullable && field.default == null),
            schema = withCommon(schema, schema.common.copy(description = null, deprecated = false)),
            description = field.doc,
            deprecated = field.annotations.deprecated,
            style = query,
        )
    }

    /** A doc's first paragraph as the summary and the rest as the description. */
    private fun paragraphs(doc: String?): Pair<String?, String?> {
        if (doc == null) return null to null
        val parts = doc.split(BLANK_LINE, limit = 2)
        return parts[0].trim().ifEmpty { null } to parts.getOrNull(1)?.trim()?.ifEmpty { null }
    }

    /**
     * One tag per service, named by [tagName]; a service whose tag name another already has is
     * reported, and its operations share that tag.
     */
    private fun tags(): List<Tag> {
        val owners = mutableMapOf<String, Service>()
        return namespace.services.mapNotNull { service ->
            val name = tagName(service)
            val previous = owners.putIfAbsent(name, service)
            if (previous == null) Tag(name, tagDescription(service))
            else {
                diagnostics +=
                    Diagnostic(
                        OpenApiCodes.COLLISION,
                        "services '${previous.name}' and '${service.name}' both lower to tag '$name'",
                        service.nameSpan,
                        help = "set a different `@openapi(name = \"…\")` on one of them",
                    )
                null
            }
        }
    }

    private val tagNames = mutableMapOf<QualifiedName, String>()

    /**
     * The service's emitted name: its `@openapi(name)` when valid, else its own name; an invalid
     * override is reported once.
     */
    private fun tagName(service: Service): String =
        tagNames.getOrPut(service.qualifiedName) {
            val override = service.annotations.string("openapi", "name")
            when {
                override == null -> service.name
                OPERATION_ID.matches(override) -> override
                else -> {
                    diagnostics +=
                        Diagnostic(
                            OpenApiCodes.INVALID_OVERRIDE,
                            "service '${service.name}': @openapi(name = \"$override\") is not a valid tag",
                            service.nameSpan,
                            help = "use letters, digits, `_`, `.`, and `-`",
                        )
                    service.name
                }
            }
        }

    private fun tagDescription(service: Service): String? {
        if (!service.annotations.deprecated) return service.doc
        return service.doc?.let { "$it\n\nDeprecated." } ?: "Deprecated."
    }

    /** `@openapi(server)` when it is an absolute URL or a path; anything else is reported. */
    private fun server(): String? {
        val server = namespace.annotations.string("openapi", "server") ?: return null
        if (SERVER.matches(server)) return server
        diagnostics +=
            Diagnostic(
                OpenApiCodes.INVALID_OVERRIDE,
                "namespace '${namespace.name}': @openapi(server = \"$server\") is not a valid URL",
                namespace.span,
                help =
                    "use an absolute URL such as `https://api.example.com`, or a path such as `/v1`",
            )
        return null
    }

    private companion object {
        // keep in step with `ChangeContext.OPENAPI_NAME` in schemata-evolution
        val OPERATION_ID = Regex("[A-Za-z0-9_.-]+")
        val COMPONENT_KEY = Regex("[a-zA-Z0-9._-]+")
        val SERVER = Regex("[A-Za-z][A-Za-z0-9+.-]*://\\S+|/\\S*")
        val BLANK_LINE = Regex("\n[ \t]*\n")
    }
}
