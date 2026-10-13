package io.schemata.evolution

import io.schemata.core.ir.AnnotationValue
import io.schemata.core.ir.Field
import io.schemata.core.ir.Operation
import io.schemata.core.ir.QualifiedName
import io.schemata.core.ir.Service
import io.schemata.core.ir.service
import io.schemata.target.HttpSecurity
import io.schemata.target.SecurityRequirement
import io.schemata.target.string

/**
 * What each kind of [Change] means for a client generated from the old OpenAPI document: whether
 * every call it makes still reaches the same operation with the same request and response. A
 * record, enum, or union is only in that document when some OLD service's request, response, or
 * error body reaches it; such a declaration's changes are judged exactly as JSON Schema judges
 * them, since its component is the JSON Schema lowering's, named by the `@jsonschema` keys. Any
 * other declaration's changes are invisible to the document's clients. Success codes, error
 * responses, headers, and security requirements exist only in this document, so only this rulebook
 * reads them.
 */
object OpenApiRules : Rulebook {
    override val target = "openapi"

    private const val HEADER = "header"

    /** The keys whose change moves the requirement of every operation beneath them. */
    private val REQUIREMENT_KEYS = setOf("auth", "scopes")

    /**
     * A change to a key reaches the models referencing it; only the ones OLD's document carries
     * count.
     */
    override fun classify(change: Change, ctx: ChangeContext): Verdict =
        if (touchesOnlyBackReference(change)) Verdict.Compatible
        else
            ReferencedKeys.judge(target, change, ctx, judge(change, ctx)) {
                ctx.reachableFromServices(Side.OLD, it)
            }

    private fun judge(change: Change, ctx: ChangeContext): Verdict =
        when (change) {
            is ServiceAdded -> Verdict.Compatible
            is ServiceRemoved ->
                Verdict.Breaking(
                    "${change.path}: the service was removed breaks clients that call its " +
                        "operations",
                    "deprecate the service and keep it until no client calls it",
                )
            is OperationAdded -> Verdict.Compatible
            is OperationRemoved -> operationRemoved(change, ctx)
            is OperationRenamed -> operationRenamed(change, ctx)
            // the operationId and a derived URL are built from the name, never the ordinal
            is OperationMoved -> Verdict.Compatible
            is OperationRequestChanged ->
                Verdict.Breaking(
                    "${change.path}: the request changed from ${payloadText(change.from)} to " +
                        "${payloadText(change.to)} breaks clients that send the old one",
                    "add a new operation instead of changing this one's request",
                )
            is OperationResponseChanged ->
                Verdict.Breaking(
                    "${change.path}: the response changed from ${payloadText(change.from)} to " +
                        "${payloadText(change.to)} breaks clients that read the old one",
                    "add a new operation instead of changing this one's response",
                )
            is OperationBindingChanged -> bindingChanged(change, ctx)
            is OperationStatusChanged ->
                Verdict.Breaking(
                    "${change.path}: the success code changed from ${change.from} to " +
                        "${change.to} breaks clients that check for the old one",
                    "add a new operation instead of changing this one's success code",
                )
            // a generated client may treat a code it was never told about as a failure
            is OperationErrorAdded ->
                Verdict.Note(
                    "${change.path}: the operation can now answer ${errorText(change.error)}; " +
                        "a client may not handle it",
                    "tell clients about the new response before the operation sends it",
                )
            is OperationErrorRemoved -> Verdict.Compatible
            // an error body is read as a response body is
            is OperationErrorChanged ->
                Verdict.Breaking(
                    "${change.path}: the ${errorCodeText(change.code)} response changed from " +
                        "${errorBodyText(change.from)} to ${errorBodyText(change.to)} breaks " +
                        "clients that read the old one",
                    "add a new operation instead of changing this one's error responses",
                )
            is NamespaceAdded -> Verdict.Compatible
            is NamespaceRemoved -> namespaceRemoved(change, ctx)
            is DeclarationAdded -> Verdict.Compatible
            is DeclarationRemoved -> data(change, change.decl.qualifiedName, ctx)
            is DeclarationKindChanged -> data(change, change.from.qualifiedName, ctx)
            is FieldAdded -> fieldAdded(change, ctx)
            is FieldRemoved -> data(change, change.record.qualifiedName, ctx)
            is FieldRenamed -> fieldRenamed(change, ctx)
            is FieldTypeChanged -> data(change, change.record.qualifiedName, ctx)
            is FieldNullabilityChanged -> data(change, change.record.qualifiedName, ctx)
            is FieldDefaultChanged -> data(change, change.record.qualifiedName, ctx)
            is FieldRefinementChanged -> data(change, change.record.qualifiedName, ctx)
            is EnumValueAdded -> data(change, change.enum.qualifiedName, ctx)
            is EnumValueRemoved -> data(change, change.enum.qualifiedName, ctx)
            is EnumValueRenamed -> data(change, change.enum.qualifiedName, ctx)
            is UnionMemberAdded -> data(change, change.union.qualifiedName, ctx)
            is UnionMemberRemoved -> data(change, change.union.qualifiedName, ctx)
            is UnionMemberTypeChanged -> data(change, change.union.qualifiedName, ctx)
            is ReservedChanged -> Verdict.Compatible
            is AnnotationChanged -> annotationChanged(change, ctx)
            is DeprecationChanged -> deprecationChanged(change, ctx)
            is DocChanged -> Verdict.Compatible
        }

