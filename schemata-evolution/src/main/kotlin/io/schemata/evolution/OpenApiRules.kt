package io.schemata.evolution

import io.schemata.core.ir.HttpBinding
import io.schemata.core.ir.Operation
import io.schemata.core.ir.Payload
import io.schemata.core.ir.QualifiedName
import io.schemata.core.ir.Service
import io.schemata.core.ir.service

/**
 * What each kind of [Change] means for a client generated from the old OpenAPI document: whether
 * every call it makes still reaches the same operation with the same request and response. A
 * record, enum, or union is only in that document when some OLD service's request or response
 * reaches it; such a declaration's changes are judged exactly as JSON Schema judges them, since its
 * component is the JSON Schema lowering's, named by the `@jsonschema` keys. Any other declaration's
 * changes are invisible to the document's clients.
 */
object OpenApiRules : Rulebook {
    override val target = "openapi"

    override fun classify(change: Change, ctx: ChangeContext): Verdict =
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
            is NamespaceAdded -> Verdict.Compatible
            is NamespaceRemoved -> namespaceRemoved(change, ctx)
            is DeclarationAdded -> Verdict.Compatible
            is DeclarationRemoved -> data(change, change.decl.qualifiedName, ctx)
            is DeclarationKindChanged -> data(change, change.from.qualifiedName, ctx)
            is FieldAdded -> data(change, change.record.qualifiedName, ctx)
            is FieldRemoved -> data(change, change.record.qualifiedName, ctx)
            is FieldRenamed -> data(change, change.record.qualifiedName, ctx)
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
        if (ctx.reachableFromServices(Side.OLD, decl)) JsonSchemaRules.classify(change, ctx)
        else Verdict.Compatible

    /** Only a namespace with services had a document; its declarations go with it. */
    private fun namespaceRemoved(change: NamespaceRemoved, ctx: ChangeContext): Verdict =
        if (ctx.old.namespaces.any { it.name == change.path && it.services.isNotEmpty() })
            Verdict.Breaking(
                "${change.path}: the namespace was removed breaks clients that call its " +
                    "services' operations",
                "keep the namespace's services until no client calls them",
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
        val oldService = oldService(change.service, ctx)
        val fromId = ctx.emittedName(target, OperationOwner(oldService, change.from))
        val toId = ctx.emittedName(target, OperationOwner(change.service, change.to))
        val fromUrl = url(oldService, change.from, ctx)
        val toUrl = url(change.service, change.to, ctx)
        val what =
            listOfNotNull(
                    "its operationId changes from $fromId to $toId".takeIf { fromId != toId },
                    "its URL changes from $fromUrl to $toUrl".takeIf { fromUrl != toUrl },
                )
                .joinToString(", and ")
        if (what.isEmpty()) return Verdict.Compatible
        return Verdict.Breaking(
            "${change.path}: the operation was renamed, so $what",
            if (fromId != toId) "pin the operationId with @openapi(name = \"$fromId\")"
            else "bind the operation to its old URL with $fromUrl",
        )
    }

    /**
     * A binding added or removed moves the operation to or from its derived URL, unless the binding
     * is that derived URL: a client sees only the verb and path, so the same URL either way is
     * compatible.
     */
    private fun bindingChanged(change: OperationBindingChanged, ctx: ChangeContext): Verdict {
        val oldService = oldService(change.service, ctx)
        val oldOp =
            oldService.operations.firstOrNull { it.ordinal == change.operation.ordinal }
                ?: change.operation
        val fromUrl = bindingText(change.from) ?: derivedUrl(oldService, oldOp, ctx)
        val toUrl = bindingText(change.to) ?: derivedUrl(change.service, change.operation, ctx)
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
     * the emitted name stays the same, as when a pin is added alongside a rename. The namespace's
     * keys (`version`, `server`) describe the document, not any operation.
     */
    private fun annotationChanged(change: AnnotationChanged, ctx: ChangeContext): Verdict =
        when (val owner = change.newOwner) {
            is ServiceOwner,
            is OperationOwner -> nameChanged(change, ctx, owner)
            is NamespaceOwner -> Verdict.Compatible
            is DeclarationOwner -> data(change, owner.decl.qualifiedName, ctx)
            is FieldOwner -> data(change, owner.record.qualifiedName, ctx)
            is EnumValueOwner -> data(change, owner.enum.qualifiedName, ctx)
            is UnionMemberOwner -> data(change, owner.union.qualifiedName, ctx)
        }

    private fun nameChanged(change: AnnotationChanged, ctx: ChangeContext, owner: Owner): Verdict {
        if (change.target != target || change.key != "name") return Verdict.Compatible
        val fromName = ctx.emittedName(target, change.oldOwner)
        val toName = ctx.emittedName(target, change.newOwner)
        if (fromName == toName) return Verdict.Compatible
        val what =
            if (owner is ServiceOwner)
                "the tag changes from $fromName to $toName, and with it every operationId it " +
                    "prefixes"
            else "the operationId changes from $fromName to $toName"
        return Verdict.Breaking(
            "${change.path}: @openapi(name) ${changeWord(change)}, so $what",
            "keep @openapi(name = \"$fromName\")",
        )
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

    private fun oldService(service: Service, ctx: ChangeContext): Service =
        ctx.old.service(service.qualifiedName) ?: service

    /** `get /orders/{id}` as written, or the derived `post /<tag>/<operation>` when unbound. */
    private fun url(service: Service, op: Operation, ctx: ChangeContext): String =
        bindingText(op.binding) ?: derivedUrl(service, op, ctx)

    private fun derivedUrl(service: Service, op: Operation, ctx: ChangeContext): String =
        "post /${ctx.emittedName(target, ServiceOwner(service))}/${op.name}"

    private fun bindingText(binding: HttpBinding?): String? =
        binding?.let { "${it.verb.lower} ${it.path}" }

    /** `Order`, `stream Order`, or `none`. */
    private fun payloadText(payload: Payload?): String =
        when {
            payload == null -> "none"
            payload.stream -> "stream ${payload.target.simpleName}"
            else -> payload.target.simpleName
        }
}
