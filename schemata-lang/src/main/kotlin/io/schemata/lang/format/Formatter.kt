package io.schemata.lang.format

import io.schemata.lang.Diagnostic
import io.schemata.lang.Parser
import io.schemata.lang.Span
import io.schemata.lang.ast.AliasDecl
import io.schemata.lang.ast.Annotation
import io.schemata.lang.ast.AnnotationArg
import io.schemata.lang.ast.AnnotationValue
import io.schemata.lang.ast.Declaration
import io.schemata.lang.ast.EnumDecl
import io.schemata.lang.ast.RecordDecl
import io.schemata.lang.ast.Refinement
import io.schemata.lang.ast.ServiceDecl
import io.schemata.lang.ast.SourceFile
import io.schemata.lang.ast.TypeExpr
import io.schemata.lang.ast.UnionDecl

sealed interface FormatResult {
    data class Formatted(val text: String) : FormatResult

    data class Failed(val diagnostics: List<Diagnostic>) : FormatResult
}

/** How wide [s] prints: one column per code point, so an astral character counts once. */
internal fun width(s: String): Int = s.codePointCount(0, s.length)

/** Prints a schema file in the canonical layout; never analyses, so unresolved imports are fine. */
object Formatter {
    const val LINE_WIDTH = 100
    internal const val INDENT = "  "

    /**
     * The output always ends its lines with `\n`, whatever the source used, comments included, and
     * never starts with a byte-order mark.
     */
    fun format(input: String, path: String): FormatResult {
        val source = input.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        val parsed = Parser.parseForFormat(source, path)
        val file = parsed.file ?: return FormatResult.Failed(parsed.diagnostics)
        val text = Printer(source, parsed.comments).file(file)
        val reparsed = Parser.parseForFormat(text, path)
        check(reparsed.file != null) {
            "formatter produced unparsable output for $path: ${reparsed.diagnostics}"
        }
        val before = parsed.comments.count()
        val after = reparsed.comments.count()
        check(before == after) {
            "formatter changed the comment count for $path: input had $before, output has $after"
        }
        return FormatResult.Formatted(text)
    }

    private fun CommentTable.count(): Int =
        fileLeading.size +
            leading.values.sumOf { it.size } +
            trailing.values.sumOf { it.size } +
            headerTrailing.values.sumOf { it.size } +
            endOfBlock.values.sumOf { it.size } +
            fileTrailing.size

    internal class Printer(val source: String, val comments: CommentTable) {
        val lines = source.lines()

        fun file(f: SourceFile): String = buildString {
            comments.fileLeading.forEach { appendLine(it.text) }
            f.doc?.let { docLines(it, "").forEach { l -> appendLine(l) } }
            f.annotations.forEach { a ->
                comments.leading[a.span]?.forEach { appendLine(it.text) }
                appendLine(annotation(a) + trailing(a.span))
            }
            comments.leading[f.namespace.span]?.forEach { appendLine(it.text) }
            appendLine("namespace ${f.namespace.name}" + trailing(f.namespace.span))
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

        // Every element prints as: leading comments, doc lines, own-line annotations, then the
        // element. A declaration returns its lines joined with '\n' and a trailing '\n'. Record,
        // enum and union print their own trailing comment as part of their body; only alias needs
        // it added here.
        fun declaration(d: Declaration, indent: String): String = buildString {
            comments.leading[d.span]?.forEach { appendLine(indent + it.text) }
            d.doc?.let { docLines(it, indent).forEach { l -> appendLine(l) } }
            d.annotations.forEach { appendLine(indent + annotation(it) + trailing(it.span)) }
            when (d) {
                is RecordDecl -> append(record(d, indent))
                is EnumDecl -> append(enum(d, indent))
                is UnionDecl -> append(union(d, indent))
                is AliasDecl ->
                    appendLine(indent + "alias ${d.name} = ${typeExpr(d.type)}" + trailing(d.span))
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

        internal fun typeExpr(t: TypeExpr): String {
            val args =
                if (t.args.isEmpty()) "" else "<" + t.args.joinToString(", ") { typeExpr(it) } + ">"
            val refinements =
                if (t.refinements.isEmpty()) ""
                else "(" + t.refinements.joinToString(", ") { refinement(it) } + ")"
            return t.name + args + refinements + (if (t.nullable) "?" else "")
        }

        private fun refinement(r: Refinement): String =
            when (r) {
                is Refinement.Named -> "${r.name} = ${slice(r.value.span)}"
                is Refinement.Positional -> slice(r.value.span)
            }

        internal fun annotation(a: Annotation): String {
            val args =
                if (a.args.isEmpty()) ""
                else "(" + a.args.joinToString(", ") { annotationArg(it) } + ")"
            return "@${a.name}$args"
        }

        private fun annotationArg(arg: AnnotationArg): String =
            when (arg) {
                is AnnotationArg.Named -> "${arg.name} = ${annotationValue(arg.value)}"
                is AnnotationArg.Positional -> annotationValue(arg.value)
            }

        private fun annotationValue(v: AnnotationValue): String =
            when (v) {
                is AnnotationValue.Lit -> slice(v.literal.span)
                is AnnotationValue.Tuple -> "(" + v.names.joinToString(", ") + ")"
            }

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

        internal fun record(d: RecordDecl, indent: String): String {
            val canOneLine = canOneLineRecord(d)
            val oneLineMembers =
                if (canOneLine) d.fields.joinToString(" ") { fieldOneLine(it) } else ""
            val innerIndent = indent + INDENT
            val ordWidth = ordinalWidth(d.fields.map { it.ordinal })
            val nameWidth = d.fields.maxOfOrNull { "${it.name}:".length } ?: 0
            val members = mutableListOf<BodyMember>()
            d.fields.forEach {
                members +=
                    BodyMember(
                        it.span,
                        isNested = false,
                        fieldMultilineLines(it, innerIndent, ordWidth, nameWidth),
                    )
            }
            d.nested.forEach {
                members +=
                    BodyMember(
                        it.span,
                        isNested = true,
                        declaration(it, innerIndent).removeSuffix("\n").split("\n"),
                    )
            }
            members += reservedMembers(d.reserved, innerIndent)
            return block(indent, "record ${d.name}", d.span, canOneLine, oneLineMembers, members)
        }

        internal fun enum(d: EnumDecl, indent: String): String {
            val canOneLine = canOneLineEnum(d)
            val oneLineMembers =
                if (canOneLine) d.values.joinToString(", ") { valueOneLine(it) } else ""
            val innerIndent = indent + INDENT
            val ordWidth = ordinalWidth(d.values.map { it.ordinal })
            val members = mutableListOf<BodyMember>()
            d.values.forEach {
                members +=
                    BodyMember(
                        it.span,
                        isNested = false,
                        valueMultilineLines(it, innerIndent, ordWidth),
                    )
            }
            members += reservedMembers(d.reserved, innerIndent)
            return block(indent, "enum ${d.name}", d.span, canOneLine, oneLineMembers, members)
        }

        /**
         * Shared by [record], [enum] and [service]: the one-line form, or the braced multi-line
         * form.
         */
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
