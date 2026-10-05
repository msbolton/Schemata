package io.schemata.evolution

import io.schemata.core.ir.EnumType
import io.schemata.core.ir.kindWord

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
            // DDL carries no services, so nothing about one touches stored rows
            is ServiceAdded,
            is ServiceRemoved,
            is OperationAdded,
            is OperationRemoved,
            is OperationRenamed,
            is OperationRequestChanged,
            is OperationResponseChanged,
            is OperationBindingChanged -> Verdict.Compatible
        }

    private fun namespaceRemoved(change: NamespaceRemoved, ctx: ChangeContext): Verdict =
        if (
            ctx.declarationsOf(Side.OLD, change.path).any {
                ctx.hasTable(Side.OLD, it.qualifiedName)
            }
        )
            Verdict.Breaking(
                "${change.path}: the namespace was removed breaks reads of the tables backing its " +
                    "declarations",
                "drop the tables only after migrating or archiving their data",
            )
        else Verdict.Compatible

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

    private fun fieldTypeChanged(change: FieldTypeChanged, ctx: ChangeContext): Verdict =
        wrapTypeVerdict(change, typeVerdict(ctx).of(change.from.type, change.to.type))

    /**
     * Scalars widen by [TypeCompat.sqlWidening]; a reference is compatible only while it names the
     * same declaration, and two different enums change the CHECK constraint's value set.
     */
    private fun typeVerdict(ctx: ChangeContext) =
        StructuralTypeVerdict(
            TypeCompat::sqlWidening,
            holders = "existing rows",
            incompatible = "the column's storage type is incompatible",
            help = "add a new column instead of changing this one's type",
        ) { from, to ->
            val fromEnum = ctx.old.lookupOrNull(from.target) is EnumType
            val toEnum = ctx.new.lookupOrNull(to.target) is EnumType
            when {
                from.target == to.target -> Verdict.Compatible
                fromEnum && toEnum ->
                    Verdict.Breaking(
                        "existing rows: the CHECK constraint's value set changes",
                        "add a new column instead of changing this one's type",
                    )
                else ->
                    Verdict.Breaking(
                        "existing rows: the column's storage type is incompatible",
                        "add a new column instead of changing this one's type",
                    )
            }
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
        val fromName = ctx.emittedValueName(target, change.from)
        val toName = ctx.emittedValueName(target, change.to)
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

    /**
     * Every `@sql` key, decided explicitly: a primary key, storage strategy, uniqueness added, or
     * column type override changes the DDL existing rows live under; a name override is a rename
     * only when the emitted name moves; uniqueness removed and an index only relax or speed up
     * access. A key this rulebook does not know is treated as breaking, so a new DDL-shaping key is
     * never waved through by default.
     */
    private fun annotationChanged(change: AnnotationChanged, ctx: ChangeContext): Verdict {
        if (change.target != "sql") return Verdict.Compatible
        if (change.newOwner is ServiceOwner || change.newOwner is OperationOwner)
            return Verdict.Compatible
        return when (change.key) {
            "key" ->
                Verdict.Breaking(
                    "${change.path}: @sql(key) ${changeWord(change)} breaks the primary key used " +
                        "to address existing rows",
                    "avoid changing @sql(key) once the table holds data",
                )
            "strategy" ->
                Verdict.Breaking(
                    "${change.path}: @sql(strategy) changed breaks how existing rows map onto " +
                        "tables",
                    "avoid changing @sql(strategy) once the table holds data",
                )
            "unique" ->
                if (change.to != null)
                    Verdict.Breaking(
                        "${change.path}: @sql(unique) added breaks tables that already hold " +
                            "duplicate values",
                        "remove duplicate rows before adding the constraint",
                    )
                else Verdict.Compatible
            "type" ->
                Verdict.Breaking(
                    "${change.path}: @sql(type) ${changeWord(change)} breaks existing rows: the " +
                        "column is retyped",
                    "add a new column instead of retyping this one",
                )
            "column",
            "table",
            "schema" -> renamed(change, ctx)
            in INERT_KEYS -> Verdict.Compatible
            else ->
                Verdict.Breaking(
                    "${change.path}: @sql(${change.key}) ${changeWord(change)} has no known " +
                        "effect on existing rows, so it is assumed to break them",
                    "review the emitted DDL for this change by hand",
                )
        }
    }

    /** `@sql` keys whose change never touches data already stored. */
    private val INERT_KEYS = setOf("index")

    /**
     * `@sql(column)`, `@sql(table)`, or `@sql(schema)` added, changed, or removed: a rename of the
     * column, table, or schema only when the OLD element's emitted name differs from the NEW one's.
     */
    private fun renamed(change: AnnotationChanged, ctx: ChangeContext): Verdict {
        val fromName = ctx.emittedName(target, change.oldOwner)
        val toName = ctx.emittedName(target, change.newOwner)
        if (fromName == toName) return Verdict.Compatible
        return Verdict.Breaking(
            "${change.path}: @sql(${change.key}) ${changeWord(change)} renames '$fromName' to " +
                "'$toName', which breaks statements that reference the old name",
            "migrate references to the new name, or keep @sql(${change.key}) pinned to " +
                "\"$fromName\"",
        )
    }
}
