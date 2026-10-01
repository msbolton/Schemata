package io.schemata.evolution

import io.schemata.core.ir.AnnotationValue
import io.schemata.core.ir.EnumType
import io.schemata.core.ir.Field
import io.schemata.core.ir.ListOf
import io.schemata.core.ir.MapOf
import io.schemata.core.ir.RecordType
import io.schemata.core.ir.Ref
import io.schemata.core.ir.Scalar
import io.schemata.core.ir.Schema
import io.schemata.core.ir.Type
import io.schemata.core.ir.TypeDecl
import io.schemata.core.ir.kindWord
import io.schemata.core.ir.selfAndNested
import io.schemata.target.Names
import io.schemata.target.TypeText

/**
 * What each kind of [Change] means for Postgres: whether data already in a table survives the DDL
 * change without loss and without a failing constraint. Unlike wire formats, a Postgres column
 * either keeps every existing row readable and valid or it does not, so this rulebook never returns
 * [Verdict.Note].
 */
object SqlRules : Rulebook {
    override val target = "sql"

    override fun classify(change: Change, ctx: ChangeContext): Verdict =
        when (change) {
            is NamespaceAdded -> Verdict.Compatible
            is NamespaceRemoved -> namespaceRemoved(change, ctx)
            is DeclarationAdded -> Verdict.Compatible
            is DeclarationRemoved -> declarationRemoved(change, ctx)
            is DeclarationKindChanged ->
                Verdict.Breaking(
                    "${change.path}: kind changed from ${change.from.kindWord} to " +
                        "${change.to.kindWord} breaks the table or CHECK built for the old kind",
                    "introduce a new declaration instead of changing this one's kind",
                )
            is FieldAdded -> fieldAdded(change)
            is FieldRemoved -> fieldRemoved(change)
            is FieldRenamed -> fieldRenamed(change, ctx)
            is FieldTypeChanged -> fieldTypeChanged(change, ctx)
            is FieldNullabilityChanged -> fieldNullabilityChanged(change)
            is FieldDefaultChanged -> fieldDefaultChanged(change)
            is FieldRefinementChanged -> fieldRefinementChanged(change)
            is EnumValueAdded -> Verdict.Compatible
            is EnumValueRemoved ->
                Verdict.Breaking(
                    "${change.path}: the enum value was removed breaks the CHECK constraint for " +
                        "rows that still store it",
                    "reserve the value instead of removing it",
                )
            is EnumValueRenamed -> enumValueRenamed(change, ctx)
            is UnionMemberAdded -> Verdict.Compatible
            is UnionMemberRemoved ->
                Verdict.Breaking(
                    "${change.path}: the union member was removed breaks rows whose discriminator " +
                        "selects the old case",
                    "add a new member instead of removing one",
                )
            is UnionMemberTypeChanged ->
                Verdict.Breaking(
                    "${change.path}: the union member's type changed breaks rows whose " +
                        "discriminator selects the old case",
                    "add a new member instead of changing this one's type",
                )
            is ReservedChanged -> Verdict.Compatible
            is AnnotationChanged -> annotationChanged(change, ctx)
            is DeprecationChanged -> Verdict.Compatible
            is DocChanged -> Verdict.Compatible
        }

    private fun namespaceRemoved(change: NamespaceRemoved, ctx: ChangeContext): Verdict {
        val removed = ctx.old.namespaces.firstOrNull { it.name == change.path }
        val declarations = removed?.declarations.orEmpty().flatMap { it.selfAndNested() }
        return if (declarations.any { ctx.hasTable(Side.OLD, it.qualifiedName) })
            Verdict.Breaking(
                "${change.path}: the namespace was removed breaks tables backing its declarations",
                "keep the namespace, even if its declarations move",
            )
        else Verdict.Compatible
    }

    private fun fieldAdded(change: FieldAdded): Verdict {
        val required = !change.field.nullable && change.field.default == null
        return if (required)
            Verdict.Breaking(
                "${change.path}: a required column was added breaks existing rows, which have no " +
                    "value for it",
                "add the column as nullable or with a default",
            )
        else Verdict.Compatible
    }

