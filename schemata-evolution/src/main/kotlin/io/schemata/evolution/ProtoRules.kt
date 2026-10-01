package io.schemata.evolution

import io.schemata.core.ir.Builtin
import io.schemata.core.ir.EnumType
import io.schemata.core.ir.Ref
import io.schemata.core.ir.Scalar
import io.schemata.core.ir.Type
import io.schemata.core.ir.kindWord
import io.schemata.target.TypeText

/** What each kind of [Change] means for a Protobuf consumer reading data under the old schema. */
object ProtoRules : Rulebook {
    override val target = "proto"

    override fun classify(change: Change, ctx: ChangeContext): Verdict =
        when (change) {
            is NamespaceAdded -> Verdict.Compatible
            is NamespaceRemoved ->
                Verdict.Breaking(
                    "${change.path}: the namespace was removed breaks consumers of its declarations",
                    "keep the namespace, even if its declarations move",
                )
            is DeclarationAdded -> Verdict.Compatible
            is DeclarationRemoved ->
                Verdict.Note(
                    "${change.path}: the declaration was removed; generated code loses this type",
                    "keep the type, or confirm nothing outside this schema still depends on it",
                )
            is DeclarationKindChanged ->
                Verdict.Breaking(
                    "${change.path}: kind changed from ${change.from.kindWord} to " +
                        "${change.to.kindWord} breaks generated code built against the old kind",
                    "introduce a new declaration instead of changing this one's kind",
                )
            is FieldAdded -> Verdict.Compatible
            is FieldRemoved -> fieldRemoved(change, ctx)
            is FieldRenamed -> fieldRenamed(change, ctx)
            is FieldTypeChanged -> fieldTypeChanged(change, ctx)
            is FieldNullabilityChanged -> fieldNullabilityChanged(change)
            is FieldDefaultChanged -> fieldDefaultChanged(change)
            is FieldRefinementChanged -> fieldRefinementChanged(change)
            is EnumValueAdded -> Verdict.Compatible
            is EnumValueRemoved -> enumValueRemoved(change, ctx)
            is EnumValueRenamed -> Verdict.Compatible
            is UnionMemberAdded -> Verdict.Compatible
            is UnionMemberRemoved ->
                Verdict.Breaking(
                    "${change.path}: union member removed breaks decoders of the old oneof case",
                    "add a new member instead of removing one",
                )
            is UnionMemberTypeChanged ->
                Verdict.Breaking(
                    "${change.path}: union member type changed breaks decoders of the old oneof case",
                    "add a new member instead of changing this one's type",
                )
            is ReservedChanged -> reservedChanged(change)
            is AnnotationChanged -> annotationChanged(change)
            is DeprecationChanged -> Verdict.Compatible
            is DocChanged -> Verdict.Compatible
        }

    private fun fieldRemoved(change: FieldRemoved, ctx: ChangeContext): Verdict {
        val ordinal = change.field.ordinal
        val name = change.field.name
        val help = "reserve #$ordinal and \"$name\" so they are not reused"
        return when (ctx.reservedInNew(change.record.qualifiedName, ordinal, name)) {
            ReservedStatus.BOTH -> Verdict.Compatible
            ReservedStatus.NAME_ONLY ->
                Verdict.Note(
                    "${change.path}: field removed; number $ordinal is free to be reused",
                    help,
                )
            ReservedStatus.ORDINAL_ONLY ->
                Verdict.Note(
                    "${change.path}: field removed; name '$name' is free to be reused",
                    help,
                )
            ReservedStatus.NEITHER ->
                Verdict.Note(
                    "${change.path}: field removed; number $ordinal and name '$name' are free to be reused",
                    help,
                )
        }
    }

    private fun fieldRenamed(change: FieldRenamed, ctx: ChangeContext): Verdict {
        val fromName = ctx.emittedFieldName(target, change.from)
        val toName = ctx.emittedFieldName(target, change.to)
        if (fromName == toName) return Verdict.Compatible
        return Verdict.Note(
            "${change.path}: field renamed from '${change.from.name}' to '${change.to.name}'; " +
                "this changes the JSON mapping",
            "pin the emitted name with @proto(name = \"$fromName\")",
        )
    }

