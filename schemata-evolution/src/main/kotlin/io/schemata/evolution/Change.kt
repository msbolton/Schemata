package io.schemata.evolution

import io.schemata.core.ir.AnnotationValue
import io.schemata.core.ir.EnumType
import io.schemata.core.ir.EnumValue
import io.schemata.core.ir.Field
import io.schemata.core.ir.HttpBinding
import io.schemata.core.ir.Namespace
import io.schemata.core.ir.Operation
import io.schemata.core.ir.Payload
import io.schemata.core.ir.RecordType
import io.schemata.core.ir.Reserved
import io.schemata.core.ir.Service
import io.schemata.core.ir.TypeDecl
import io.schemata.core.ir.UnionMember
import io.schemata.core.ir.UnionType
import io.schemata.lang.Span

enum class Side {
    OLD,
    NEW,
}

/**
 * One difference between OLD and NEW. [path] is `ns.Decl.member`; [span] is on the NEW side, or OLD
 * for a removal.
 */
sealed interface Change {
    val path: String
    val span: Span
    val kind: String
}

data class NamespaceAdded(override val path: String, override val span: Span) : Change {
    override val kind = "namespace.added"
}

data class NamespaceRemoved(override val path: String, override val span: Span) : Change {
    override val kind = "namespace.removed"
}

data class DeclarationAdded(
    override val path: String,
    override val span: Span,
    val decl: TypeDecl,
) : Change {
    override val kind = "declaration.added"
}

data class DeclarationRemoved(
    override val path: String,
    override val span: Span,
    val decl: TypeDecl,
) : Change {
    override val kind = "declaration.removed"
}

data class DeclarationKindChanged(
    override val path: String,
    override val span: Span,
    val from: TypeDecl,
    val to: TypeDecl,
) : Change {
    override val kind = "declaration.kindChanged"
}

data class FieldAdded(
    override val path: String,
    override val span: Span,
    val record: RecordType,
    val field: Field,
) : Change {
    override val kind = "field.added"
}

data class FieldRemoved(
    override val path: String,
    override val span: Span,
    val record: RecordType,
    val field: Field,
) : Change {
    override val kind = "field.removed"
}

data class FieldRenamed(
    override val path: String,
    override val span: Span,
    val record: RecordType,
    val from: Field,
    val to: Field,
) : Change {
    override val kind = "field.renamed"
}

data class FieldTypeChanged(
    override val path: String,
    override val span: Span,
    val record: RecordType,
    val from: Field,
    val to: Field,
) : Change {
    override val kind = "field.typeChanged"
}

data class FieldNullabilityChanged(
    override val path: String,
    override val span: Span,
    val record: RecordType,
    val from: Field,
    val to: Field,
) : Change {
    override val kind = "field.nullabilityChanged"
}

data class FieldDefaultChanged(
    override val path: String,
    override val span: Span,
    val record: RecordType,
    val from: Field,
    val to: Field,
) : Change {
    override val kind = "field.defaultChanged"
}

/**
 * A bound or pattern changed somewhere in the field's type, at the top level or on a list element,
 * map key, or map value. [tightened] when any of them narrowed or gained or changed a pattern;
 * loosened otherwise.
 */
data class FieldRefinementChanged(
    override val path: String,
    override val span: Span,
    val record: RecordType,
    val from: Field,
    val to: Field,
    val tightened: Boolean,
) : Change {
    override val kind = "field.refinementChanged"
}

data class EnumValueAdded(
    override val path: String,
    override val span: Span,
    val enum: EnumType,
    val value: EnumValue,
) : Change {
    override val kind = "enumValue.added"
}

data class EnumValueRemoved(
    override val path: String,
    override val span: Span,
    val enum: EnumType,
    val value: EnumValue,
) : Change {
    override val kind = "enumValue.removed"
}

data class EnumValueRenamed(
    override val path: String,
    override val span: Span,
    val enum: EnumType,
    val from: EnumValue,
    val to: EnumValue,
) : Change {
    override val kind = "enumValue.renamed"
}

data class UnionMemberAdded(
    override val path: String,
    override val span: Span,
    val union: UnionType,
    val member: UnionMember,
) : Change {
    override val kind = "unionMember.added"
}

data class UnionMemberRemoved(
    override val path: String,
    override val span: Span,
    val union: UnionType,
    val member: UnionMember,
) : Change {
    override val kind = "unionMember.removed"
}

data class UnionMemberTypeChanged(
    override val path: String,
    override val span: Span,
    val union: UnionType,
    val from: UnionMember,
    val to: UnionMember,
) : Change {
    override val kind = "unionMember.typeChanged"
}

/** [owner] is the declaration or service whose reservations changed, as it stands on NEW's side. */
data class ReservedChanged(
    override val path: String,
    override val span: Span,
    val from: Reserved,
    val to: Reserved,
    val owner: Owner,
) : Change {
    override val kind = "reserved.changed"
}

data class ServiceAdded(override val path: String, override val span: Span, val service: Service) :
    Change {
    override val kind = "service.added"
}

data class ServiceRemoved(
    override val path: String,
    override val span: Span,
    val service: Service,
) : Change {
    override val kind = "service.removed"
}

data class OperationAdded(
    override val path: String,
    override val span: Span,
    val service: Service,
    val operation: Operation,
) : Change {
    override val kind = "operation.added"
}

/** [service] and [operation] as they stood on OLD's side. */
data class OperationRemoved(
    override val path: String,
    override val span: Span,
    val service: Service,
    val operation: Operation,
) : Change {
    override val kind = "operation.removed"
}

