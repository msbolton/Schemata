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
 * Reports SCH1053 when a hoisted name collides with a declared one, or would hide a name visible
 * from an enclosing scope (the schema's top level, or a model the field's model is nested in),
 * since every bare use of that name inside the model would then mean the hoisted type. An inline
 * shape is composition, never a model of its own, so a key inside one (`{ id }` on one of its
 * fields, or `@@id`) is SCH1049.
 *
 * A hoisted field's type keeps the declaration in [TypeExpr.inlineEnum] or [TypeExpr.inlineShape],
 * now named, so the analyzer can still tell a hoisted shape from a declared record. Hoisted
 * declarations follow the record's declared nested ones, in field order; a shape's own inline
 * fields hoist inside it, named after it (`OrderAddress` holds `OrderAddressGeo`).
 */
object Hoisting {
    private const val NAME = "name"
    private const val TIMESTAMPS = "timestamps"

    /**
     * [topLevel] holds the names declared at the top level of [file]'s schema, in every file that
     * declares it; the file's own declarations are always among them.
     */
    fun apply(
        file: SourceFile,
        topLevel: Set<String> = emptySet(),
        report: (Diagnostic) -> Unit,
    ): SourceFile {
        val visible =
            Visible(file.namespace.name, topLevel + file.declarations.map { it.name }, emptyMap())
        return file.copy(
            declarations =
                file.declarations.map { if (it is RecordDecl) record(it, visible, report) else it }
        )
    }

    /**
     * The names a field's type could already mean from outside its own model: the schema's top
     * level, then each enclosing model's nested declarations, mapped to the model that holds it and
     * whether that model got it by hoisting an inline type rather than by declaring it.
     */
    private class Visible(
        val schema: String,
        val topLevel: Set<String>,
        val enclosing: Map<String, Holder>,
    ) {
        class Holder(val model: String, val hoisted: Boolean)

        fun inside(model: RecordDecl, nested: Collection<String>, hoisted: Set<String>): Visible =
            Visible(
                schema,
                topLevel,
                enclosing + nested.associateWith { Holder(model.name, it in hoisted) },
            )

        /** Who holds [name] already, worded to follow "which", or null when nobody does. */
        fun owner(name: String): String? =
            enclosing[name]?.let {
                "model '${it.model}' ${if (it.hoisted) "also names" else "also declares"}"
            } ?: if (name in topLevel) "the top level of schema '$schema' also declares" else null
    }

