package io.schemata.importer.openapi

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.schemata.importer.ImportCodes
import io.schemata.importer.ImportNames
import io.schemata.importer.UnitAnnotation
import io.schemata.importer.UnitDecl
import io.schemata.importer.UnitEnum
import io.schemata.importer.UnitErrorResponse
import io.schemata.importer.UnitField
import io.schemata.importer.UnitOperation
import io.schemata.importer.UnitPayload
import io.schemata.importer.UnitRecord
import io.schemata.importer.UnitService
import io.schemata.importer.UnitType
import io.schemata.importer.UnitUnion
import io.schemata.lang.SchemataText
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.oas.models.headers.Header
import io.swagger.v3.oas.models.media.Content
import io.swagger.v3.oas.models.media.MediaType
import io.swagger.v3.oas.models.parameters.Parameter
import io.swagger.v3.oas.models.responses.ApiResponse
import io.swagger.v3.parser.util.OpenAPIDeserializer

/** A model object where it sits: its document, its pointer, and its tree node. */
internal data class Placed<T>(
    val value: T,
    val doc: OpenApiDoc,
    val at: Pointer,
    val node: JsonNode,
)

/** The document operation behind a lowered one, and where it sits. */
internal data class SourceOperation(val operation: Operation, val at: Pointer)

/** One lowered service and, in step with its operations, the document operation behind each. */
internal data class LoweredService(val service: UnitService, val sources: List<SourceOperation>)

/**
 * [start] itself, or the component its `$ref` chain ends at under `#/components/<section>`. Null
 * after reporting a chain that cannot be resolved, that leaves [section], or that comes back to a
 * component it passed; [what] names the component kind in the message. The component is read from
 * its document's own tree with [lookup], one of swagger-parser's deserializer methods, since a file
 * reached only through a `$ref` has an empty model.
 */
internal fun <T : Any> Refs.follow(
    report: Report,
    start: Placed<T>,
    section: String,
    what: String,
    refOf: (T) -> String?,
    lookup: (OpenAPIDeserializer, ObjectNode, OpenAPIDeserializer.ParseResult) -> T?,
): Placed<T>? {
    var current = start
    val seen = mutableSetOf<String>()
    while (true) {
        val ref = refOf(current.value) ?: return current
        val target = resolve(current.doc, current.at, ref) ?: return null
        val value =
            (target.node as? ObjectNode)
                ?.takeIf { target.name != null && target.section == section }
                ?.let { node ->
                    // what the deserializer says about the node the reader already said for the
                    // document
                    val result =
                        OpenAPIDeserializer.ParseResult()
                            .openapi31(target.doc.version.startsWith("3.1"))
                    lookup(OpenAPIDeserializer(), node, result)
                }
        if (value == null) {
            report.at(
                current.doc,
                current.at,
                ImportCodes.UNRESOLVED,
                "reference '$ref' does not name a $what component",
            )
            return null
        }
        if (!seen.add("${target.doc.path}${target.pointer}")) {
            report.at(
                current.doc,
                current.at,
                ImportCodes.UNRESOLVED,
                "reference '$ref' refers back to itself",
            )
            return null
        }
        current = Placed(value, target.doc, target.pointer, target.node)
    }
}

/**
 * The namespaces other than [namespace] that [services] name in their payloads and error bodies,
 * which the unit must import.
 */
internal fun serviceImports(services: List<UnitService>, namespace: String): List<String> =
    services
        .flatMap { service ->
            service.errors.mapNotNull { it.body } +
                service.operations.flatMap { op ->
                    listOfNotNull(op.request?.type, op.response?.type) +
                        op.errors.mapNotNull { it.body }
                }
        }
        .mapNotNull { ref ->
            ref.name
                .split('.')
                .takeWhile(ImportNames::isNamespaceSegment)
                .takeIf { it.isNotEmpty() }
                ?.joinToString(".")
        }
        .filter { it != namespace }
        .distinct()

/**
 * One document's operations as services, in the order the OpenAPI target writes them back.
 *
 * A service is an operation's first tag, in the order of the document's `tags`, then tags the
 * document does not declare in first-use order, then the untagged operations, under a service named
 * after the schema's last segment. A declared tag with no operation is an empty service, since the
 * target writes a tag for every service. Operations take ordinals in path order and, within a path,
 * the order get, put, post, delete, options, head, patch.
 *
 * A request merges the path parameters (in path order), the query and header parameters, and the
 * body's fields into one model, `<OpId>Request`, the reverse of how the target splits a request
 * model; a body alone is the request. The success response is the lowest of 200, 201, 202 and 204,
 * and its code is written only where the default rule (200 with a response, 204 without) gives
 * another. 4xx, 5xx and `default` responses are `errors` entries, and an entry every operation of a
 * service answers alike moves to the service's clause. A response with headers is a model of its
 * body's fields and then its header fields, named by its description when that is a model name,
 * since the target describes such a response by its model's name.
 */