    private fun fieldTypeChanged(change: FieldTypeChanged, ctx: ChangeContext): Verdict {
        val verdict = resolvedTypeVerdict(ctx, change.from.type, change.to.type)
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
     * A reference is resolved against the surrounding schemas before falling back to
     * [TypeCompat.proto], since only a schema lookup can tell an enum from a record or union
     * reference: two enum references are compatible regardless of which enum, and an enum reference
     * paired with `int32` is compatible (the only scalar an enum's wire format matches). A record
     * or union reference is never compatible with a scalar.
     */
    private fun resolvedTypeVerdict(ctx: ChangeContext, from: Type, to: Type): Verdict {
        if (from is Ref && to is Ref) {
            val fromIsEnum = ctx.old.lookupOrNull(from.target) is EnumType
            val toIsEnum = ctx.new.lookupOrNull(to.target) is EnumType
            if (fromIsEnum && toIsEnum) return Verdict.Compatible
        }
        if (
            from is Ref &&
                to is Scalar &&
                to.builtin == Builtin.INT32 &&
                ctx.old.lookupOrNull(from.target) is EnumType
        ) {
            return Verdict.Compatible
        }
        if (
            to is Ref &&
                from is Scalar &&
                from.builtin == Builtin.INT32 &&
                ctx.new.lookupOrNull(to.target) is EnumType
        ) {
            return Verdict.Compatible
        }
        return TypeCompat.proto(from, to)
    }

    private fun fieldNullabilityChanged(change: FieldNullabilityChanged): Verdict =
        if (change.from.nullable && !change.to.nullable)
            Verdict.Note(
                "${change.path}: the field became non-null; proto cannot tell an absent value " +
                    "from the zero value",
                "keep the field nullable",
            )
        else Verdict.Compatible

    private fun fieldRefinementChanged(change: FieldRefinementChanged): Verdict =
        if (change.tightened)
            Verdict.Note(
                "${change.path}: the refinement was tightened; proto does not enforce refinements," +
                    " so old values outside the new bound can still arrive",
                "validate incoming values separately; proto will not reject them",
            )
        else Verdict.Compatible

    private fun fieldDefaultChanged(change: FieldDefaultChanged): Verdict {
        val what =
            when {
                change.from.default == null -> "a default was added"
                change.to.default == null -> "a default was removed"
                else -> "the default changed"
            }
        return Verdict.Note(
            "${change.path}: $what; proto does not transmit defaults, so only newly generated " +
                "code applies it",
            "apply the new default in application code, not the wire format",
        )
    }

    private fun enumValueRemoved(change: EnumValueRemoved, ctx: ChangeContext): Verdict {
        val status =
            ctx.reservedInNew(change.enum.qualifiedName, change.value.ordinal, change.value.name)
        return if (status == ReservedStatus.BOTH)
            Verdict.Note(
                "${change.path}: enum value removed and reserved; old senders of this value " +
                    "become unrecognized",
                "treat an unrecognized enum value as unknown in generated code",
            )
        else
            Verdict.Breaking(
                "${change.path}: enum value removed breaks old senders of this value",
                "reserve its number and name so they are not reused",
            )
    }

    private fun reservedChanged(change: ReservedChanged): Verdict {
        val removed =
            change.from.names.any { it !in change.to.names } ||
                change.from.ordinals.any { range -> range.any { it !in change.to } }
        return if (removed)
            Verdict.Note(
                "${change.path}: a reservation was removed; the freed number or name could be " +
                    "reused later",
                "keep old reservations even after the numbers are no longer used",
            )
        else Verdict.Compatible
    }

    private fun annotationChanged(change: AnnotationChanged): Verdict =
        when {
            change.target != "proto" -> Verdict.Compatible
            change.key == "package" ->
                Verdict.Breaking(
                    "${change.path}: the proto package changed breaks consumers that pin the " +
                        "fully-qualified message name",
                    "avoid changing @proto(package) once published",
                )
            change.key == "name" ->
                Verdict.Note(
                    "${change.path}: the proto name changed; this changes the wire type's name " +
                        "used by reflection and Any",
                    "keep @proto(name) stable once published",
                )
            else -> Verdict.Compatible
        }
}
