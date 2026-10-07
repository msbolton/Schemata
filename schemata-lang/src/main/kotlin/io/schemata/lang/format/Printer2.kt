package io.schemata.lang.format

import io.schemata.lang.Span
import io.schemata.lang.ast.AliasDecl
import io.schemata.lang.ast.Annotation
import io.schemata.lang.ast.AnnotationArg
import io.schemata.lang.ast.AnnotationValue
import io.schemata.lang.ast.Declaration
import io.schemata.lang.ast.EnumDecl
import io.schemata.lang.ast.FieldDecl
import io.schemata.lang.ast.Literal
import io.schemata.lang.ast.RecordDecl
import io.schemata.lang.ast.SourceFile
import io.schemata.lang.ast.TypeExpr
import io.schemata.lang.ast.UnionDecl
import io.schemata.lang.format.Formatter.INDENT
import io.schemata.lang.format.Formatter.LINE_WIDTH

/**
 * The canonical layout of the 2.0 surface. Unions, services, imports, docs and comments print as
 * they do in 1.x; what differs is below.
 * - The header is `schema name` with the file's attributes on the same line: an attribute starting
 *   on a later line would lead the first declaration instead.
 * - A model body that can share one line prints on one line, its fields two spaces apart. It cannot
 *   when it has a nested declaration, a reserved statement, a block attribute, a comment, a doc, a
 *   field with a leading attribute, or a field whose type is an inline enum or shape (which is a
 *   nested declaration in all but name).
 * - Otherwise each field takes its own line in four columns: the ordinal, the name, the type, and
 *   the rest (`{ options }`, then trailing attributes, then `= default`, one space apart). Each
 *   column is as wide as its widest entry plus one space; a field with nothing after its type is
 *   not padded, and an inline type is never padded nor counted in the type column's width.
 * - A field's attributes keep their side: those written before its name print on their own lines
 *   above it, and those after it stay on its line however long that makes it, since an attribute on
 *   the next line would lead the next member.
 * - Block attributes (`@@x`) close a model body, one per line, after a blank line when the body has
 *   members.
 * - Enum values are separated by spaces, never commas.
 * - An inline enum or shape prints on its field's line when it can share one and the whole line
 *   fits in [LINE_WIDTH]; otherwise its `{` ends the field's line, its members follow one level
 *   deeper, and its `}` starts a line that carries the rest of the field.
 */
