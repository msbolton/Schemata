package io.schemata.evolution

import io.schemata.core.ir.AnnotationValue
import io.schemata.core.ir.QualifiedName
import io.schemata.core.ir.RecordType
import io.schemata.core.ir.TypeDecl
import io.schemata.core.ir.kindWord

/**
 * What a document-shaped target (XSD, JSON Schema) means for a document produced under the old
 * schema: whether it still validates against the new one. The two targets share every rule except
 * [removedFieldBreaks] (a removed field is always unexpected on XSD, but only on a closed JSON
 * Schema record), [removedDeclarationBreaks] (XSD only cares when the declaration was reachable as
 * a root element; every JSON Schema definition is addressable), and the few cells where the two
 * lowerings differ, which branch on [target]: XSD has no explicit null, applies a default only to
 * an empty element, and names a global element after a root record.
 */
class InstanceRules(
    override val target: String,
    private val removedFieldBreaks: (ChangeContext, QualifiedName) -> Boolean,
    private val removedDeclarationBreaks: (ChangeContext, TypeDecl) -> Boolean,
) : Rulebook {
    override fun classify(change: Change, ctx: ChangeContext): Verdict =
        when (change) {
            is NamespaceAdded -> Verdict.Compatible
            is NamespaceRemoved -> namespaceRemoved(change, ctx)
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
            is FieldTypeChanged -> fieldTypeChanged(change, ctx)
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
            is AnnotationChanged -> annotationChanged(change, ctx)
            is DeprecationChanged -> Verdict.Compatible
            is DocChanged -> Verdict.Compatible
        }

    /**
     * Judged the way removing each of its declarations one by one would be: breaking when any one
     * of them breaks, else a note when it had any declarations at all.
     */
    private fun namespaceRemoved(change: NamespaceRemoved, ctx: ChangeContext): Verdict {
        val declarations = ctx.declarationsOf(Side.OLD, change.path)
        return when {
            declarations.any { removedDeclarationBreaks(ctx, it) } ->
                Verdict.Breaking(
                    "${change.path}: the namespace was removed breaks documents validated " +
                        "against its declarations",
                    "keep the namespace, or confirm nothing outside this schema still depends " +
                        "on it",
                )
            declarations.isNotEmpty() ->
                Verdict.Note(
                    "${change.path}: the namespace was removed; none of its declarations was " +
                        "reachable as a root element",
                    "confirm nothing outside this schema still depends on it",
                )
            else -> Verdict.Compatible
        }
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

    private fun fieldTypeChanged(change: FieldTypeChanged, ctx: ChangeContext): Verdict =
        wrapTypeVerdict(change, typeVerdict(ctx).of(change.from.type, change.to.type))

    /**
     * Scalars widen by [TypeCompat.instanceWidening]; a reference is compatible only while it names
     * the same declaration.
     */
    private fun typeVerdict(ctx: ChangeContext) =
        StructuralTypeVerdict(
            TypeCompat::instanceWidening,
            holders = "old documents",
            incompatible = "the new type cannot hold the old values",
            help = "add a new field instead of changing this one's type",
        ) { from, to ->
            if (from.target == to.target) Verdict.Compatible
            else
                Verdict.Breaking(
                    "old documents: the new type cannot hold the old values",
                    "add a new field instead of changing this one's type",
                )
        }

    /**
     * Nullable to non-null breaks a document that stored a null, except on XSD when the field now
     * has a default: XSD has no explicit null and the element keeps `minOccurs = 0`, so every old
     * document still validates. JSON Schema writes `null` into a nullable field's type, so an old
     * document may carry one.
     */
    private fun fieldNullabilityChanged(change: FieldNullabilityChanged): Verdict =
        when {
            !change.from.nullable || change.to.nullable -> Verdict.Compatible
            target == "xsd" && change.to.default != null -> Verdict.Compatible
            target == "xsd" ->
                Verdict.Breaking(
                    "${change.path}: the field became required breaks old documents that omit " +
                        "it: the element becomes minOccurs = 1",
                    "keep the field nullable, give it a default, or backfill every old document",
                )
            else ->
                Verdict.Breaking(
                    "${change.path}: the field became non-null breaks old documents that store " +
                        "a null value",
                    "keep the field nullable, or backfill every old document first",
                )
        }

    private fun fieldRefinementChanged(change: FieldRefinementChanged): Verdict =
        if (change.tightened)
            Verdict.Breaking(
                "${change.path}: the refinement was tightened breaks old documents whose value " +
                    "falls outside the new bound",
                "validate and migrate existing documents before tightening the constraint",
            )
        else Verdict.Compatible

    /**
     * Removing a non-null field's default makes it required on both targets: XSD emits the element
     * with `minOccurs = 1` (or the attribute as `required`), JSON Schema lists it in `required`.
     */
    private fun fieldDefaultChanged(change: FieldDefaultChanged): Verdict {
        if (change.to.default == null) {
            if (change.to.nullable) return Verdict.Compatible
            val how =
                if (target == "xsd") "the element becomes minOccurs = 1, or the attribute required"
                else "the property becomes required"
            return Verdict.Breaking(
                "${change.path}: the default was removed from a non-null field breaks old " +
                    "documents that omit it: $how",
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
        val fromName = ctx.emittedValueName(target, change.from)
        val toName = ctx.emittedValueName(target, change.to)
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

    /**
     * Only this target's own keys matter: the identity key, a name override, and the representation
     * keys each target alone defines (`attribute` and `root` on XSD, `open` on JSON Schema). Any
     * other key of this target does not change what a document must look like.
     */
    private fun annotationChanged(change: AnnotationChanged, ctx: ChangeContext): Verdict {
        if (change.target != target) return Verdict.Compatible
        val xsd = target == "xsd"
        val identityKey = if (xsd) "namespace" else "id"
        return when {
            change.key == identityKey -> identityChanged(change, identityKey)
            change.key == "name" -> nameAnnotationChanged(change, ctx)
            xsd && change.key == "attribute" -> attributeChanged(change)
            xsd && change.key == "root" -> rootChanged(change, ctx)
            !xsd && change.key == "open" -> openChanged(change)
            else -> Verdict.Compatible
        }
    }

    private fun identityChanged(change: AnnotationChanged, identityKey: String): Verdict =
        Verdict.Breaking(
            "${change.path}: @$target($identityKey) ${changeWord(change)} breaks documents that " +
                "reference the old identifier",
            "avoid changing @$target($identityKey) once published",
        )

    /**
     * A `name` override added, changed, or removed, judged by the emitted name of the OLD element
     * against the NEW one, so a pin added in the same step as a rename (which keeps the emitted
     * name) is compatible. On a declaration, a new emitted name renames XSD's global element (only
     * a root record has one; otherwise just the type name moves, a note) and JSON Schema's `$defs`
     * key, which every document can address.
     */
    private fun nameAnnotationChanged(change: AnnotationChanged, ctx: ChangeContext): Verdict {
        val fromName = ctx.emittedName(target, change.oldOwner)
        val toName = ctx.emittedName(target, change.newOwner)
        if (fromName == toName) return Verdict.Compatible
        val owner = change.newOwner
        if (owner is DeclarationOwner)
            return declarationNameChanged(change, ctx, owner, fromName, toName)
        return Verdict.Breaking(
            "${change.path}: the emitted name changed from '$fromName' to '$toName' breaks old " +
                "documents that still use the old name",
            "pin the emitted name with @$target(name = \"$fromName\")",
        )
    }

    private fun declarationNameChanged(
        change: AnnotationChanged,
        ctx: ChangeContext,
        owner: DeclarationOwner,
        fromName: String,
        toName: String,
    ): Verdict {
        val help = "pin the emitted name with @$target(name = \"$fromName\")"
        if (target != "xsd")
            return Verdict.Breaking(
                "${change.path}: the emitted name changed from '$fromName' to '$toName' breaks " +
                    "documents that reference the old \$defs key",
                help,
            )
        if (owner.decl is RecordType && ctx.isRoot(Side.OLD, owner.decl.qualifiedName))
            return Verdict.Breaking(
                "${change.path}: the emitted name changed from '$fromName' to '$toName' breaks " +
                    "old documents whose root element has the old name",
                help,
            )
        return Verdict.Note(
            "${change.path}: the emitted name changed from '$fromName' to '$toName'; the type " +
                "name moves, but it was never reachable as a root element",
            help,
        )
    }

    /** `@xsd(attribute)` added or removed always changes how the element is serialized. */
    private fun attributeChanged(change: AnnotationChanged): Verdict {
        val name = change.path.substringAfterLast(".")
        val becomesAttribute = change.to != null
        val what =
            if (becomesAttribute) "element '$name' becomes an attribute"
            else "attribute '$name' becomes an element"
        return Verdict.Breaking(
            "${change.path}: $what breaks old documents that carry it the old way",
            "avoid changing @xsd(attribute) once published",
        )
    }

    /** `@xsd(root = false)` only matters when it takes away a root element OLD actually had. */
    private fun rootChanged(change: AnnotationChanged, ctx: ChangeContext): Verdict {
        val qn = (change.newOwner as? DeclarationOwner)?.decl?.qualifiedName
        if (qn == null || !ctx.isRoot(Side.OLD, qn) || ctx.isRoot(Side.NEW, qn)) {
            return Verdict.Compatible
        }
        return Verdict.Breaking(
            "${change.path}: @xsd(root = false) was added breaks validation against the old root " +
                "element",
            "keep the declaration reachable as a root element, or confirm nothing validates " +
                "against it directly",
        )
    }

    /** `@jsonschema(open)` only matters when it is taken away: a closed record rejects extras. */
    private fun openChanged(change: AnnotationChanged): Verdict =
        if (change.from is AnnotationValue.Flag && change.to == null)
            Verdict.Breaking(
                "${change.path}: @jsonschema(open) was removed breaks old documents whose extra " +
                    "properties are now rejected",
                "keep the record open, or confirm no old document carries extra properties",
            )
        else Verdict.Compatible
}