    /** A data change, judged as JSON Schema judges it when OLD's document carries [decl]. */
    private fun data(change: Change, decl: QualifiedName, ctx: ChangeContext): Verdict =
        if (ctx.reachableFromServices(Side.OLD, decl)) JsonSchemaRules.rules.own(change, ctx)
        else Verdict.Compatible

    /**
     * A field added with `@openapi(header)` to a model an OLD operation takes as its request is a
     * header every client must now send, unless the field is nullable or has a default; on a
     * response or error body it is one more header a client may ignore. On any other model the
     * attribute changes nothing, and the field is judged as JSON Schema judges it.
     */
    private fun fieldAdded(change: FieldAdded, ctx: ChangeContext): Verdict {
        val record = change.record.qualifiedName
        val header =
            change.field.annotations.string(target, HEADER) ?: return data(change, record, ctx)
        val request = ctx.isRequest(Side.OLD, record)
        if (!request && !ctx.isResponse(Side.OLD, record)) return data(change, record, ctx)
        val required = !change.field.nullable && change.field.default == null
        return if (request && required)
            Verdict.Breaking(
                "${change.path}: a required request header $header was added, which breaks " +
                    "clients that do not send it",
                "make the field nullable or give it a default, so the header is optional",
            )
        else Verdict.Compatible
    }

    /**
     * Whether [from] and [to], one field of [record] on each side, travel as an HTTP header in
     * OLD's document and nothing else of OLD's document holds them: both carry `@openapi(header)`
     * and OLD's operations take or answer with the model only as one message, never reaching it as
     * a field's type, a union member, or a nested declaration, which would make it a component
     * carrying the field under its JSON name.
     */
    private fun onlyAHeader(
        record: QualifiedName,
        from: Field,
        to: Field,
        ctx: ChangeContext,
    ): Boolean =
        from.annotations.string(target, HEADER) != null &&
            to.annotations.string(target, HEADER) != null &&
            (ctx.isRequest(Side.OLD, record) || ctx.isResponse(Side.OLD, record)) &&
            !ctx.reachableAsPlainType(Side.OLD, record)

    /**
     * A header is named by `@openapi(header)`, never by the field, so renaming a field that is only
     * a header changes nothing a client sees; a header rename is judged on the attribute. A doc
     * change is compatible on any field, and a refinement change is judged as JSON Schema judges
     * it, as a header's type change is, since the header's schema carries the refinements.
     */
    private fun fieldRenamed(change: FieldRenamed, ctx: ChangeContext): Verdict {
        val record = change.record.qualifiedName
        return if (onlyAHeader(record, change.from, change.to, ctx)) Verdict.Compatible
        else data(change, record, ctx)
    }