internal class Printer2(source: String, comments: CommentTable) :
    Formatter.Printer(source, comments) {

    /**
     * Comments due at the end of the header line all go there, except that only the last `//`
     * comment can: any other prints on its own line above the header.
     */
    override fun header(f: SourceFile): List<String> {
        val spans = listOf(f.namespace.span) + f.annotations.map { it.span }
        val above = spans.flatMap { comments.leading[it].orEmpty() }.toMutableList()
        val ending = spans.flatMap { comments.trailing[it].orEmpty() }
        val lineComments = ending.filter { it.text.startsWith("//") }
        above += lineComments.dropLast(1)
        val atEnd = ending.filterNot { it.text.startsWith("//") } + lineComments.takeLast(1)
        val text =
            "schema ${f.namespace.name}" + f.annotations.joinToString("") { " " + annotation(it) }
        return above.map { it.text } + (text + lineEnd(atEnd))
    }

    /** A model's block attributes print inside its body, so only its leading ones print here. */
    override fun declaration(d: Declaration, indent: String): String = buildString {
        comments.leading[d.span]?.forEach { appendLine(indent + it.text) }
        d.doc?.let { docLines(it, indent).forEach { l -> appendLine(l) } }
        d.annotations
            .filterNot { it.block }
            .forEach { appendLine(indent + annotation(it) + trailing(it.span)) }
        when (d) {
            is RecordDecl -> append(record(d, indent))
            is EnumDecl -> append(enum(d, indent))
            is UnionDecl -> append(union(d, indent))
            is AliasDecl ->
                appendLine(indent + "alias ${d.name} = ${typeExpr(d.type)}" + trailing(d.span))
        }
    }

    override fun record(d: RecordDecl, indent: String): String =
        braced(
                indent + "model ${d.name} ",
                trailing(d.span),
                d.span,
                indent,
                modelOneLine(d, d.span),
            ) {
                modelMembers(d, indent + INDENT)
            }
            .joinToString("") { it + "\n" }

    override fun enum(d: EnumDecl, indent: String): String =
        braced(
                indent + "enum ${d.name} ",
                trailing(d.span),
                d.span,
                indent,
                enumOneLine(d, d.span),
            ) {
                enumMembers(d, indent + INDENT)
            }
            .joinToString("") { it + "\n" }

    /**
     * `name<args>(p, s)`, then the postfix `?`, `[]` and `?` as written, then the type's own
     * options (on a type argument or an alias). A field's options belong to the field and print
     * with it.
     */
    override fun typeExpr(t: TypeExpr): String {
        val core =
            when {
                t.inlineEnum != null -> "enum " + (enumOneLine(t.inlineEnum, t.span) ?: "{ … }")
                t.inlineShape != null -> modelOneLine(t.inlineShape, t.span) ?: "{ … }"
                else -> t.name
            }
        val args =
            if (t.args.isEmpty()) "" else "<" + t.args.joinToString(", ") { typeExpr(it) } + ">"
        val refinements =
            if (t.refinements.isEmpty()) ""
            else "(" + t.refinements.joinToString(", ") { slice(it.value.span) } + ")"
        val options = if (t.options.isEmpty()) "" else " " + options(t.options)
        return core + args + refinements + postfix(t) + options
    }

    /** `@name(key: value, positional)`, or `@@name(…)` for a block attribute. */
    override fun annotation(a: Annotation): String {
        val args =
            if (a.args.isEmpty()) ""
            else "(" + a.args.joinToString(", ") { annotationArg(it) } + ")"
        return (if (a.block) "@@" else "@") + a.name + args
    }

    private fun annotationArg(arg: AnnotationArg): String =
        when (arg) {
            is AnnotationArg.Named -> "${arg.name}: ${annotationValue(arg.value)}"
            is AnnotationArg.Positional -> annotationValue(arg.value)
        }

    private fun annotationValue(v: AnnotationValue): String =
        when (v) {
            is AnnotationValue.Lit -> literal(v.literal)
            is AnnotationValue.Tuple -> "(" + v.names.joinToString(", ") + ")"
        }

    /**
     * A literal as written, sliced from the source, except that a bare name prints as its name
     * (which the upgrader may have renamed) and a string with no source position (line 0, one the
     * upgrader wrote) prints from its value.
     */
    private fun literal(l: Literal): String =
        when {
            l is Literal.NameLit -> l.name
            l is Literal.StringLit && l.span.startLine == 0 ->
                "\"" + l.value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
            else -> slice(l.span)
        }

    private fun postfix(t: TypeExpr): String {
        val nullable = if (t.nullable) "?" else ""
        if (!t.list) return nullable
        return nullable + "[]" + if (t.listNullable) "?" else ""
    }

    /**
     * A braced body keyed by [key] for its comments: [open] is everything before its `{` on the
     * first line (indentation included) and [close] everything after its `}`. It is the single line
     * `open + oneLine + close` when [oneLine] is given and that line fits; otherwise the `{` ends
     * the first line, [members] follow, and the `}` starts the last line at [indent].
     */
    private fun braced(
        open: String,
        close: String,
        key: Span,
        indent: String,
        oneLine: String?,
        members: () -> List<String>,
    ): List<String> {
        if (oneLine != null) {
            val line = open + oneLine + close
            if (width(line) <= LINE_WIDTH) return listOf(line)
        }
        val lines = mutableListOf(open + "{" + lineEnd(comments.headerTrailing[key]))
        lines += members()
        comments.endOfBlock[key]?.forEach { lines += indent + INDENT + it.text }
        lines += indent + "}" + close
        return lines
    }

    /** The one-line body, its fields two spaces apart (or `{}`), when [d]'s can be; else null. */
    private fun modelOneLine(d: RecordDecl, key: Span): String? {
        val shareable =
            d.nested.isEmpty() &&
                d.reserved.isEmpty() &&
                d.annotations.none { it.block } &&
                comments.headerTrailing[key].isNullOrEmpty() &&
                comments.endOfBlock[key].isNullOrEmpty() &&
                d.fields.all { fieldShareable(it) }
        if (!shareable) return null
        if (d.fields.isEmpty()) return "{}"
        return "{ " + d.fields.joinToString("  ") { fieldOneLine(it) } + " }"
    }

    private fun fieldShareable(f: FieldDecl): Boolean =
        f.doc == null &&
            comments.leading[f.span].isNullOrEmpty() &&
            comments.trailing[f.span].isNullOrEmpty() &&
            f.type.inlineEnum == null &&
            f.type.inlineShape == null &&
            f.annotations.all { trails(it, f) && comments.trailing[it.span].isNullOrEmpty() }

    private fun fieldOneLine(f: FieldDecl): String {
        val ordinal = f.ordinal?.let { "#$it " } ?: ""
        return ordinal + f.name + " " + typeExpr(f.type) + rest(f, f.annotations)
    }

    /** Whether [a] is written after [f]'s name or ordinal, which makes it a trailing attribute. */
    private fun trails(a: Annotation, f: FieldDecl): Boolean {
        val name = f.ordinalSpan ?: f.nameSpan
        return a.span.startLine > name.startLine ||
            (a.span.startLine == name.startLine && a.span.startColumn > name.startColumn)
    }

    /** ` { options } @attr… = default`, each part after one space, or "" when there is none. */
    private fun rest(f: FieldDecl, trailingAttributes: List<Annotation>): String {
        val parts = mutableListOf<String>()
        if (f.options.isNotEmpty()) parts += options(f.options)
        trailingAttributes.forEach { parts += annotation(it) }
        f.default?.let { parts += "= " + literal(it) }
        return parts.joinToString("") { " $it" }
    }

    private fun modelMembers(d: RecordDecl, indent: String): List<String> {
        val ordWidth = ordinalWidth(d.fields.map { it.ordinal })
        val nameWidth = d.fields.maxOfOrNull { width(it.name) } ?: 0
        val typeWidth =
            d.fields
                .filter { it.type.inlineEnum == null && it.type.inlineShape == null }
                .maxOfOrNull { width(typeExpr(it.type)) } ?: 0
        val members = mutableListOf<BodyMember>()
        d.fields.forEach {
            members +=
                BodyMember(
                    it.span,
                    isNested = false,
                    field(it, indent, ordWidth, nameWidth, typeWidth),
                )
        }
        d.nested.forEach {
            members +=
                BodyMember(
                    it.span,
                    isNested = true,
                    declaration(it, indent).removeSuffix("\n").split("\n"),
                )
        }
        members += reservedMembers(d.reserved, indent)
        val lines = assembleBody(members).toMutableList()
        val blockAttributes = d.annotations.filter { it.block }
        if (blockAttributes.isNotEmpty() && lines.isNotEmpty()) lines += ""
        blockAttributes.forEach { a ->
            comments.leading[a.span]?.forEach { lines += indent + it.text }
            lines += indent + annotation(a) + trailing(a.span)
        }
        return lines
    }

    private fun field(
        f: FieldDecl,
        indent: String,
        ordWidth: Int,
        nameWidth: Int,
        typeWidth: Int,
    ): List<String> {
        val (trailingAttributes, leadingAttributes) = f.annotations.partition { trails(it, f) }
        val out = mutableListOf<String>()
        comments.leading[f.span]?.forEach { out += indent + it.text }
        f.doc?.let { out += docLines(it, indent) }
        leadingAttributes.forEach { out += indent + annotation(it) + trailing(it.span) }
        val ordinalPart =
            if (ordWidth > 0) (f.ordinal?.let { "#$it" } ?: "").padEnd(ordWidth) + " " else ""
        val open = indent + ordinalPart + f.name.padEnd(nameWidth) + " "
        val rest = rest(f, trailingAttributes)
        val close = rest + trailing(f.span)
        val t = f.type
        if (t.inlineEnum != null) {
            out +=
                braced(
                    open + "enum ",
                    postfix(t) + close,
                    f.span,
                    indent,
                    enumOneLine(t.inlineEnum, f.span),
                ) {
                    enumMembers(t.inlineEnum, indent + INDENT)
                }
            return out
        }
        if (t.inlineShape != null) {
            out +=
                braced(
                    open,
                    postfix(t) + close,
                    f.span,
                    indent,
                    modelOneLine(t.inlineShape, f.span),
                ) {
                    modelMembers(t.inlineShape, indent + INDENT)
                }
            return out
        }
        val type = typeExpr(t)
        out += open + (if (rest.isEmpty()) type else type.padEnd(typeWidth)) + close
        return out
    }

    /** `{ a b c }` (or `{}`) when [d]'s values can share one line, else null. */
    private fun enumOneLine(d: EnumDecl, key: Span): String? {
        val shareable =
            d.reserved.isEmpty() &&
                comments.headerTrailing[key].isNullOrEmpty() &&
                comments.endOfBlock[key].isNullOrEmpty() &&
                d.values.all { canInlineMember(it.span, it.doc, it.annotations) }
        if (!shareable) return null
        if (d.values.isEmpty()) return "{}"
        return "{ " + d.values.joinToString(" ") { valueOneLine(it) } + " }"
    }

    private fun enumMembers(d: EnumDecl, indent: String): List<String> {
        val ordWidth = ordinalWidth(d.values.map { it.ordinal })
        val members =
            d.values.map {
                BodyMember(it.span, isNested = false, valueMultilineLines(it, indent, ordWidth))
            } + reservedMembers(d.reserved, indent)
        return assembleBody(members)
    }
}