/** [service] is NEW's; [from] is the operation on OLD's side, [to] on NEW's. */
data class OperationRenamed(
    override val path: String,
    override val span: Span,
    val service: Service,
    val from: Operation,
    val to: Operation,
) : Change {
    override val kind = "operation.renamed"
}

/**
 * The request's type or streaming changed, or a request was added or taken away; [service] and
 * [operation] are NEW's.
 */
data class OperationRequestChanged(
    override val path: String,
    override val span: Span,
    val service: Service,
    val operation: Operation,
    val from: Payload?,
    val to: Payload?,
) : Change {
    override val kind = "operation.requestChanged"
}

/**
 * The response's type or streaming changed, or a response was added or taken away; [service] and
 * [operation] are NEW's.
 */
data class OperationResponseChanged(
    override val path: String,
    override val span: Span,
    val service: Service,
    val operation: Operation,
    val from: Payload?,
    val to: Payload?,
) : Change {
    override val kind = "operation.responseChanged"
}

/**
 * The HTTP binding was added, removed, or its verb or path changed; [service] and [operation] are
 * NEW's.
 */
data class OperationBindingChanged(
    override val path: String,
    override val span: Span,
    val service: Service,
    val operation: Operation,
    val from: HttpBinding?,
    val to: HttpBinding?,
) : Change {
    override val kind = "operation.bindingChanged"
}

/**
 * The element an annotation, deprecation, or doc change belongs to, as it stands on one side, so a
 * rulebook or renderer reads names off the element itself instead of re-resolving [Change.path].
 */
sealed interface Owner

data class NamespaceOwner(val namespace: Namespace) : Owner

data class DeclarationOwner(val decl: TypeDecl) : Owner

data class FieldOwner(val record: RecordType, val field: Field) : Owner

data class EnumValueOwner(val enum: EnumType, val value: EnumValue) : Owner

data class UnionMemberOwner(val union: UnionType, val member: UnionMember) : Owner

data class ServiceOwner(val service: Service) : Owner

data class OperationOwner(val service: Service, val operation: Operation) : Owner

/**
 * An annotation key added, removed, or changed on an element both sides share; [oldOwner] and
 * [newOwner] are that element on each side, so a rename and a pin added in the same step compare
 * the OLD element's emitted name with the NEW one's.
 */
data class AnnotationChanged(
    override val path: String,
    override val span: Span,
    val target: String,
    val key: String,
    val from: AnnotationValue?,
    val to: AnnotationValue?,
    val oldOwner: Owner,
    val newOwner: Owner,
) : Change {
    override val kind = "annotation.changed"
}

/** `@deprecated` added to or removed from [owner], the element as it stands on NEW's side. */
data class DeprecationChanged(
    override val path: String,
    override val span: Span,
    val deprecated: Boolean,
    val owner: Owner,
) : Change {
    override val kind = "deprecation.changed"
}

/** [owner] is the element whose doc changed, as it stands on NEW's side. */
data class DocChanged(override val path: String, override val span: Span, val owner: Owner) :
    Change {
    override val kind = "doc.changed"
}

/** `added`, `removed`, or `changed`, by which side of [change] holds a value. */
fun changeWord(change: AnnotationChanged): String =
    when {
        change.from == null -> "added"
        change.to == null -> "removed"
        else -> "changed"
    }

/** A model-level constraint's key in the `sql` annotations [Differ] derives: `@@unique(a, b)`. */
fun constraintKey(kind: String, columns: List<String>): String =
    "@@$kind(${columns.joinToString(", ")})"

/**
 * How the schema text spells the thing [change] adds, removes, or changes: `{ id }` or `@@id` for a
 * key, `{ unique }`, `{ index }`, `{ embed }`, `@@unique(a, b)`, or else `@<target>(<key>)`. The
 * SQL facts [Differ] derives from the language's own options carry the `sql` target's keys, so they
 * are spelled as the language writes them.
 */
fun annotationLabel(change: AnnotationChanged): String {
    if (change.target == "sql") {
        val embed = AnnotationValue.Name("embed")
        when {
            change.key.startsWith("@@") -> return change.key
            change.key == "key" ->
                return if (change.newOwner is DeclarationOwner) "@@id" else "{ id }"
            change.key == "unique" -> return "{ unique }"
            change.key == "index" -> return "{ index }"
            change.key == "strategy" &&
                ((change.from == null && change.to == embed) ||
                    (change.to == null && change.from == embed)) -> return "{ embed }"
        }
    }
    return "@${change.target}(${change.key})"
}

/**
 * Whether [change] touches only a back-reference: a virtual field added or removed, or changed
 * while it is virtual on both sides. A back-reference is emitted by no target, so every rulebook
 * passes such a change as compatible before judging anything else.
 */
fun touchesOnlyBackReference(change: Change): Boolean =
    when (change) {
        is FieldAdded -> change.field.virtual
        is FieldRemoved -> change.field.virtual
        is FieldRenamed -> change.from.virtual && change.to.virtual
        is FieldTypeChanged -> change.from.virtual && change.to.virtual
        is FieldNullabilityChanged -> change.from.virtual && change.to.virtual
        is FieldDefaultChanged -> change.from.virtual && change.to.virtual
        is FieldRefinementChanged -> change.from.virtual && change.to.virtual
        is AnnotationChanged -> virtual(change.oldOwner) && virtual(change.newOwner)
        is DeprecationChanged -> virtual(change.owner)
        is DocChanged -> virtual(change.owner)
        else -> false
    }

private fun virtual(owner: Owner): Boolean = owner is FieldOwner && owner.field.virtual