    private fun record(
        decl: RecordDecl,
        visible: Visible,
        report: (Diagnostic) -> Unit,
    ): RecordDecl {
        val taken = decl.nested.map { it.name }.toMutableSet()
        val hoisted = mutableListOf<Declaration>()
        // the field each hoisted name came from, so a later twin can name it
        val sources = mutableMapOf<String, String>()
        val fields = decl.fields.map { field(decl, it, taken, hoisted, sources, visible, report) }
        // only the block attribute `@@timestamps`; a single-`@` one goes to the annotation checker
        val (stamps, annotations) = decl.annotations.partition { it.block && it.name == TIMESTAMPS }
        val inner = visible.inside(decl, taken, hoisted.map { it.name }.toSet())
        return decl.copy(
            fields = fields + timestamps(fields, decl.reserved, stamps, report),
            nested =
                decl.nested.map { if (it is RecordDecl) record(it, inner, report) else it } +
                    hoisted,
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
        sources: MutableMap<String, String>,
        visible: Visible,
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
        if (inlineShape != null) keyless(inlineShape, report)
        val declaration: Declaration =
            inlineEnum?.copy(name = name)
                ?: record(
                    inlineShape!!.copy(name = name),
                    visible.inside(owner, taken, hoisted.map { it.name }.toSet()),
                    report,
                )
        val reference =
            type.copy(
                name = name,
                inlineEnum = declaration as? EnumDecl,
                inlineShape = declaration as? RecordDecl,
            )
        val kind = if (inlineEnum != null) "enum" else "shape"
        val hidden = visible.owner(name)
        if (name !in taken && hidden != null) {
            report(
                Diagnostic(
                    CoreCodes.HOISTED_NAME_COLLISION,
                    "the inline $kind of field '${field.name}' is named '$name', which $hidden; inside model '${owner.name}' the name would mean the inline $kind",
                    field.nameSpan,
                    help = "name it with @name(\"…\")",
                )
            )
        }
        if (taken.add(name)) {
            hoisted += declaration
            sources[name] = field.name
            return field.copy(type = reference)
        }
        // a name already in [taken] is either a declared nested one or an earlier field's hoisted
        // one
        val earlier = sources[name]
        val twin = if (earlier != null) "hoists from field '$earlier'" else "already declares"
        report(
            Diagnostic(
                CoreCodes.HOISTED_NAME_COLLISION,
                "the inline $kind of field '${field.name}' is named '$name', which model '${owner.name}' $twin",
                field.nameSpan,
                help = "name it with @name(\"…\")",
            )
        )
        return field.copy(type = reference, default = null, options = emptyList())
    }

    /**
     * An inline shape is part of the model it is written in, so it carries no key: `{ id }` on one
     * of its fields or `@@id` in its body would turn it into a model of its own that every target
     * references instead of composing.
     */
    private fun keyless(shape: RecordDecl, report: (Diagnostic) -> Unit) {
        val help = "declare a nested model to give it a key"
        shape.fields
            .flatMap { f -> f.options.filter { it.name == "id" } }
            .forEach {
                report(
                    Diagnostic(
                        CoreCodes.OPTION_NOT_APPLICABLE,
                        "option 'id' does not apply inside an inline shape, which has no key",
                        it.span,
                        help = help,
                    )
                )
            }
        shape.annotations
            .filter { it.block && it.name == "id" }
            .forEach {
                report(
                    Diagnostic(
                        CoreCodes.OPTION_NOT_APPLICABLE,
                        "@@id does not apply inside an inline shape, which has no key",
                        it.span,
                        help = help,
                    )
                )
            }
    }

    /** The string `@name("X")` gives, or null when it is written some other way. */
    private fun written(annotation: Annotation): String? {
        val arg = annotation.args.singleOrNull() as? AnnotationArg.Positional ?: return null
        val value = arg.value as? AnnotationValue.Lit ?: return null
        return (value.literal as? Literal.StringLit)?.value
    }

    /**
     * The two fields `@@timestamps` appends. Unpinned, and when every field has an explicit
     * ordinal, they take the next two after the highest one; otherwise the next two after the last
     * field's position. Either way they step over reserved ordinals. In a body of implicit ordinals
     * the chosen ones carry no span, since nobody wrote them, and the analyzer reads them as
     * positions.
     *
     * `@@timestamps(#n, #m)` pins them: `created_at` takes `#n` and `updated_at` `#m`, in either
     * order. Both or neither, because a single pinned ordinal leaves the other to float with the
     * model. Under explicit ordinals the pinned ones are written ordinals like any field's, so the
     * ordinal check reports a clash or a reserved one. In a body of implicit ordinals that check
     * never sees them (the other fields are positions), so the clashes are reported here: against
     * the positions 1..n the other fields hold, and the reserved ranges. The positional fields keep
     * their positions and the stamps are appended after them, so the mix of written and implicit
     * ordinals is never presented to the ordinal check.
     *
     * An unpinned `@@timestamps` over explicit ordinals draws SCH1054, since adding a field later
     * moves the stamps; a model with no fields has nothing to move, so it draws none.
     */
    private fun timestamps(
        fields: List<FieldDecl>,
        reserved: List<ReservedItem>,
        stamps: List<Annotation>,
        report: (Diagnostic) -> Unit,
    ): List<FieldDecl> {
        val first = stamps.firstOrNull() ?: return emptyList()
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
        stamps.drop(1).filter { it.args.isNotEmpty() }.forEach { badShape(it, report) }
        val explicit = fields.isNotEmpty() && fields.all { it.ordinal != null }
        val ranges = reserved.filterIsInstance<ReservedItem.Ordinals>().map { it.from..it.to }
        val pinned =
            first.args.map {
                ((it as? AnnotationArg.Positional)?.value as? AnnotationValue.Ordinal)
            }
        val malformed = first.args.isNotEmpty() && (pinned.size != 2 || pinned.any { it == null })
        if (malformed) badShape(first, report)
        if (first.args.isNotEmpty() && !malformed) {
            val (created, updated) = pinned.map { it!! }
            if (!explicit) {
                // the other fields hold the positions 1..n
                val taken = fields.size
                listOf(created, updated).forEach { p ->
                    when {
                        p.ordinal <= 0 ->
                            report(
                                Diagnostic(
                                    CoreCodes.INVALID_ORDINAL,
                                    "ordinal #${p.ordinal} is not positive",
                                    p.span,
                                    help = "ordinals start at #1",
                                )
                            )
                        p.ordinal <= taken || (p === updated && p.ordinal == created.ordinal) ->
                            report(
                                Diagnostic(
                                    CoreCodes.DUPLICATE_ORDINAL,
                                    "ordinal #${p.ordinal} is used more than once in this model",
                                    p.span,
                                    help = "pin an ordinal no field holds",
                                )
                            )
                        ranges.any { p.ordinal in it } ->
                            report(
                                Diagnostic(
                                    CoreCodes.RESERVED_CONFLICT,
                                    "ordinal #${p.ordinal} is reserved in this model",
                                    p.span,
                                    help = "pin an ordinal that is not reserved",
                                )
                            )
                    }
                }
            }
            val at = { p: AnnotationValue.Ordinal -> p.span.takeIf { explicit } }
            return listOf(
                stamp("created_at", created.ordinal, at(created), nullable = false, first.span),
                stamp("updated_at", updated.ordinal, at(updated), nullable = true, first.span),
            )
        }
        val last = if (explicit) fields.maxOf { it.ordinal!! } else fields.size
        val created = free(last + 1, ranges)
        val updated = free(created + 1, ranges)
        if (explicit && !malformed) {
            report(
                Diagnostic(
                    CoreCodes.TIMESTAMPS_UNPINNED,
                    "timestamps are unpinned; adding a field later renumbers them",
                    first.span,
                    help = "write `@@timestamps(#$created, #$updated)`",
                )
            )
        }
        val at = first.span.takeIf { explicit }
        return listOf(
            stamp("created_at", created, at, nullable = false, first.span),
            stamp("updated_at", updated, at, nullable = true, first.span),
        )
    }

    private fun badShape(stamp: Annotation, report: (Diagnostic) -> Unit) =
        report(
            Diagnostic(
                CoreCodes.ANNOTATION_VALUE,
                "@@timestamps takes both ordinals or none",
                stamp.span,
                help = "write `@@timestamps` or `@@timestamps(#n, #m)`",
            )
        )

    /** The first ordinal from [from] on that no range in [reserved] holds. */
    private fun free(from: Int, reserved: List<IntRange>): Int {
        var candidate = from
        while (true) {
            val hit = reserved.firstOrNull { candidate in it } ?: return candidate
            if (hit.last == Int.MAX_VALUE) return Int.MAX_VALUE
            candidate = hit.last + 1
        }
    }

    private fun stamp(
        name: String,
        ordinal: Int,
        ordinalSpan: Span?,
        nullable: Boolean,
        at: Span,
    ): FieldDecl =
        FieldDecl(
            ordinal = ordinal,
            ordinalSpan = ordinalSpan,
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