    private fun fieldRemoved(change: FieldRemoved): Verdict =
        Verdict.Breaking(
            "${change.path}: the column was dropped breaks rows that still hold its data",
            "migrate the column's data elsewhere before dropping it",
        )

    private fun fieldRenamed(change: FieldRenamed, ctx: ChangeContext): Verdict {
        val fromName = ctx.emittedFieldName(target, change.from)
        val toName = ctx.emittedFieldName(target, change.to)
        if (fromName == toName) return Verdict.Compatible
        return Verdict.Breaking(
            "${change.path}: the column was renamed from '$fromName' to '$toName' breaks " +
                "statements that reference the old name",
            "pin the emitted column with @sql(column = \"$fromName\")",
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
     * A scalar change is judged by [TypeCompat.sqlWidening]; a reference change is compatible only
     * when it still names the same declaration, breaking when both sides are enums (the CHECK's
     * value set changes) or otherwise; a list or map keeps its element nullability check, and
     * anything else mixing a scalar with a list, map, or reference is breaking outright.
     */
    private fun resolvedTypeVerdict(ctx: ChangeContext, from: Type, to: Type): Verdict {
        val help = "add a new column instead of changing this one's type"
        return when {
            from is Scalar && to is Scalar ->
                if (TypeCompat.sqlWidening(from, to)) Verdict.Compatible
                else Verdict.Breaking("existing values that no longer fit the narrower type", help)
            from is Ref && to is Ref -> refTypeVerdict(ctx, from, to, help)
            from is ListOf && to is ListOf -> listTypeVerdict(from, to, help)
            from is MapOf && to is MapOf -> mapTypeVerdict(from, to, help)
            else -> Verdict.Breaking("rows backed by an incompatible storage type", help)
        }
    }

    private fun refTypeVerdict(ctx: ChangeContext, from: Ref, to: Ref, help: String): Verdict {
        if (from.target == to.target) return Verdict.Compatible
        val fromEnum = ctx.old.lookupOrNull(from.target) is EnumType
        val toEnum = ctx.new.lookupOrNull(to.target) is EnumType
        val why =
            if (fromEnum && toEnum) "the CHECK constraint's value set"
            else "rows backed by an incompatible storage type"
        return Verdict.Breaking(why, help)
    }

    private fun listTypeVerdict(from: ListOf, to: ListOf, help: String): Verdict {
        val sameElement = typeCore(from.element) == typeCore(to.element)
        return if (sameElement && !from.nullableElement && to.nullableElement) Verdict.Compatible
        else Verdict.Breaking("rows backed by an incompatible storage type", help)
    }

    private fun mapTypeVerdict(from: MapOf, to: MapOf, help: String): Verdict {
        if (typeCore(from.key) != typeCore(to.key))
            return Verdict.Breaking("existing entries keyed by the old type", help)
        val sameValue = typeCore(from.value) == typeCore(to.value)
        return if (sameValue && !from.nullableValue && to.nullableValue) Verdict.Compatible
        else Verdict.Breaking("rows backed by an incompatible storage type", help)
    }

    private fun fieldNullabilityChanged(change: FieldNullabilityChanged): Verdict =
        if (change.from.nullable && !change.to.nullable)
            Verdict.Breaking(
                "${change.path}: the column became NOT NULL breaks existing rows that store a " +
                    "null value",
                "backfill the column before making it NOT NULL",
            )
        else Verdict.Compatible

    private fun fieldRefinementChanged(change: FieldRefinementChanged): Verdict =
        if (change.tightened)
            Verdict.Breaking(
                "${change.path}: the refinement was tightened breaks rows whose existing value " +
                    "falls outside the new CHECK",
                "validate and migrate existing values before tightening the constraint",
            )
        else Verdict.Compatible

    private fun fieldDefaultChanged(change: FieldDefaultChanged): Verdict =
        if (change.to.default == null && !change.to.nullable)
            Verdict.Breaking(
                "${change.path}: the default was removed from a NOT NULL column breaks inserts " +
                    "that omit it",
                "keep a default, or supply the column explicitly in every insert",
            )
        else Verdict.Compatible

    private fun enumValueRenamed(change: EnumValueRenamed, ctx: ChangeContext): Verdict {
        val fromName = ctx.emittedValueName(target, change.enum, change.from)
        val toName = ctx.emittedValueName(target, change.enum, change.to)
        if (fromName == toName) return Verdict.Compatible
        return Verdict.Breaking(
            "${change.path}: the enum value was renamed from '$fromName' to '$toName' breaks the " +
                "CHECK constraint for rows that still store the old value",
            "add a new value instead of renaming this one",
        )
    }

    private fun declarationRemoved(change: DeclarationRemoved, ctx: ChangeContext): Verdict =
        if (ctx.hasTable(Side.OLD, change.decl.qualifiedName))
            Verdict.Breaking(
                "${change.path}: the declaration was removed breaks reads of the table backing it",
                "drop the table only after migrating or archiving its data",
            )
        else Verdict.Compatible

    private fun annotationChanged(change: AnnotationChanged, ctx: ChangeContext): Verdict {
        if (change.target != "sql") return Verdict.Compatible
        return when (change.key) {
            "key" ->
                Verdict.Breaking(
                    "${change.path}: @sql(key) changed breaks the primary key used to address " +
                        "existing rows",
                    "avoid changing @sql(key) once the table holds data",
                )
            "strategy" ->
                Verdict.Breaking(
                    "${change.path}: @sql(strategy) changed breaks how existing rows map onto tables",
                    "avoid changing @sql(strategy) once the table holds data",
                )
            "column" -> columnRenamed(change, ctx)
            "table" -> declRenamed(change, ctx) { Names.snakeCase(it.name) }
            "schema" ->
                declRenamed(change, ctx) { it.qualifiedName.namespace.substringAfterLast(".") }
            else -> Verdict.Compatible
        }
    }

    /**
     * `@sql(column)` compared the way [fieldRenamed] compares names: a pin that still agrees with
     * the field's emitted column, added, changed, or removed, is only breaking when the two sides'
     * emitted names actually differ.
     */
    private fun columnRenamed(change: AnnotationChanged, ctx: ChangeContext): Verdict {
        val field = fieldAt(ctx.new, change.path) ?: fieldAt(ctx.old, change.path)
        val declaredName = field?.name
        val fromName = (change.from as? AnnotationValue.Str)?.value ?: declaredName
        val toName = (change.to as? AnnotationValue.Str)?.value ?: declaredName
        return renameVerdict(change, fromName, toName)
    }

    /**
     * `@sql(table)` or `@sql(schema)` compared against [default]'s derivation (the snake-cased
     * record name, or the namespace's last segment) when the override is absent on that side.
     */
    private fun declRenamed(
        change: AnnotationChanged,
        ctx: ChangeContext,
        default: (TypeDecl) -> String,
    ): Verdict {
        val decl = declAt(ctx.new, change.path) ?: declAt(ctx.old, change.path)
        val fallback = decl?.let(default)
        val fromName = (change.from as? AnnotationValue.Str)?.value ?: fallback
        val toName = (change.to as? AnnotationValue.Str)?.value ?: fallback
        return renameVerdict(change, fromName, toName)
    }

    private fun renameVerdict(
        change: AnnotationChanged,
        fromName: String?,
        toName: String?,
    ): Verdict =
        if (fromName != null && fromName == toName) Verdict.Compatible
        else
            Verdict.Breaking(
                "${change.path}: @sql(${change.key}) changed breaks statements that reference " +
                    "the old name",
                "migrate references to the new name, or keep @sql(${change.key}) pinned to the " +
                    "old one",
            )

    private fun declAt(schema: Schema, path: String): TypeDecl? =
        schema.namespaces
            .flatMap { it.declarations.flatMap { d -> d.selfAndNested() } }
            .firstOrNull { it.qualifiedName.toString() == path }

    private fun fieldAt(schema: Schema, path: String): Field? {
        val decl = declAt(schema, path.substringBeforeLast(".")) as? RecordType ?: return null
        return decl.fields.firstOrNull { it.name == path.substringAfterLast(".") }
    }
}
