package io.schemata.evolution

import io.schemata.core.ir.AnnotationValue
import io.schemata.core.ir.EnumType
import io.schemata.core.ir.EnumValue
import io.schemata.core.ir.Field
import io.schemata.core.ir.RecordType
import io.schemata.core.ir.Reserved
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

/** [tightened] when any bound narrowed or a pattern was added or changed; loosened otherwise. */
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

data class ReservedChanged(
    override val path: String,
    override val span: Span,
    val from: Reserved,
    val to: Reserved,
) : Change {
    override val kind = "reserved.changed"
}

data class AnnotationChanged(
    override val path: String,
    override val span: Span,
    val target: String,
    val key: String,
    val from: AnnotationValue?,
    val to: AnnotationValue?,
) : Change {
    override val kind = "annotation.changed"
}

data class DeprecationChanged(
    override val path: String,
    override val span: Span,
    val deprecated: Boolean,
) : Change {
    override val kind = "deprecation.changed"
}

data class DocChanged(override val path: String, override val span: Span) : Change {
    override val kind = "doc.changed"
}