    /** Only a namespace with services had a document; its declarations go with it. */
    private fun namespaceRemoved(change: NamespaceRemoved, ctx: ChangeContext): Verdict =
        if (ctx.old.namespaces.any { it.name == change.path && it.services.isNotEmpty() })
            Verdict.Breaking(
                "${change.path}: the schema was removed breaks clients that call its " +
                    "services' operations",
                "keep the schema's services until no client calls them",
            )
        else Verdict.Compatible

    /**
     * Reserving the removed name keeps a later operation from taking over its default `operationId`
     * for a different call; the ordinal never reaches an OpenAPI client.
     */
    private fun operationRemoved(change: OperationRemoved, ctx: ChangeContext): Verdict {
        val op = change.operation
        val status = ctx.reservedInNew(change.service.qualifiedName, op.ordinal, op.name)
        val nameReserved = status == ReservedStatus.BOTH || status == ReservedStatus.NAME_ONLY
        val help = "deprecate the operation and keep it until no client calls it"
        return Verdict.Breaking(
            "${change.path}: the operation was removed breaks clients that call it",
            if (nameReserved) help
            else "$help; reserve \"${op.name}\" so its operationId is not reused for another call",
        )
    }

    /**
     * A rename moves the default `operationId` (an `@openapi(name)` that keeps it pins it), and on
     * an operation with no binding also the derived URL, which is built from the name itself.
     */
    private fun operationRenamed(change: OperationRenamed, ctx: ChangeContext): Verdict {
        val oldService = ctx.oldService(change.service)
        val fromId = ctx.emittedName(target, OperationOwner(oldService, change.from))
        val toId = ctx.emittedName(target, OperationOwner(change.service, change.to))
        val fromUrl = url(oldService, change.from, ctx)
        val toUrl = url(change.service, change.to, ctx)
        // A binding rewritten in the same step has its own change, which names both URLs; the
        // rename only says that the URL moves with it.
        val urlWord =
            if (change.from.binding != change.to.binding) "its URL changes with its binding"
            else "its URL changes from $fromUrl to $toUrl"
        val what =
            listOfNotNull(
                    "its operationId changes from $fromId to $toId".takeIf { fromId != toId },
                    urlWord.takeIf { fromUrl != toUrl },
                )
                .joinToString(", and ")
        if (what.isEmpty()) return Verdict.Compatible
        return Verdict.Breaking(
            "${change.path}: the operation was renamed, so $what",
            if (fromId != toId) "pin the operationId with @openapi(name: \"$fromId\")"
            else "bind the operation to its old URL with $fromUrl",
        )
    }

    /**
     * A binding added or removed moves the operation to or from its derived URL, unless the binding
     * is that derived URL: a client sees only the verb and path, so the same URL either way is
     * compatible.
     */
    private fun bindingChanged(change: OperationBindingChanged, ctx: ChangeContext): Verdict {
        val oldService = ctx.oldService(change.service)
        val oldOp =
            oldService.operations.firstOrNull { it.ordinal == change.operation.ordinal }
                ?: change.operation
        val fromUrl = change.from?.let(::bindingText) ?: derivedUrl(oldService, oldOp, ctx)
        val toUrl =
            change.to?.let(::bindingText) ?: derivedUrl(change.service, change.operation, ctx)
        if (fromUrl == toUrl) return Verdict.Compatible
        val how =
            when {
                change.from == null -> "the derived path is now bound, so the URL changes"
                change.to == null ->
                    "the binding was removed, so the URL changes to the derived one"
                else -> "the URL changes"
            }
        return Verdict.Breaking(
            "${change.path}: $how from $fromUrl to $toUrl, which breaks clients that call the old one",
            "add a new operation for the new URL instead of moving this one",
        )
    }

