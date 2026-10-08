package io.schemata.lang.format

import io.schemata.lang.Diagnostic
import io.schemata.lang.FormatParse
import io.schemata.lang.Parser
import io.schemata.lang.Span
import io.schemata.lang.ast.AliasDecl
import io.schemata.lang.ast.Annotation
import io.schemata.lang.ast.AnnotationArg
import io.schemata.lang.ast.AnnotationValue
import io.schemata.lang.ast.Declaration
import io.schemata.lang.ast.EnumDecl
import io.schemata.lang.ast.FieldDecl
import io.schemata.lang.ast.Literal
import io.schemata.lang.ast.Option
import io.schemata.lang.ast.RecordDecl
import io.schemata.lang.ast.ServiceDecl
import io.schemata.lang.ast.SourceFile
import io.schemata.lang.ast.TypeExpr
import io.schemata.lang.ast.UnionDecl

sealed interface FormatResult {
    /** [warnings] are what `upgrade` reports about a file it could rewrite. */
    data class Formatted(val text: String, val warnings: List<Diagnostic> = emptyList()) :
        FormatResult

    data class Failed(val diagnostics: List<Diagnostic>) : FormatResult
}

/** How wide [s] prints: one column per code point, so an astral character counts once. */
internal fun width(s: String): Int = s.codePointCount(0, s.length)

/** Prints a schema file in the canonical layout; never analyses, so unresolved imports are fine. */
object Formatter {
    const val LINE_WIDTH = 100
    internal const val INDENT = "  "

    /**
     * Prints [input] in the canonical layout, with `list<T>` written `T[]` wherever that says the
     * same ([CanonicalLists]). The output always ends its lines with `\n`, whatever the source
     * used, comments included, and never starts with a byte-order mark.
     */
    fun format(input: String, path: String): FormatResult {
        val source = normalize(input)
        val parsed = Parser.parseForFormat(source, path)
        val file = parsed.file ?: return FormatResult.Failed(parsed.diagnostics)
        val text = print(CanonicalLists.file(file), parsed.comments, source)
        return FormatResult.Formatted(checked(text, parsed, path))
    }

    /**
     * The text of [file], whose spans and [comments] point into [source]. The upgrader prints a
     * mapped 1.x file through this as well, so literals are always sliced from the text they were
     * parsed from.
     */
    internal fun print(file: SourceFile, comments: CommentTable, source: String): String =
        Printer(source, comments).file(file)

    /** [input] without a byte-order mark and with every line ending turned into `\n`. */
    internal fun normalize(input: String): String =
        input.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')

    /**
     * [text] after checking that it reads back and keeps every comment of [parsed]: either failing
     * is a printer bug, never the user's.
     */
    internal fun checked(text: String, parsed: FormatParse, path: String): String {
        val reparsed = Parser.parseForFormat(text, path)
        check(reparsed.file != null) {
            "formatter produced unparsable output for $path: ${reparsed.diagnostics}"
        }
        val before = parsed.comments.count()
        val after = reparsed.comments.count()
        check(before == after) {
            "formatter changed the comment count for $path: input had $before, output has $after"
        }
        return text
    }

    internal fun CommentTable.count(): Int =
        fileLeading.size +
            leading.values.sumOf { it.size } +
            trailing.values.sumOf { it.size } +
            headerTrailing.values.sumOf { it.size } +
            endOfBlock.values.sumOf { it.size } +
            fileTrailing.size

    /**
     * The canonical layout:
     * - The header is `schema name` with the file's attributes on the same line: an attribute
     *   starting on a later line would lead the first declaration instead.
     * - A model body that can share one line prints on one line, its fields two spaces apart. It
     *   cannot when it has a nested declaration, a reserved statement, a block attribute, a
     *   comment, a doc, a field with a leading attribute, or a field whose type is an inline enum
     *   or shape (which is a nested declaration in all but name).
     * - Otherwise each field takes its own line in four columns: the ordinal, the name, the type,
     *   and the rest (`{ options }`, then trailing attributes, then `= default`, one space apart).
     *   Each column is as wide as its widest entry plus one space; a field with nothing after its
     *   type is not padded, and an inline type is never padded nor counted in the type column's
     *   width.
     * - A field's attributes keep their side: those written before its name print on their own
     *   lines above it, and those after it stay on its line however long that makes it, since an
     *   attribute on the next line would lead the next member.
     * - Block attributes (`@@x`) close a model body, one per line, after a blank line when the body
     *   has members.
     * - Enum values are separated by spaces, never commas.
     * - An inline enum or shape prints on its field's line when it can share one and the whole line
     *   fits in [LINE_WIDTH]; otherwise its `{` ends the field's line, its members follow one level
     *   deeper, and its `}` starts a line that carries the rest of the field.
     */
    internal class Printer(val source: String, val comments: CommentTable) {
        val lines = source.lines()

