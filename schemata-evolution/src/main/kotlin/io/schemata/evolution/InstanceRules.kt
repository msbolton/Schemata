package io.schemata.evolution

import io.schemata.core.ir.ListOf
import io.schemata.core.ir.MapOf
import io.schemata.core.ir.QualifiedName
import io.schemata.core.ir.Ref
import io.schemata.core.ir.Scalar
import io.schemata.core.ir.Type
import io.schemata.core.ir.TypeDecl
import io.schemata.core.ir.kindWord
import io.schemata.target.TypeText

/**
 * What a document-shaped target (XSD, JSON Schema) means for a document produced under the old
 * schema: whether it still validates against the new one. Every rule here is the same for both
 * targets except [removedFieldBreaks] (a removed field is always unexpected on XSD, but only on a
 * closed JSON Schema record) and [removedDeclarationBreaks] (XSD only cares when the declaration
 * was reachable as a root element; every JSON Schema definition is addressable).
 */
class InstanceRules(
    override val target: String,
    private val removedFieldBreaks: (ChangeContext, QualifiedName) -> Boolean,
    private val removedDeclarationBreaks: (ChangeContext, TypeDecl) -> Boolean,
) : Rulebook {
    override fun classify(change: Change, ctx: ChangeContext): Verdict =
        when (change) {
            is NamespaceAdded -> Verdict.Compatible
            is NamespaceRemoved ->
                Verdict.Breaking(
                    "${change.path}: the namespace was removed breaks consumers of its declarations",
                    "keep the namespace, even if its declarations move",
                )
            is DeclarationAdded -> Verdict.Compatible
            is DeclarationRemoved -> declarationRemoved(change, ctx)
            is DeclarationKindChanged ->
                Verdict.Breaking(
                    "${change.path}: kind changed from ${change.from.kindWord} to " +
                        "${change.to.kindWord} breaks documents validated against the old kind",
                    "introduce a new declaration instead of changing this one's kind",
                )
            is FieldAdded -> fieldAdded(change)
            is FieldRemoved -> fieldRemoved(change, ctx)
            is FieldRenamed -> fieldRenamed(change, ctx)
            is FieldTypeChanged -> fieldTypeChanged(change)
            is FieldNullabilityChanged -> fieldNullabilityChanged(change)
            is FieldDefaultChanged -> fieldDefaultChanged(change)
            is FieldRefinementChanged -> fieldRefinementChanged(change)
            is EnumValueAdded -> Verdict.Compatible
            is EnumValueRemoved ->
                Verdict.Breaking(
                    "${change.path}: the enum value was removed breaks documents that still carry it",
                    "reserve the value instead of removing it",
                )
            is EnumValueRenamed -> enumValueRenamed(change, ctx)
            is UnionMemberAdded -> Verdict.Compatible
            is UnionMemberRemoved ->
                Verdict.Breaking(
                    "${change.path}: the union member was removed breaks documents whose " +
                        "discriminator selects the old case",
                    "add a new member instead of removing one",
                )
            is UnionMemberTypeChanged ->
                Verdict.Breaking(
                    "${change.path}: the union member's type changed breaks documents whose " +
                        "discriminator selects the old case",
                    "add a new member instead of changing this one's type",
                )
            is ReservedChanged -> Verdict.Compatible
            is AnnotationChanged -> annotationChanged(change)
            is DeprecationChanged -> Verdict.Compatible
            is DocChanged -> Verdict.Compatible
        }

    private fun fieldAdded(change: FieldAdded): Verdict {
        val required = !change.field.nullable && change.field.default == null
        return if (required)
            Verdict.Breaking(
                "${change.path}: a required field was added breaks old documents, which have no " +
                    "value for it",
                "add the field as nullable or with a default",
            )
        else Verdict.Compatible
    }

    private fun fieldRemoved(change: FieldRemoved, ctx: ChangeContext): Verdict {
        if (!removedFieldBreaks(ctx, change.record.qualifiedName)) return Verdict.Compatible
        return Verdict.Breaking(
            "${change.path}: the field was removed breaks old documents that still carry it",
            if (target == "xsd") "keep the field; an XSD document always carries every element"
            else "keep the field, or mark the record @jsonschema(open)",
        )
    }

    private fun fieldRenamed(change: FieldRenamed, ctx: ChangeContext): Verdict {
        val fromName = ctx.emittedFieldName(target, change.from)
        val toName = ctx.emittedFieldName(target, change.to)
        if (fromName == toName) return Verdict.Compatible
        return Verdict.Breaking(
            "${change.path}: the field was renamed from '$fromName' to '$toName' breaks old " +
                "documents that still use the old name",
            "pin the emitted name with @$target(name = \"$fromName\")",
        )
    }

    private fun fieldTypeChanged(change: FieldTypeChanged): Verdict {
        val verdict = resolvedTypeVerdict(change.from.type, change.to.type)
        val what =
            "type changed from ${TypeText.of(change.from.type)} to ${TypeText.of(change.to.type)}"
        return when (verdict) {
            is Verdict.Compatible -> verdict
            is Verdict.Note ->
                Verdict.Note("${change.path}: $what; ${verdict.message}", verdict.help)
            is Verdict.Breaking ->
                Verdict.Breaking("${change.path}: $what breaks ${verdict.message}", verdict.help)
        }
    }

    /**
     * A scalar change is judged by [TypeCompat.instanceWidening]; a reference change is compatible
     * only when it still names the same declaration; a list or map keeps its element nullability
     * check; anything else mixing a scalar with a list, map, or reference is breaking outright.
     */
    private fun resolvedTypeVerdict(from: Type, to: Type): Verdict {
        val help = "add a new field instead of changing this one's type"
        return when {
            from is Scalar && to is Scalar ->
                if (TypeCompat.instanceWidening(from, to)) Verdict.Compatible
                else Verdict.Breaking("existing values that no longer fit the narrower type", help)
            from is Ref && to is Ref ->
                if (from.target == to.target) Verdict.Compatible
                else Verdict.Breaking("documents backed by an incompatible type", help)
            from is ListOf && to is ListOf -> listTypeVerdict(from, to, help)
            from is MapOf && to is MapOf -> mapTypeVerdict(from, to, help)
            else -> Verdict.Breaking("documents backed by an incompatible type", help)
        }
    }

    private fun listTypeVerdict(from: ListOf, to: ListOf, help: String): Verdict {
        val sameElement = typeCore(from.element) == typeCore(to.element)
        return if (sameElement && !from.nullableElement && to.nullableElement) Verdict.Compatible
        else Verdict.Breaking("documents backed by an incompatible type", help)
    }

    private fun mapTypeVerdict(from: MapOf, to: MapOf, help: String): Verdict {
        if (typeCore(from.key) != typeCore(to.key))
            return Verdict.Breaking("existing entries keyed by the old type", help)
        val sameValue = typeCore(from.value) == typeCore(to.value)
        return if (sameValue && !from.nullableValue && to.nullableValue) Verdict.Compatible
        else Verdict.Breaking("documents backed by an incompatible type", help)
    }

    private fun fieldNullabilityChanged(change: FieldNullabilityChanged): Verdict =
        if (change.from.nullable && !change.to.nullable)
            Verdict.Breaking(
                "${change.path}: the field became required breaks old documents that store a " +
                    "null value",
                "keep the field nullable, or backfill every old document first",
            )
        else Verdict.Compatible

    private fun fieldRefinementChanged(change: FieldRefinementChanged): Verdict =
        if (change.tightened)
            Verdict.Breaking(
                "${change.path}: the refinement was tightened breaks old documents whose value " +
                    "falls outside the new bound",
                "validate and migrate existing documents before tightening the constraint",
            )
        else Verdict.Compatible

    private fun fieldDefaultChanged(change: FieldDefaultChanged): Verdict {
        if (change.to.default == null) {
            if (change.to.nullable || target == "xsd") return Verdict.Compatible
            return Verdict.Breaking(
                "${change.path}: the default was removed from a required field breaks old " +
                    "documents that omit it, which is now rejected by required",
                "keep a default, or supply the field explicitly in every document",
            )
        }
        return if (target == "xsd")
            Verdict.Note(
                "${change.path}: a default was added or changed; it is applied to empty elements " +
                    "only, not omitted ones",
                "supply the value explicitly where an old document omitted the element",
            )
        else Verdict.Compatible
    }

    private fun enumValueRenamed(change: EnumValueRenamed, ctx: ChangeContext): Verdict {
        val fromName = ctx.emittedValueName(target, change.enum, change.from)
        val toName = ctx.emittedValueName(target, change.enum, change.to)
        if (fromName == toName) return Verdict.Compatible
        return Verdict.Breaking(
            "${change.path}: the enum value was renamed from '$fromName' to '$toName' breaks old " +
                "documents that still store the old value",
            "add a new value instead of renaming this one",
        )
    }

    private fun declarationRemoved(change: DeclarationRemoved, ctx: ChangeContext): Verdict {
        if (removedDeclarationBreaks(ctx, change.decl))
            return Verdict.Breaking(
                "${change.path}: the declaration was removed breaks documents validated against it",
                "keep the declaration, or confirm nothing outside this schema still depends on it",
            )
        return Verdict.Note(
            "${change.path}: the declaration was removed; it was never reachable as a root element",
            "confirm nothing outside this schema still depends on it",
        )
    }

    private fun annotationChanged(change: AnnotationChanged): Verdict {
        if (change.target != target) return Verdict.Compatible
        val identityKey = if (target == "xsd") "namespace" else "id"
        if (change.key != identityKey) return Verdict.Compatible
        return Verdict.Breaking(
            "${change.path}: @$target($identityKey) changed breaks documents that reference the " +
                "old identifier",
            "avoid changing @$target($identityKey) once published",
        )
    }
}