    /**
     * `@openapi(name)` on a service renames its tag, and with it every `operationId` that does not
     * pin its own; on an operation it replaces the `operationId`. Either is compatible only while
     * the emitted name stays the same, as when a pin is added alongside a rename. `auth` and
     * `scopes` are judged on the requirements they move, a schema line's scheme keys on the
     * credentials clients send, and `header` on where a field travels; the namespace's other keys
     * (`version`, `server`) describe the document, not any operation.
     */
    private fun annotationChanged(change: AnnotationChanged, ctx: ChangeContext): Verdict {
        val owner = change.newOwner
        if (change.target == target) {
            if (change.key in REQUIREMENT_KEYS) return requirementChanged(change, ctx)
            if (owner is NamespaceOwner && HttpSecurity.schemeOf(change.key) != null)
                return schemeChanged(change, ctx, owner)
            if (owner is FieldOwner && change.key == HEADER)
                return headerChanged(change, ctx, owner)
        }
        return when (owner) {
            is ServiceOwner,
            is OperationOwner -> nameChanged(change, ctx, owner)
            is NamespaceOwner -> Verdict.Compatible
            is DeclarationOwner -> data(change, owner.decl.qualifiedName, ctx)
            is FieldOwner -> data(change, owner.record.qualifiedName, ctx)
            is EnumValueOwner -> data(change, owner.enum.qualifiedName, ctx)
            is UnionMemberOwner -> data(change, owner.union.qualifiedName, ctx)
        }
    }

    private fun nameChanged(change: AnnotationChanged, ctx: ChangeContext, owner: Owner): Verdict {
        if (change.target != target || change.key != "name") return Verdict.Compatible
        val fromName = ctx.emittedName(target, change.oldOwner)
        val toName = ctx.emittedName(target, change.newOwner)
        if (fromName == toName) return Verdict.Compatible
        val what =
            if (owner is ServiceOwner)
                "the tag changes from $fromName to $toName, and every operationId built on it " +
                    "changes too"
            else "the operationId changes from $fromName to $toName"
        return Verdict.Breaking(
            "${change.path}: @openapi(name) ${changeWord(change)}, so $what",
            "keep @openapi(name: \"$fromName\")",
        )
    }

    /**
     * `auth` and `scopes` decide the requirement of every operation beneath the element that writes
     * them, so each such operation both sides have is judged on its requirement before and after:
     * one that now needs credentials, no longer accepts a scheme it did, or needs more scopes
     * refuses calls that worked; one opened up (`none`, a scheme added to the alternatives, scopes
     * removed) refuses nothing. When `auth` and `scopes` change together on one element, the
     * schemes are the `auth` change's to report, so the `scopes` change names only the scopes it
     * adds.
     */
    private fun requirementChanged(change: AnnotationChanged, ctx: ChangeContext): Verdict {
        val qualify = change.newOwner is NamespaceOwner
        val scopesOnly = change.key == "scopes" && auth(change.oldOwner) != auth(change.newOwner)
        val reasons =
            beneath(change.newOwner).mapNotNull { (service, op) ->
                val oldService = ctx.old.service(service.qualifiedName) ?: return@mapNotNull null
                val oldOp =
                    oldService.operations.firstOrNull { it.ordinal == op.ordinal }
                        ?: oldService.operations.firstOrNull { it.name == op.name }
                        ?: return@mapNotNull null
                val label = if (qualify) "'${service.name}.${op.name}'" else "'${op.name}'"
                val from = ctx.requirement(Side.OLD, oldService, oldOp)
                val to = ctx.requirement(Side.NEW, service, op)
                if (scopesOnly) scopesAdded(label, from, to) else tightened(label, from, to)
            }
        if (reasons.isEmpty()) return Verdict.Compatible
        return Verdict.Breaking(
            "${change.path}: ${annotationLabel(change)} ${changeWord(change)}, so " +
                reasons.joinToString(", and ") +
                ", which breaks clients without those credentials",
            "keep the old requirement on these operations, or require the new one on new operations",
        )
    }