        /**
         * Comments due at the end of the header line all go there, except that only the last `//`
         * comment can: any other prints on its own line above the header.
         */
        internal fun header(f: SourceFile): List<String> {
            // in source order, so the upgrader's annotations written ahead of `namespace` keep
            // their comments ahead of the namespace's
            val spans =
                (listOf(f.namespace.span) + f.annotations.map { it.span }).sortedWith(
                    compareBy({ it.startLine }, { it.startColumn })
                )
            val above = spans.flatMap { comments.leading[it].orEmpty() }.toMutableList()
            val ending = spans.flatMap { comments.trailing[it].orEmpty() }
            val lineComments = ending.filter { it.text.startsWith("//") }
            above += lineComments.dropLast(1)
            val atEnd = ending.filterNot { it.text.startsWith("//") } + lineComments.takeLast(1)
            val text =
                "schema ${f.namespace.name}" +
                    f.annotations.joinToString("") { " " + annotation(it) }
            return above.map { it.text } + (text + lineEnd(atEnd))
        }

        /**
         * A model's block attributes print inside its body, so only its leading ones print here.
         */
        fun declaration(d: Declaration, indent: String): String = buildString {
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

        internal fun record(d: RecordDecl, indent: String): String =
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

        internal fun enum(d: EnumDecl, indent: String): String =
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
        internal fun typeExpr(t: TypeExpr): String {
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
        internal fun annotation(a: Annotation): String {
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
         * (which the upgrader may have renamed) and a string with no source position (line 0, one
         * the upgrader wrote) prints from its value.
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
         * first line (indentation included) and [close] everything after its `}`. It is the single
         * line `open + oneLine + close` when [oneLine] is given and that line fits; otherwise the
         * `{` ends the first line, [members] follow, and the `}` starts the last line at [indent].
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

        /**
         * The one-line body, its fields two spaces apart (or `{}`), when [d]'s can be; else null.
         */
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

        /**
         * Whether [a] is written after [f]'s name or ordinal, which makes it a trailing attribute.
         */
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

        fun file(f: SourceFile): String = buildString {
            comments.fileLeading.forEach { appendLine(it.text) }
            f.doc?.let { docLines(it, "").forEach { l -> appendLine(l) } }
            header(f).forEach { appendLine(it) }
            if (f.imports.isNotEmpty()) {
                appendLine()
                f.imports.forEach { i ->
                    comments.leading[i.span]?.forEach { appendLine(it.text) }
                    appendLine(
                        "import ${i.namespace}${i.alias?.let { " as $it" } ?: ""}" +
                            trailing(i.span)
                    )
                }
            }
            val topLevel: List<Pair<Span, () -> String>> =
                f.declarations.map { d -> d.span to { declaration(d, "") } } +
                    f.services.map { s -> s.span to { service(s, "") } }
            topLevel
                .sortedWith(compareBy({ it.first.startLine }, { it.first.startColumn }))
                .forEach { (_, print) ->
                    appendLine()
                    append(print())
                }
            if (comments.fileTrailing.isNotEmpty()) {
                appendLine()
                comments.fileTrailing.forEach { appendLine(it.text) }
            }
        }

        /**
         * Always the braced form, one operation per line, except that a service with no members at
         * all prints as `service S {}`.
         */
        internal fun service(s: ServiceDecl, indent: String): String = buildString {
            comments.leading[s.span]?.forEach { appendLine(indent + it.text) }
            s.doc?.let { docLines(it, indent).forEach { l -> appendLine(l) } }
            s.annotations.forEach { appendLine(indent + annotation(it) + trailing(it.span)) }
            val innerIndent = indent + INDENT
            val ordWidth = ordinalWidth(s.operations.map { it.ordinal })
            val members = mutableListOf<BodyMember>()
            s.operations.forEach {
                members +=
                    BodyMember(it.span, isNested = false, operationLines(it, innerIndent, ordWidth))
            }
            members += reservedMembers(s.reserved, innerIndent)
            val canOneLine =
                s.operations.isEmpty() &&
                    s.reserved.isEmpty() &&
                    comments.headerTrailing[s.span].isNullOrEmpty() &&
                    comments.endOfBlock[s.span].isNullOrEmpty()
            append(block(indent, "service ${s.name}", s.span, canOneLine, "", members))
        }

        internal fun trailing(span: Span) = lineEnd(comments.trailing[span])

        /** Comments printed at the end of a line, each after two spaces. */
        internal fun lineEnd(cs: List<Comment>?) = cs?.joinToString("") { "  " + it.text } ?: ""

        internal fun docLines(doc: String, indent: String) =
            doc.lines().map { if (it.isEmpty()) "$indent///" else "$indent/// $it" }

        /** [span] always covers a single source line: every literal and reserved name is. */
        internal fun slice(span: Span): String {
            val line = lines[span.startLine - 1]
            val start = line.offsetByCodePoints(0, span.startColumn - 1)
            val end = line.offsetByCodePoints(0, span.endColumn)
            return line.substring(start, end)
        }

        /** A `{ … }` option block, `{ a, b 1, match "x" }`. */
        internal fun options(options: List<Option>): String =
            "{ " +
                options.joinToString(", ") { o ->
                    o.name + (o.value?.let { " " + slice(it.span) } ?: "")
                } +
                " }"

        /**
         * True when [annotations] can print inline before a member: none at all, or exactly one
         * annotation with at most one argument that carries no trailing comment of its own (a
         * comment glued to an annotation's line forces that annotation onto its own line so the
         * comment has somewhere to print).
         */
        internal fun canInline(annotations: List<Annotation>): Boolean =
            annotations.isEmpty() ||
                (annotations.size == 1 &&
                    annotations[0].args.size <= 1 &&
                    comments.trailing[annotations[0].span].isNullOrEmpty())

        /** The service body: the one-line form, or the braced multi-line form. */
        private fun block(
            indent: String,
            keyword: String,
            span: Span,
            canOneLine: Boolean,
            oneLineMembers: String,
            members: List<BodyMember>,
        ): String {
            if (canOneLine) {
                val body = if (oneLineMembers.isEmpty()) "{}" else "{ $oneLineMembers }"
                val oneLine = indent + "$keyword $body" + trailing(span)
                if (width(oneLine) <= LINE_WIDTH) return oneLine + "\n"
            }
            val innerIndent = indent + INDENT
            val bodyLines = assembleBody(members).toMutableList()
            comments.endOfBlock[span]?.forEach { bodyLines += innerIndent + it.text }
            return buildString {
                appendLine(indent + "$keyword {" + lineEnd(comments.headerTrailing[span]))
                bodyLines.forEach { appendLine(it) }
                appendLine(indent + "}" + trailing(span))
            }
        }

        /**
         * `union U = #1 A | #2 B` on one line when it fits; otherwise `union U =` and each member
         * on its own line, since the grammar allows no leading `|`: it trails every member but the
         * last. A member's own trailing comment sits at the end of its line; the union's own
         * trailing comment — there being no closing brace to hang it on — sits at the end of the
         * last member's line instead, after any comment trailing that member (which therefore reads
         * back as the union's own and alone never breaks the union), except that the member's `//`
         * comment prints above the member when the union has a trailing comment too, per
         * [lastMemberAbove]. A comment between a member's doc and its ordinal leads that member,
         * like any other leading comment, so it prints above the member's doc, not between the doc
         * and the ordinal.
         */
        internal fun union(d: UnionDecl, indent: String): String {
            if (canOneLineUnion(d)) {
                val oneLine =
                    indent +
                        "union ${d.name} = " +
                        d.members.joinToString(" | ") { unionMemberOneLine(it) } +
                        trailing(d.members.last().span) +
                        trailing(d.span)
                if (width(oneLine) <= LINE_WIDTH) return oneLine + "\n"
            }
            val innerIndent = indent + INDENT
            val declTrailing = comments.trailing[d.span].orEmpty()
            return buildString {
                appendLine(indent + "union ${d.name} =")
                d.members.forEachIndexed { i, m ->
                    val last = i == d.members.size - 1
                    val own = comments.trailing[m.span].orEmpty()
                    val above = if (last) lastMemberAbove(d) else emptyList()
                    comments.leading[m.span]?.forEach { appendLine(innerIndent + it.text) }
                    above.forEach { appendLine(innerIndent + it.text) }
                    m.doc?.let { docLines(it, innerIndent).forEach { l -> appendLine(l) } }
                    val pipe = if (last) "" else " |"
                    val ending = (own - above.toSet()) + if (last) declTrailing else emptyList()
                    appendLine(innerIndent + unionMemberOneLine(m) + pipe + lineEnd(ending))
                }
            }
        }
    }
}
