package io.schemata.evolution

import io.schemata.core.ir.Builtin
import io.schemata.core.ir.EnumType
import io.schemata.core.ir.Ref
import io.schemata.core.ir.Scalar
import io.schemata.core.ir.Type
import io.schemata.core.ir.kindWord

/** What each kind of [Change] means for a Protobuf consumer reading data under the old schema. */
object ProtoRules : Rulebook {
    override val target = "proto"

    override fun classify(change: Change, ctx: ChangeContext): Verdict =
        when (change) {
            is NamespaceAdded -> Verdict.Compatible
            is NamespaceRemoved -> namespaceRemoved(change, ctx)
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
            is ReservedChanged ->
                if (change.owner is ServiceOwner) Verdict.Compatible else reservedChanged(change)
            is AnnotationChanged -> annotationChanged(change, ctx)
            is DeprecationChanged -> Verdict.Compatible
            is DocChanged -> Verdict.Compatible
            // a .proto file carries no services, so nothing about one reaches its consumers
            is ServiceAdded,
            is ServiceRemoved,
            is OperationAdded,
            is OperationRemoved,
            is OperationRenamed,
            is OperationRequestChanged,
            is OperationResponseChanged,
            is OperationBindingChanged -> Verdict.Compatible
        }

    /** Judged the way removing each of its declarations one by one would be: a note per type. */
    private fun namespaceRemoved(change: NamespaceRemoved, ctx: ChangeContext): Verdict =
        if (ctx.declarationsOf(Side.OLD, change.path).isEmpty()) Verdict.Compatible
        else
            Verdict.Note(
                "${change.path}: the namespace was removed; generated code loses its declarations",
                "keep the types, or confirm nothing outside this schema still depends on them",
            )

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

    /**
     * Judged on the wire alone, then, when the wire still agrees, noted if a bound or pattern was
     * tightened anywhere in the type, since proto carries no refinements to reject old values.
     */
    private fun fieldTypeChanged(change: FieldTypeChanged, ctx: ChangeContext): Verdict {
        val verdict = resolvedTypeVerdict(ctx, change.from.type, change.to.type)
        if (
            verdict is Verdict.Compatible && refinementsTightened(change.from.type, change.to.type)
        ) {
            return wrapTypeVerdict(
                change,
                Verdict.Note(
                    "the new bound is tighter, and proto does not enforce it",
                    "validate incoming values separately; proto will not reject them",
                ),
            )
        }
        return wrapTypeVerdict(change, verdict)
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
        when {
            !change.from.nullable || change.to.nullable -> Verdict.Compatible
            change.to.default != null ->
                Verdict.Note(
                    "${change.path}: the field became non-null with a default; proto does not " +
                        "carry the default, so an absent value still decodes as the zero value",
                    "apply the default in application code, not the wire format",
                )
            else ->
                Verdict.Note(
                    "${change.path}: the field became non-null; proto cannot tell an absent " +
                        "value from the zero value",
                    "keep the field nullable",
                )
        }

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

    private fun annotationChanged(change: AnnotationChanged, ctx: ChangeContext): Verdict =
        when {
            change.target != "proto" -> Verdict.Compatible
            change.key == "package" ->
                Verdict.Breaking(
                    "${change.path}: the proto package changed breaks consumers that pin the " +
                        "fully-qualified message name",
                    "avoid changing @proto(package) once published",
                )
            change.key == "name" -> nameAnnotationChanged(change, ctx)
            else -> Verdict.Compatible
        }

    /**
     * A `@proto(name)` override added, changed, or removed, judged by the emitted name of the OLD
     * element against the NEW one, so a pin added in the same step as a rename (which keeps the
     * emitted name) is compatible. A field's emitted name moves the JSON mapping; a declaration's
     * moves the type name reflection and `Any` use; an enum value's emitted name does not matter on
     * the wire at all.
     */
    private fun nameAnnotationChanged(change: AnnotationChanged, ctx: ChangeContext): Verdict {
        val fromName = ctx.emittedName(target, change.oldOwner)
        val toName = ctx.emittedName(target, change.newOwner)
        if (fromName == toName) return Verdict.Compatible
        return when (change.newOwner) {
            is FieldOwner ->
                Verdict.Note(
                    "${change.path}: the emitted name changed from '$fromName' to '$toName'; " +
                        "this changes the JSON mapping",
                    "pin the emitted name with @proto(name = \"$fromName\")",
                )
            is DeclarationOwner ->
                Verdict.Note(
                    "${change.path}: the proto name changed from '$fromName' to '$toName'; this " +
                        "changes the type name reflection and Any use",
                    "keep @proto(name) stable once published",
                )
            is NamespaceOwner,
            is EnumValueOwner,
            is UnionMemberOwner,
            is ServiceOwner,
            is OperationOwner -> Verdict.Compatible
        }
    }
}