internal class OperationLowering(
    private val doc: OpenApiDoc,
    private val namespace: String,
    private val refs: Refs,
    private val schemas: SchemaLowering,
    private val report: Report,
) {
    /** One operation as the document lists it. */
    private class Found(
        val path: String,
        val verb: String,
        val item: PathItem,
        val operation: Operation,
        val at: Pointer,
    )

    /** The operations of one tag, or of none, and where the tag is first named. */
    private class Group(val tag: String?, val at: Pointer?) {
        val operations = mutableListOf<Found>()
    }

    /** What a body schema is. */
    private enum class Kind {
        MODEL,
        UNION,
        ENUM,
        OTHER,
    }

    /** A request or response body. */
    private sealed interface Body {
        /** A model or union component, by the name the operation refers to it with. */
        data class Named(val name: String) : Body

        /** A declaration made for the operation, named as it asked, claimed once it is used. */
        data class Declared(val decl: UnitDecl) : Body
    }

    /** The media type a body is read from, and whether it is a stream of messages. */
    private class Picked(val media: Placed<MediaType>, val stream: Boolean)

    /** The namespace's top-level names: declarations, services, and the models made here. */
    private val top = Scope()

    fun lower(): List<LoweredService> {
        schemas.declarations()[namespace].orEmpty().forEach { top.claim(it.name) }
        val groups = groups()
        // every service is named before any operation makes a model, so a model whose name a
        // service has takes a suffix instead
        val names = groups.map(::serviceName)
        names.forEach { schemas.reserve(namespace, it) }
        return groups.zip(names, ::service)
    }

    private fun groups(): List<Group> {
        val declared = LinkedHashMap<String, Group>()
        doc.api.tags.orEmpty().forEachIndexed { i, tag ->
            val name = tag.name ?: return@forEachIndexed
            declared.putIfAbsent(name, Group(name, ROOT / "tags" / i))
        }
        val undeclared = LinkedHashMap<String, Group>()
        val untagged = Group(null, null)
        doc.api.paths.orEmpty().forEach { (path, item) ->
            val at = ROOT / "paths" / path
            if (item.`$ref` != null) {
                dropped(at, "path item reference dropped")
                return@forEach
            }
            if (!item.servers.isNullOrEmpty()) dropped(at / "servers", "servers dropped")
            // the model lists a path's operations in the order get, put, post, delete, options,
            // head, patch, trace, whatever order the document writes them in
            item.readOperationsMap().forEach { (method, operation) ->
                val verb = method.name.lowercase()
                val tag = operation.tags?.firstOrNull()
                val group =
                    when (tag) {
                        null -> untagged
                        else ->
                            declared[tag]
                                ?: undeclared.getOrPut(tag) { Group(tag, at / verb / "tags" / 0) }
                    }
                group.operations += Found(path, verb, item, operation, at / verb)
            }
        }
        return declared.values +
            undeclared.values +
            listOfNotNull(untagged.takeIf { it.operations.isNotEmpty() })
    }

    /** The service name [group] claims at the top of the namespace, reporting a change. */
    private fun serviceName(group: Group): String {
        val tag = group.tag
        val valid = tag != null && OpenApiNames.ID.matches(tag)
        val wanted = ImportNames.upperCamel(tag ?: namespace.substringAfterLast('.'))
        val name = top.claim(wanted)
        when {
            tag != null && !valid -> invalid(doc, group.at, "tag", tag, name)
            name != wanted ->
                taken(
                    doc,
                    group.at,
                    if (tag != null) "tag" else "service",
                    tag ?: wanted,
                    name,
                    wanted,
                )
        }
        return name
    }

    private fun service(group: Group, name: String): LoweredService {
        val tag = group.tag
        val valid = tag != null && OpenApiNames.ID.matches(tag)
        // the target names a tag after its service, unless `@openapi(name)` keeps another spelling
        val compiledTag = if (valid) tag!! else name
        val annotations = if (valid && name != tag) listOf(openapi("name", tag!!)) else emptyList()
        val described = tag?.let { t -> doc.api.tags.orEmpty().firstOrNull { it.name == t } }
        val (serviceDoc, deprecated) = deprecation(described?.description)
        val names = Scope()
        val lowered = group.operations.mapNotNull { operation(it, compiledTag, names, deprecated) }
        val shared = shared(lowered.map { it.first })
        val operations =
            lowered.mapIndexed { i, (op, _) ->
                op.copy(ordinal = i + 1, errors = op.errors - shared.toSet())
            }
        return LoweredService(
            UnitService(
                name = name,
                operations = operations,
                doc = serviceDoc,
                annotations = annotations,
                deprecated = deprecated,
                errors = shared,
            ),
            lowered.map { it.second },
        )
    }

    /**
     * The error entries every operation of a service answers alike, which move to the service's
     * clause; a service of one operation keeps them on it, since a shared clause would say nothing
     * more.
     */
    private fun shared(operations: List<UnitOperation>): List<UnitErrorResponse> {
        if (operations.size < 2) return emptyList()
        return operations.first().errors.filter { error -> operations.all { error in it.errors } }
    }

    /**
     * [text] without the target's deprecation marker, a last paragraph `Deprecated.`, and whether
     * it had one.
     */
    private fun deprecation(text: String?): Pair<String?, Boolean> {
        val trimmed = text?.trimEnd() ?: return null to false
        return when {
            trimmed == MARKER -> null to true
            trimmed.endsWith("\n\n$MARKER") -> trimmed.removeSuffix("\n\n$MARKER").trimEnd() to true
            else -> trimmed.ifEmpty { null } to false
        }
    }

    private fun operation(
        found: Found,
        compiledTag: String,
        names: Scope,
        serviceDeprecated: Boolean,
    ): Pair<UnitOperation, SourceOperation>? {
        val op = found.operation
        val at = found.at
        if (found.verb !in VERBS) return droppedOperation(at, "Schemata has no ${found.verb} verb")
        val wires =
            template(found.path)
                ?: return droppedOperation(at, "its path is not one a binding can write")
        val parameters = parameters(found)
        val pathParameters = parameters.filter { it.value.`in` == "path" }
        // a path segment holds one value, so a path parameter is a scalar or an enum
        for (wire in wires) {
            val p = pathParameters.firstOrNull { it.value.name == wire } ?: continue
            if (
                p.value.schema == null ||
                    !flat(p.doc, p.at / "schema", p.node.path("schema"), false)
            )
                return droppedOperation(p.at, "path parameter '$wire' is not a scalar or an enum")
        }
        val (name, nameAnnotation) = name(found, compiledTag, names)
        // the models are named after the operationId the target writes back, so a compile and a
        // second import give them the same names: the document's own when it is valid, since
        // `@openapi(name)` keeps it, otherwise `<Tag>_<name>`
        val written =
            op.operationId?.takeIf { OpenApiNames.ID.matches(it) } ?: "${compiledTag}_$name"
        val opId = ImportNames.upperCamel(written)
        val bodyVerb = found.verb in BODY_VERBS
        val owner = "${opId}Request"
        val fields = Scope()
        val requestFields = mutableListOf<UnitField>()
        val fieldOf = mutableMapOf<String, String>()
        wires.forEach { wire ->
            val p = pathParameters.firstOrNull { it.value.name == wire }
            if (p == null)
                report.at(
                    doc,
                    at,
                    ImportCodes.APPROXIMATED,
                    "path parameter '$wire' is not declared, so it is imported as a string",
                )
            val field = parameterField(p, wire, "path", owner, fields, p?.doc ?: doc, p?.at ?: at)
            fieldOf[wire] = field.name
            requestFields += field
        }
        val headerNames = mutableSetOf<String>()
        val accepted = parameters.filter { accept(it, found.verb, headerNames) }
        // the target sends a post, put or patch request's fields in the body, never in the query
        val queryLocation = if (bodyVerb) "body" else "query"
        accepted
            .filter { it.value.`in` == "query" }
            .forEach {
                requestFields +=
                    parameterField(it, it.value.name, queryLocation, owner, fields, it.doc, it.at)
            }
        accepted
            .filter { it.value.`in` == "header" }
            .forEach {
                requestFields +=
                    parameterField(it, it.value.name, "header", owner, fields, it.doc, it.at)
            }
        val bodyAt = at / "requestBody"
        var requestBody: Body? = null
        var streamed = false
        op.requestBody?.let { written ->
            if (!bodyVerb) {
                dropped(bodyAt, "request body dropped; ${found.verb} sends no body")
                return@let
            }
            val place =
                refs.follow(
                    report,
                    Placed(written, doc, bodyAt, node(bodyAt)),
                    "requestBodies",
                    "request body",
                    { it.`$ref` },
                    { d, n, r -> d.getRequestBody(n, "requestBody", r) },
                ) ?: return@let
            // a streamed request takes no parameters, so it is read only from a bare body
            val picked =
                pick(place.value.content, place, NDJSON.takeIf { requestFields.isEmpty() })
                    ?: return@let
            streamed = picked.stream
            requestBody =
                body(
                    picked.media,
                    owner,
                    "the request body",
                    "parameters".takeIf { requestFields.isNotEmpty() },
                )
        }
        val request =
            if (requestFields.isEmpty()) {
                requestBody?.let { UnitPayload(UnitType.Ref(payloadName(it, bodyAt)), streamed) }
            } else {
                val part = (requestBody as? Body.Declared)?.decl as? UnitRecord
                val decl =
                    UnitRecord(
                        name = owner,
                        fields = requestFields + renamed(part?.fields.orEmpty(), fields, bodyAt),
                        nested = schemas.takeNested(owner) + part?.nested.orEmpty(),
                        doc = part?.doc,
                        annotations = part?.annotations.orEmpty(),
                        deprecated = part?.deprecated ?: false,
                    )
                UnitPayload(UnitType.Ref(synthesize(at, decl)), stream = false)
            }
        val responsesAt = at / "responses"
        val responses = op.responses.orEmpty()
        val successes = responses.keys.mapNotNull { it.toIntOrNull() }.filter { it in 200..299 }
        val chosen = SUCCESS.filter { it in successes }.minOrNull() ?: successes.minOrNull()
        val status =
            when (chosen) {
                null -> 204
                in SUCCESS -> chosen
                else -> 200
            }
        var response: UnitPayload? = null
        val errors = mutableListOf<UnitErrorResponse>()
        responses.forEach { (key, written) ->
            val place = Placed(written, doc, responsesAt / key, node(responsesAt / key))
            val code = key.toIntOrNull()
            when {
                key == "default" ->
                    errors +=
                        UnitErrorResponse(
                            null,
                            response(place, key, "${opId}ErrorDefault", false)?.type,
                        )
                code == null || code !in 100..599 ->
                    droppedResponse(place.at, key, "it is not a status code Schemata writes")
                code < 200 ->
                    droppedResponse(place.at, key, "Schemata writes no informational responses")
                code == chosen -> {
                    if (code !in SUCCESS)
                        report.at(
                            doc,
                            place.at,
                            ImportCodes.APPROXIMATED,
                            "success response '$key' is imported as 200",
                        )
                    response = response(place, key, "${opId}Response", true)
                }
                code < 300 -> droppedResponse(place.at, key, "the success response is $status")
                code < 400 -> droppedResponse(place.at, key, "Schemata writes no redirects")
                else ->
                    errors +=
                        UnitErrorResponse(
                            code,
                            response(place, key, "${opId}Error$key", false)?.type,
                        )
            }
        }
        if (chosen == null)
            report.at(
                doc,
                responsesAt,
                ImportCodes.APPROXIMATED,
                "the operation has no success response, so it is imported as a 204",
            )
        if (!op.callbacks.isNullOrEmpty()) dropped(at / "callbacks", "callbacks dropped")
        if (!op.servers.isNullOrEmpty()) dropped(at / "servers", "servers dropped")
        if (op.externalDocs != null) dropped(at / "externalDocs", "externalDocs dropped")
        // a path parameter is bound by its field's name; `@openapi(name)` writes the wire name back
        val path =
            found.path.split("/").joinToString("/") { segment ->
                if (segment.length > 2 && segment.startsWith("{") && segment.endsWith("}"))
                    "{${fieldOf.getValue(segment.substring(1, segment.length - 1))}}"
                else segment
            }
        val default = if (response != null) 200 else 204
        val binding =
            "${found.verb} ${SchemataText.string(path)}" + if (status != default) " $status" else ""
        val operation =
            UnitOperation(
                name = name,
                request = request,
                response = response,
                binding = binding,
                doc =
                    listOfNotNull(op.summary, op.description)
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .joinToString("\n\n")
                        .ifEmpty { null },
                annotations = listOfNotNull(nameAnnotation),
                deprecated = op.deprecated == true && !serviceDeprecated,
                errors =
                    errors.sortedWith(compareBy<UnitErrorResponse, Int?>(nullsLast()) { it.code }),
            )
        return operation to SourceOperation(op, at)
    }

    /**
     * The `{name}` segments of [path] in order; null when the path is not one a binding can write:
     * `/` and segments of `A-Z a-z 0-9 . _ ~ -` or whole `{name}` parameters, each once.
     */
    private fun template(path: String): List<String>? {
        if (path == "/") return emptyList()
        if (!path.startsWith("/") || path.endsWith("/")) return null
        val wires = mutableListOf<String>()
        for (segment in path.substring(1).split("/")) {
            when {
                segment.length > 2 && segment.startsWith("{") && segment.endsWith("}") ->
                    wires += segment.substring(1, segment.length - 1)
                SEGMENT.matches(segment) -> {}
                else -> return null
            }
        }
        return wires.takeIf { it.distinct().size == it.size }
    }

    /**
     * The operation's parameters resolved: the path item's, less those the operation redefines by
     * name and place, then the operation's own.
     */
    private fun parameters(found: Found): List<Placed<Parameter>> {
        fun resolved(list: List<Parameter>?, at: Pointer): List<Placed<Parameter>> =
            list.orEmpty().mapIndexedNotNull { i, p ->
                refs.follow(
                    report,
                    Placed(p, doc, at / i, node(at / i)),
                    "parameters",
                    "parameter",
                    { it.`$ref` },
                    { d, n, r -> d.getParameter(n, "parameter", r) },
                )
            }
        val shared = resolved(found.item.parameters, ROOT / "paths" / found.path / "parameters")
        val own = resolved(found.operation.parameters, found.at / "parameters")
        val redefined = own.map { it.value.`in` to it.value.name }.toSet()
        return shared.filter { (it.value.`in` to it.value.name) !in redefined } + own
    }

    /**
     * Whether a query or header parameter joins the request; a cookie, one with no schema, an
     * object, or a header OpenAPI cannot carry is reported and left out. A path parameter is placed
     * by the path instead.
     */
    private fun accept(
        p: Placed<Parameter>,
        verb: String,
        headerNames: MutableSet<String>,
    ): Boolean {
        val param = p.value
        val wire = param.name ?: return false
        val schemaAt = p.at / "schema"
        val schemaNode = p.node.path("schema")
        return when (param.`in`) {
            "query" ->
                when {
                    param.schema == null ->
                        drop(
                            p,
                            "parameter '$wire' dropped; it is described by content, not a schema",
                        )
                    !flat(p.doc, schemaAt, schemaNode, list = true) ->
                        drop(
                            p,
                            "parameter '$wire' dropped; a query parameter holds a scalar, an enum, or a list of them",
                        )
                    else -> {
                        if (verb in BODY_VERBS)
                            report.at(
                                p.doc,
                                p.at,
                                ImportCodes.APPROXIMATED,
                                "query parameter '$wire' becomes a body field, since $verb sends no query parameters",
                            )
                        true
                    }
                }
            "header" -> {
                val problem =
                    headerProblem(
                        wire,
                        param.schema != null,
                        p.doc,
                        schemaAt,
                        schemaNode,
                        headerNames,
                    )
                if (problem == null) true else drop(p, "header '$wire' dropped; $problem")
            }
            "cookie" -> drop(p, "cookie parameter '$wire' dropped")
            else -> false
        }
    }

    /**
     * Why a header named [wire] cannot be a header field, as the target refuses one; null when it
     * can. [seen] holds the lower-cased names already taken in the same model.
     */
    private fun headerProblem(
        wire: String,
        hasSchema: Boolean,
        on: OpenApiDoc,
        at: Pointer,
        node: JsonNode,
        seen: MutableSet<String>,
    ): String? {
        val lower = wire.lowercase()
        val problem =
            when {
                !HTTP_TOKEN.matches(wire) -> "it is not an HTTP token"
                lower in IGNORED_HEADERS -> "OpenAPI ignores a header of this name"
                lower in seen -> "it repeats an earlier header of the same name, ignoring case"
                !hasSchema -> "it is described by content, not a schema"
                !flat(on, at, node, list = false) -> "a header holds a scalar or an enum"
                else -> null
            }
        if (problem == null) seen += lower
        return problem
    }

    /**
     * The operation's name: the part after `<Tag>_` of an id the target wrote, else the id in
     * lower_snake, else the verb and path; with `@openapi(name)` keeping an id the target would not
     * write back as `<Tag>_<name>`.
     */
    private fun name(
        found: Found,
        compiledTag: String,
        names: Scope,
    ): Pair<String, UnitAnnotation?> {
        val id = found.operation.operationId
        val valid = id != null && OpenApiNames.ID.matches(id)
        val rest =
            id?.removePrefix("${compiledTag}_")?.takeIf {
                valid && it != id && ImportNames.isLowerSnake(it)
            }
        val wanted =
            rest ?: ImportNames.lowerSnake(id ?: "${found.verb}_${found.path.replace(BRACES, "")}")
        val name = names.claim(wanted)
        val at = found.at / "operationId"
        when {
            id != null && !valid -> invalid(doc, at, "operationId", id, name)
            name != wanted -> taken(doc, at, "operation", id ?: wanted, name, wanted)
        }
        val annotation = if (valid && "${compiledTag}_$name" != id) openapi("name", id!!) else null
        return name to annotation
    }

    /**
     * A parameter as a field of the request model: named in lower_snake, its wire name kept with
     * `@openapi(name)` in the path or the query, `@openapi(header)` in a header, and
     * `@jsonschema(name)` when it travels in the body. A path parameter is never nullable; another
     * is nullable unless required or defaulted, since the target leaves a defaulted parameter
     * optional. [p] is null for a path segment the operation does not declare, a string.
     */
    private fun parameterField(
        p: Placed<Parameter>?,
        wire: String,
        location: String,
        owner: String,
        scope: Scope,
        on: OpenApiDoc,
        at: Pointer,
    ): UnitField {
        val wanted = if (ImportNames.isLowerSnake(wire)) wire else ImportNames.lowerSnake(wire)
        val name = scope.claim(wanted)
        if (name != wanted) taken(on, at, "parameter", wire, name, wanted)
        val annotations = mutableListOf<UnitAnnotation>()
        when {
            location == "header" -> annotations += openapi("header", wire)
            name == wire -> {}
            location == "body" ->
                annotations += UnitAnnotation("jsonschema", "name", SchemataText.string(wire))
            isParameterName(wire) -> annotations += openapi("name", wire)
            else -> invalid(on, at, "parameter", wire, name)
        }
        if (p == null) return UnitField(name, STRING, false, null, null, annotations)
        val param = p.value
        val shape =
            schemas.fieldType(p.doc, p.at / "schema", param.schema, owner, name, into = namespace)
        val nullable =
            location != "path" &&
                if (param.required == true) shape.nullable else shape.default == null
        return UnitField(
            name = name,
            type = shape.type,
            nullable = nullable,
            default = shape.default,
            doc = param.description?.trimEnd()?.ifEmpty { null } ?: shape.doc,
            annotations = annotations + shape.annotations,
            deprecated = param.deprecated == true || shape.deprecated,
        )
    }

    /**
     * A response's payload, or null when it has none. [wanted] names a model made for it; a
     * response with headers is named by its description when that is a model name. A 204 has no
     * body and no headers, and a streamed response no headers.
     */
    private fun response(
        written: Placed<ApiResponse>,
        key: String,
        wanted: String,
        success: Boolean,
    ): UnitPayload? {
        val place =
            refs.follow(
                report,
                written,
                "responses",
                "response",
                { it.`$ref` },
                { d, n, r -> d.getResponse(n, "response", r) },
            ) ?: return null
        val value = place.value
        if (!value.links.isNullOrEmpty()) dropped(place.at / "links", "links dropped", place.doc)
        val headers =
            value.headers.orEmpty().mapNotNull { (wire, header) ->
                val at = place.at / "headers" / wire
                refs
                    .follow(
                        report,
                        Placed(header, place.doc, at, place.node.path("headers").path(wire)),
                        "headers",
                        "header",
                        { it.`$ref` },
                        { d, n, r -> d.getHeader(n, "header", r) },
                    )
                    ?.let { wire to it }
            }
        if (success && key == "204") {
            if (!value.content.isNullOrEmpty())
                dropped(
                    place.at / "content",
                    "content of response '204' dropped; a 204 has no body",
                    place.doc,
                )
            headers.forEach { (wire, h) ->
                dropped(
                    h.at,
                    "header '$wire' dropped; a 204 response carries no header fields",
                    h.doc,
                )
            }
            return null
        }
        val picked = pick(value.content, place, EVENT_STREAM.takeIf { success })
        val role = "the body of response '$key'"
        if (picked?.stream == true) {
            headers.forEach { (wire, h) ->
                dropped(
                    h.at,
                    "header '$wire' dropped; a streamed response carries no header fields",
                    h.doc,
                )
            }
            return body(picked.media, wanted, role, null)?.let {
                UnitPayload(UnitType.Ref(payloadName(it, place.at)), stream = true)
            }
        }
        val seen = mutableSetOf<String>()
        val kept =
            headers.filter { (wire, h) ->
                val problem =
                    headerProblem(
                        wire,
                        h.value.schema != null,
                        h.doc,
                        h.at / "schema",
                        h.node.path("schema"),
                        seen,
                    )
                if (problem != null) dropped(h.at, "header '$wire' dropped; $problem", h.doc)
                problem == null
            }
        if (kept.isEmpty())
            return picked?.let { media ->
                body(media.media, wanted, role, null)?.let {
                    UnitPayload(UnitType.Ref(payloadName(it, place.at)), stream = false)
                }
            }
        val name = described(value.description) ?: wanted
        val part =
            picked?.let {
                (body(it.media, name, role, "headers") as? Body.Declared)?.decl as? UnitRecord
            }
        val scope = Scope().apply { part?.fields?.forEach { claim(it.name) } }
        val headerFields = kept.map { (wire, h) -> headerField(h, wire, name, scope) }
        val decl =
            UnitRecord(
                name = name,
                fields = part?.fields.orEmpty() + headerFields,
                nested = part?.nested.orEmpty() + schemas.takeNested(name),
                doc = part?.doc,
                annotations = part?.annotations.orEmpty(),
                deprecated = part?.deprecated ?: false,
            )
        return UnitPayload(UnitType.Ref(synthesize(place.at, decl)), stream = false)
    }

    /**
     * A response header as a field: named in lower_snake with `@openapi(header)` keeping the
     * header's name, nullable unless required or defaulted, as the target writes `required`.
     */
    private fun headerField(
        h: Placed<Header>,
        wire: String,
        owner: String,
        scope: Scope,
    ): UnitField {
        val wanted = if (ImportNames.isLowerSnake(wire)) wire else ImportNames.lowerSnake(wire)
        val name = scope.claim(wanted)
        if (name != wanted) taken(h.doc, h.at, "header", wire, name, wanted)
        val header = h.value
        val shape =
            schemas.fieldType(h.doc, h.at / "schema", header.schema, owner, name, into = namespace)
        return UnitField(
            name = name,
            type = shape.type,
            nullable = if (header.required == true) shape.nullable else shape.default == null,
            default = shape.default,
            doc = header.description?.trimEnd()?.ifEmpty { null } ?: shape.doc,
            annotations = listOf(openapi("header", wire)) + shape.annotations,
            deprecated = header.deprecated == true || shape.deprecated,
        )
    }

    /**
     * [media]'s schema as a body named [wanted]: a model or union component by name, an inline
     * model or union as its own declaration, anything else wrapped in a model with one field
     * `items`. Beside parameters or headers ([beside]) the body's fields join theirs, so a model
     * component is copied and a union wrapped. [role] names the body in a message.
     */
    private fun body(
        media: Placed<MediaType>,
        wanted: String,
        role: String,
        beside: String?,
    ): Body? {
        val schema = media.value.schema ?: return null
        val at = media.at / "schema"
        val node = media.node.path("schema")
        val ref = schema.`$ref`
        val target = if (ref != null) (refs.resolve(media.doc, at, ref) ?: return null) else null
        // a reference is read as the declaration its component lowers to; one that declares
        // nothing, such as an alias or a schema inside a component, is wrapped. A component is
        // read from its own document's tree, which a file reached only through a `$ref` has even
        // though its model is empty
        val declared = target?.let(schemas::declarationKind)
        val kind = if (target == null) kind(media.doc, at, node) else null
        return when {
            declared == DeclKind.MODEL && beside != null -> {
                report.at(
                    media.doc,
                    at,
                    ImportCodes.APPROXIMATED,
                    "'${schemas.refName(target, namespace)}' is copied into '$wanted' to sit beside the $beside",
                )
                Body.Declared(
                    schemas.declaration(
                        target.doc,
                        target.pointer,
                        schema,
                        wanted,
                        into = namespace,
                    )
                )
            }
            target != null &&
                beside == null &&
                (declared == DeclKind.MODEL || declared == DeclKind.UNION) ->
                Body.Named(schemas.refName(target, namespace))
            target == null && (kind == Kind.MODEL || (kind == Kind.UNION && beside == null)) ->
                Body.Declared(schemas.declaration(media.doc, at, schema, wanted, into = namespace))
            else -> {
                report.at(
                    media.doc,
                    at,
                    ImportCodes.APPROXIMATED,
                    "$role is wrapped in '$wanted' as field 'items', since it is not a model",
                )
                val shape =
                    schemas.fieldType(media.doc, at, schema, wanted, "items", into = namespace)
                val items =
                    UnitField(
                        name = "items",
                        type = shape.type,
                        nullable = shape.nullable,
                        default = shape.default,
                        doc = shape.doc,
                        annotations = shape.annotations,
                        deprecated = shape.deprecated,
                    )
                Body.Declared(
                    UnitRecord(
                        name = wanted,
                        fields = listOf(items),
                        nested = schemas.takeNested(wanted),
                        doc = null,
                        annotations = emptyList(),
                    )
                )
            }
        }
    }

    /** The name a payload refers to [body] by, claiming a declaration made for it. */
    private fun payloadName(body: Body, at: Pointer): String =
        when (body) {
            is Body.Named -> body.name
            is Body.Declared -> synthesize(at, body.decl)
        }

    /**
     * The body's fields after the parameters' or headers' in [scope]; a field whose name is taken
     * is renamed and keeps its property name with `@jsonschema(name)`.
     */
    private fun renamed(fields: List<UnitField>, scope: Scope, at: Pointer): List<UnitField> =
        fields.map { field ->
            val name = scope.claim(field.name)
            if (name == field.name) return@map field
            taken(doc, at, "property", field.name, name, field.name)
            val keeps = field.annotations.any { it.target == "jsonschema" && it.key == "name" }
            field.copy(
                name = name,
                annotations =
                    if (keeps) field.annotations
                    else
                        listOf(
                            UnitAnnotation("jsonschema", "name", SchemataText.string(field.name))
                        ) + field.annotations,
            )
        }

    /**
     * The media type a body is read from: the first JSON one (`application/json` or a `+json`
     * type), else [stream] when the body may be one; every other media type is reported.
     */
    private fun pick(content: Content?, owner: Placed<*>, stream: String?): Picked? {
        val entries = content.orEmpty().entries.toList()
        val chosen =
            entries.firstOrNull { json(it.key) }
                ?: entries.firstOrNull { stream != null && base(it.key) == stream }
        entries
            .filter { it !== chosen }
            .forEach { (type, _) ->
                dropped(
                    owner.at / "content" / type,
                    "media type '$type' dropped; only JSON bodies are read",
                    owner.doc,
                )
            }
        chosen ?: return null
        val at = owner.at / "content" / chosen.key
        return Picked(
            Placed(chosen.value, owner.doc, at, owner.node.path("content").path(chosen.key)),
            stream = !json(chosen.key),
        )
    }

    private fun base(type: String): String = type.substringBefore(';').trim().lowercase()

    private fun json(type: String): Boolean =
        base(type).let { it == "application/json" || it.endsWith("+json") }

    /** What the schema [node] at [at] is, through references and the nullable `anyOf`. */
    private fun kind(on: OpenApiDoc, at: Pointer, node: JsonNode, depth: Int = 0): Kind {
        val ref = node.path("\$ref").textValue()
        if (ref != null) {
            if (depth == DEPTH) return Kind.OTHER
            val target = refs.resolve(on, at, ref) ?: return Kind.OTHER
            return kind(target.doc, target.pointer, target.node, depth + 1)
        }
        if (node.has("enum")) return Kind.ENUM
        val choice = CHOICES.firstOrNull { node.path(it).isArray }
        if (choice != null) {
            val members = node.path(choice).toList()
            val others = members.withIndex().filterNot { isNull(it.value) }
            if (others.size == 1 && others.size < members.size)
                return kind(on, at / choice / others[0].index, others[0].value, depth)
            if (members.isNotEmpty() && members.all { it.has("const") }) return Kind.ENUM
            return Kind.UNION
        }
        if (node.has("properties") || node.has("allOf")) return Kind.MODEL
        return Kind.OTHER
    }

    /**
     * Whether the schema [node] is one value a parameter or header holds: a scalar or an enum, or,
     * when [list] allows, a list of those.
     */
    private fun flat(
        on: OpenApiDoc,
        at: Pointer,
        node: JsonNode,
        list: Boolean,
        depth: Int = 0,
    ): Boolean {
        val ref = node.path("\$ref").textValue()
        if (ref != null) {
            if (depth == DEPTH) return false
            val target = refs.resolve(on, at, ref) ?: return false
            return flat(target.doc, target.pointer, target.node, list, depth + 1)
        }
        if (node.has("enum")) return true
        val choice = CHOICES.firstOrNull { node.path(it).isArray }
        if (choice != null) {
            val members = node.path(choice).toList()
            val others = members.withIndex().filterNot { isNull(it.value) }
            if (others.size == 1 && others.size < members.size)
                return flat(on, at / choice / others[0].index, others[0].value, list, depth)
            return members.isNotEmpty() && members.all { it.has("const") }
        }
        if (
            node.has("properties") ||
                node.has("allOf") ||
                node.path("additionalProperties").isObject
        )
            return false
        val types = types(node)
        if ("object" in types) return false
        if ("array" in types)
            return list && flat(on, at / "items", node.path("items"), false, depth)
        return true
    }

    private fun types(node: JsonNode): Set<String> {
        val type = node.path("type")
        val all =
            when {
                type.isTextual -> setOf(type.textValue())
                type.isArray -> type.mapNotNull { it.textValue() }.toSet()
                else -> emptySet()
            }
        return all - "null"
    }

    private fun isNull(node: JsonNode): Boolean = node.path("type").textValue() == "null"

    /**
     * A response's description when it is a model name, as the target describes one with headers.
     */
    private fun described(description: String?): String? =
        description?.trim()?.takeIf { OpenApiNames.isUpperCamel(it) && it.any(Char::isLowerCase) }

    /** Claims [decl]'s name at the top of the namespace, reporting a suffix the name took. */
    private fun synthesize(at: Pointer, decl: UnitDecl): String {
        val name = schemas.synthesize(namespace, decl.name, decl)
        if (name != decl.name) taken(doc, at, kindWord(decl), decl.name, name, decl.name)
        top.claim(name)
        return name
    }

    private fun kindWord(decl: UnitDecl): String =
        when (decl) {
            is UnitRecord -> "model"
            is UnitEnum -> "enum"
            is UnitUnion -> "union"
        }

    private fun node(at: Pointer): JsonNode = doc.tree.at(at.text.removePrefix("#"))

    private fun openapi(key: String, value: String): UnitAnnotation =
        UnitAnnotation("openapi", key, SchemataText.string(value))

    private fun dropped(at: Pointer, message: String, on: OpenApiDoc = doc) {
        report.at(on, at, ImportCodes.DROPPED, message)
    }

    private fun drop(p: Placed<Parameter>, message: String): Boolean {
        dropped(p.at, message, p.doc)
        return false
    }

    private fun droppedOperation(
        at: Pointer,
        reason: String,
    ): Pair<UnitOperation, SourceOperation>? {
        dropped(at, "operation dropped; $reason")
        return null
    }

    private fun droppedResponse(at: Pointer, key: String, reason: String) {
        dropped(at, "response '$key' dropped; $reason")
    }

    private fun taken(
        on: OpenApiDoc,
        at: Pointer?,
        kind: String,
        wire: String,
        name: String,
        wanted: String,
    ) {
        report.at(
            on,
            at,
            ImportCodes.RENAMED,
            "$kind '$wire' is imported as '$name', since '$wanted' is taken",
            SUFFIX_HELP,
        )
    }

    private fun invalid(on: OpenApiDoc, at: Pointer?, kind: String, wire: String, name: String) {
        report.at(
            on,
            at,
            ImportCodes.RENAMED,
            "$kind '$wire' is imported as '$name' with no @openapi(name), since the target cannot write it",
            INVALID_HELP,
        )
    }

    private companion object {
        val ROOT = Pointer("#")
        // the verbs a binding writes; a document's trace operation has none
        val VERBS = setOf("get", "put", "post", "delete", "options", "head", "patch")
        // the target sends these verbs' request fields as a body, the others' as query parameters
        val BODY_VERBS = setOf("post", "put", "patch")
        val SUCCESS = listOf(200, 201, 202, 204)
        val CHOICES = listOf("oneOf", "anyOf")
        const val NDJSON = "application/x-ndjson"
        const val EVENT_STREAM = "text/event-stream"
        const val MARKER = "Deprecated."
        // a reference chain longer than this is a loop the resolver did not catch
        const val DEPTH = 32
        // a binding's literal path segment, as the language reads one
        val SEGMENT = Regex("[A-Za-z0-9._~-]+")
        val BRACES = Regex("[{}]")
        // the characters a path segment or a query string would have to escape in a parameter
        // name
        const val PARAMETER_PUNCTUATION = "{}/?#&=%"

        /**
         * Whether the target can write [name] as a path or query parameter's name: not empty, and
         * free of whitespace, control characters and [PARAMETER_PUNCTUATION]. This must stay in
         * step with the target's own rule, which refuses any other name:
         * `OpenApiLowering.isParameterName` in schemata-target-openapi, and its copy
         * `OpenApiRules.isParameterName` in schemata-evolution.
         */
        fun isParameterName(name: String): Boolean =
            name.isNotEmpty() &&
                name.none { it.isWhitespace() || it.isISOControl() || it in PARAMETER_PUNCTUATION }

        // the `tchar` characters of an HTTP token, which a header name is made of
        val HTTP_TOKEN = Regex("[A-Za-z0-9!#\$%&'*+.^_`|~-]+")
        // OpenAPI ignores header parameters of these names: the media types and the credentials
        val IGNORED_HEADERS = setOf("accept", "content-type", "authorization")
        val STRING = UnitType.Scalar("string", emptyList())
        const val INVALID_HELP =
            "rename it in the document to a name the target can write, to keep its spelling"
    }
}