    /** The operations, with their services, beneath the element [owner] stands for. */
    private fun beneath(owner: Owner): List<Pair<Service, Operation>> =
        when (owner) {
            is NamespaceOwner ->
                owner.namespace.services.flatMap { s -> s.operations.map { s to it } }
            is ServiceOwner -> owner.service.operations.map { owner.service to it }
            is OperationOwner -> listOf(owner.service to owner.operation)
            is DeclarationOwner,
            is FieldOwner,
            is EnumValueOwner,
            is UnionMemberOwner -> emptyList()
        }

    /** The `auth` written on the element [owner] stands for, or null when it writes none. */
    private fun auth(owner: Owner): AnnotationValue? {
        val annotations =
            when (owner) {
                is NamespaceOwner -> owner.namespace.annotations
                is ServiceOwner -> owner.service.annotations
                is OperationOwner -> owner.operation.annotations
                is DeclarationOwner,
                is FieldOwner,
                is EnumValueOwner,
                is UnionMemberOwner -> return null
            }
        return annotations[target]["auth"]
    }

    /** The scopes [new] needs that [old] did not, as a reason, or null when it needs none more. */
    private fun scopesAdded(
        label: String,
        old: SecurityRequirement?,
        new: SecurityRequirement?,
    ): String? {
        if (new == null || new.schemes.isEmpty()) return null
        val added = new.scopes.filter { it !in old?.scopes.orEmpty() }
        return if (added.isEmpty()) null
        else "operation $label now needs the scopes ${added.joinToString(" ")}"
    }

    /** Why [new] refuses a call [old] admitted, or null when it refuses none. */
    private fun tightened(
        label: String,
        old: SecurityRequirement?,
        new: SecurityRequirement?,
    ): String? {
        if (new == null || new.schemes.isEmpty()) return null
        if (old == null || old.schemes.isEmpty())
            return "operation $label now requires ${new.schemes.joinToString(" or ")}"
        val dropped = old.schemes.filter { it !in new.schemes }
        if (dropped.isNotEmpty())
            return "operation $label no longer accepts ${dropped.joinToString(" or ")}"
        val added = new.scopes.filter { it !in old.scopes }
        if (added.isNotEmpty())
            return "operation $label now needs the scopes ${added.joinToString(" ")}"
        return null
    }

    /**
     * A scheme added is one more a client may use. One removed while an OLD operation requires it
     * leaves that operation's clients without the credentials they send; an API key moved to
     * another place or name, or the oauth2 flow switched by adding or removing its authorization
     * URL, does the same while the scheme is required. A token, authorization, or OpenID URL
     * changed, or a bearer token's format, is only something clients must configure.
     */
    private fun schemeChanged(
        change: AnnotationChanged,
        ctx: ChangeContext,
        owner: NamespaceOwner,
    ): Verdict {
        val scheme = HttpSecurity.schemeOf(change.key)!!
        val required = ctx.requires(Side.OLD, owner.namespace.name, scheme)
        val label = annotationLabel(change)
        return when {
            change.key == "oauth2_authorization_url" &&
                (change.from == null || change.to == null) ->
                if (!required) Verdict.Compatible
                else
                    Verdict.Breaking(
                        "${change.path}: $label ${changeWord(change)}, so the oauth2 flow " +
                            "changes from ${flow(change.from)} to ${flow(change.to)}, which " +
                            "breaks clients that use the old flow",
                        "keep the old flow until no client uses it",
                    )
            change.from == null -> Verdict.Compatible
            change.to == null ->
                if (!required) Verdict.Compatible
                else
                    Verdict.Breaking(
                        "${change.path}: $label removed, so the $scheme scheme is gone while " +
                            "operations require it, which breaks clients that authenticate with it",
                        "keep the scheme until no operation requires it",
                    )
            change.key == "api_key" ->
                if (!required) Verdict.Compatible
                else
                    Verdict.Breaking(
                        "${change.path}: $label changed from ${quoted(change.from)} to " +
                            "${quoted(change.to)}, which breaks clients that send the key where " +
                            "it was",
                        "keep @openapi(api_key: ${quoted(change.from)})",
                    )
            change.key == "bearer" ->
                Verdict.Note(
                    "${change.path}: the bearer token format changed from " +
                        "${format(change.from)} to ${format(change.to)}",
                    "tell clients which token format to send",
                )
            else ->
                Verdict.Note(
                    "${change.path}: $label changed from ${quoted(change.from)} to " +
                        "${quoted(change.to)}; clients configured with the old URL must move",
                    "keep the old URL answering until every client uses the new one",
                )
        }
    }

