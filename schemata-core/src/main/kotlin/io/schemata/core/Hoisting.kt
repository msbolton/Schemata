package io.schemata.core

import io.schemata.lang.Diagnostic
import io.schemata.lang.Span
import io.schemata.lang.ast.Annotation
import io.schemata.lang.ast.AnnotationArg
import io.schemata.lang.ast.AnnotationValue
import io.schemata.lang.ast.Declaration
import io.schemata.lang.ast.EnumDecl
import io.schemata.lang.ast.FieldDecl
import io.schemata.lang.ast.Literal
import io.schemata.lang.ast.RecordDecl
import io.schemata.lang.ast.ReservedItem
import io.schemata.lang.ast.SourceFile
import io.schemata.lang.ast.TypeExpr

/**
 * Rewrites a parsed file before analysis: every inline enum or shape becomes a nested declaration
 * named `<Model><Field>` (UpperCamel; `@name("X")` on the field overrides) and the field's type
 * becomes a reference to it; `@@timestamps` appends `created_at instant` and `updated_at instant?`.
 * Reports SCH1053 when a hoisted name collides with a declared one.
 *
 * A hoisted field's type keeps the declaration in [TypeExpr.inlineEnum] or [TypeExpr.inlineShape],
 * now named, so the analyzer can still tell a hoisted shape from a declared record. Hoisted
 * declarations follow the record's declared nested ones, in field order; a shape's own inline
 * fields hoist inside it, named after it (`OrderAddress` holds `OrderAddressGeo`).
 */
object Hoisting {
    private const val NAME = "name"
    private const val TIMESTAMPS = "timestamps"

    fun apply(file: SourceFile, report: (Diagnostic) -> Unit): SourceFile =
        file.copy(
            declarations =
                file.declarations.map { if (it is RecordDecl) record(it, report) else it }
        )

    private fun record(decl: RecordDecl, report: (Diagnostic) -> Unit): RecordDecl {
        val taken = decl.nested.map { it.name }.toMutableSet()
        val hoisted = mutableListOf<Declaration>()
        val fields = decl.fields.map { field(decl, it, taken, hoisted, report) }
        // only the block attribute `@@timestamps`; a single-`@` one goes to the annotation checker
        val (stamps, annotations) = decl.annotations.partition { it.block && it.name == TIMESTAMPS }
        return decl.copy(
            fields = fields + timestamps(fields, decl.reserved, stamps, report),
            nested = decl.nested.map { if (it is RecordDecl) record(it, report) else it } + hoisted,
            annotations = annotations,
        )
    }

    /**
     * [field] with an inline type replaced by a reference to its hoisted declaration, which joins
     * [hoisted] unless its name is already in [taken]. On a collision the field keeps the reference
     * but drops its default and options, which were written for the inline type and would only be
     * judged against the declaration it collided with.
     */
    private fun field(
        owner: RecordDecl,
        field: FieldDecl,
        taken: MutableSet<String>,
        hoisted: MutableList<Declaration>,
        report: (Diagnostic) -> Unit,
    ): FieldDecl {
        val type = field.type
        val inlineEnum = type.inlineEnum
        val inlineShape = type.inlineShape
        val naming = field.annotations.firstOrNull { it.name == NAME }
        if (inlineEnum == null && inlineShape == null) {
            if (naming != null)
                report(
                    Diagnostic(
                        CoreCodes.ANNOTATION_ELEMENT,
                        "@name applies only to a field whose type is an inline shape or enum",
                        naming.span,
                        help = "remove `@name`; a declared type keeps its own name",
                    )
                )
            return field
        }
        val name =
            naming?.let { written(it) }
                ?: (Suggest.upperCamel(owner.name) + Suggest.upperCamel(field.name))
        val declaration: Declaration =
            inlineEnum?.copy(name = name) ?: record(inlineShape!!.copy(name = name), report)
        val reference =
            type.copy(
                name = name,
                inlineEnum = declaration as? EnumDecl,
                inlineShape = declaration as? RecordDecl,
            )
        if (taken.add(name)) {
            hoisted += declaration
            return field.copy(type = reference)
        }
        val kind = if (inlineEnum != null) "enum" else "shape"
        report(
            Diagnostic(
                CoreCodes.HOISTED_NAME_COLLISION,
                "the inline $kind of field '${field.name}' is named '$name', which record '${owner.name}' already declares",
                field.nameSpan,
                help = "name it with @name(\"…\")",
            )
        )
        return field.copy(type = reference, default = null, options = emptyList())
    }

    /** The string `@name("X")` gives, or null when it is written some other way. */
    private fun written(annotation: Annotation): String? {
        val arg = annotation.args.singleOrNull() as? AnnotationArg.Positional ?: return null
        val value = arg.value as? AnnotationValue.Lit ?: return null
        return (value.literal as? Literal.StringLit)?.value
    }

    /**
     * The two fields `@@timestamps` appends. When every field has an explicit ordinal, they take
     * the next two after the highest one, stepping over reserved ordinals; otherwise their
     * positions number them like every other field.
     */
    private fun timestamps(
        fields: List<FieldDecl>,
        reserved: List<ReservedItem>,
        stamps: List<Annotation>,
        report: (Diagnostic) -> Unit,
    ): List<FieldDecl> {
        val first = stamps.firstOrNull() ?: return emptyList()
        stamps
            .filter { it.args.isNotEmpty() }
            .forEach {
                report(
                    Diagnostic(
                        CoreCodes.ANNOTATION_VALUE,
                        "@@timestamps takes no arguments",
                        it.span,
                        help = "write `@@timestamps`",
                    )
                )
            }
        stamps.drop(1).forEach {
            report(
                Diagnostic(
                    CoreCodes.DUPLICATE_ANNOTATION,
                    "@@timestamps is given more than once",
                    it.span,
                    help = "keep one of them",
                )
            )
        }
        val explicit = fields.isNotEmpty() && fields.all { it.ordinal != null }
        val ordinals =
            if (!explicit) listOf(null, null)
            else {
                val ranges =
                    reserved.filterIsInstance<ReservedItem.Ordinals>().map { it.from..it.to }
                val created = free(fields.maxOf { it.ordinal!! } + 1, ranges)
                listOf(created, free(created + 1, ranges))
            }
        return listOf(
            stamp("created_at", ordinals[0], nullable = false, first.span),
            stamp("updated_at", ordinals[1], nullable = true, first.span),
        )
    }

    /** The first ordinal from [from] on that no range in [reserved] holds. */
    private fun free(from: Int, reserved: List<IntRange>): Int {
        var candidate = from
        while (true) {
            val hit = reserved.firstOrNull { candidate in it } ?: return candidate
            if (hit.last == Int.MAX_VALUE) return Int.MAX_VALUE
            candidate = hit.last + 1
        }
    }

    private fun stamp(name: String, ordinal: Int?, nullable: Boolean, at: Span): FieldDecl =
        FieldDecl(
            ordinal = ordinal,
            ordinalSpan = ordinal?.let { at },
            name = name,
            nameSpan = at,
            type =
                TypeExpr(
                    name = "instant",
                    nameSpan = at,
                    nameSegments = emptyList(),
                    args = emptyList(),
                    refinements = emptyList(),
                    nullable = nullable,
                    span = at,
                ),
            default = null,
            doc = null,
            annotations = emptyList(),
            span = at,
        )
}