    /** The oauth2 flow a schema line has with or without its [authorizationUrl]. */
    private fun flow(authorizationUrl: AnnotationValue?): String =
        if (authorizationUrl == null) "clientCredentials" else "authorizationCode"

    private fun quoted(value: AnnotationValue?): String =
        "\"${(value as? AnnotationValue.Str)?.value.orEmpty()}\""

    /** A bearer scheme's token format, `none` for a bare `@openapi(bearer)`. */
    private fun format(value: AnnotationValue?): String =
        (value as? AnnotationValue.Str)?.value?.ifEmpty { null } ?: "none"

    /**
     * `@openapi(header)` moves a field of an operation's request, response, or error body between
     * the body (or the query) and an HTTP header, so adding or removing it, or renaming the header
     * other than in letter case (header names ignore it), breaks clients that send or read the
     * field. On a model no OLD operation takes or answers with as a whole, it changes nothing.
     */
    private fun headerChanged(
        change: AnnotationChanged,
        ctx: ChangeContext,
        owner: FieldOwner,
    ): Verdict {
        val record = owner.record.qualifiedName
        if (!ctx.isRequest(Side.OLD, record) && !ctx.isResponse(Side.OLD, record))
            return Verdict.Compatible
        val from = (change.from as? AnnotationValue.Str)?.value
        val to = (change.to as? AnnotationValue.Str)?.value
        val field = owner.field.name
        return when {
            from == null ->
                Verdict.Breaking(
                    "${change.path}: @openapi(header) added, so field '$field' moves into the " +
                        "$to header, which breaks clients that send or read it where it was",
                    "add a new field for the header instead of moving this one",
                )
            to == null ->
                Verdict.Breaking(
                    "${change.path}: @openapi(header) removed, so field '$field' moves out of " +
                        "the $from header, which breaks clients that send or read the header",
                    "keep @openapi(header: \"$from\")",
                )
            from.equals(to, ignoreCase = true) -> Verdict.Compatible
            else ->
                Verdict.Breaking(
                    "${change.path}: @openapi(header) changed, so the $from header is renamed " +
                        "to $to, which breaks clients that send or read the old one",
                    "keep @openapi(header: \"$from\")",
                )
        }
    }

    private fun deprecationChanged(change: DeprecationChanged, ctx: ChangeContext): Verdict {
        val word =
            when (val owner = change.owner) {
                is ServiceOwner -> "service"
                is OperationOwner -> "operation"
                is DeclarationOwner -> return data(change, owner.decl.qualifiedName, ctx)
                is FieldOwner -> return data(change, owner.record.qualifiedName, ctx)
                is EnumValueOwner -> return data(change, owner.enum.qualifiedName, ctx)
                is UnionMemberOwner -> return data(change, owner.union.qualifiedName, ctx)
                is NamespaceOwner -> return Verdict.Compatible
            }
        return if (change.deprecated)
            Verdict.Note(
                "${change.path}: the $word is now deprecated; generated clients flag every call " +
                    "to it",
                "tell clients when the $word will be removed",
            )
        else
            Verdict.Note(
                "${change.path}: the $word is no longer deprecated",
                "tell clients that moved off it that it stays",
            )
    }

    /** `get /orders/{id}` as written, or the derived `post /<tag>/<operation>` when unbound. */
    private fun url(service: Service, op: Operation, ctx: ChangeContext): String =
        op.binding?.let(::bindingText) ?: derivedUrl(service, op, ctx)

    private fun derivedUrl(service: Service, op: Operation, ctx: ChangeContext): String =
        "post /${ctx.emittedName(target, ServiceOwner(service))}/${op.name}"
}
