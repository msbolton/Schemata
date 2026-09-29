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
import io.schemata.lang.ast.SourceFile
import io.schemata.lang.ast.TypeExpr
import io.schemata.lang.ast.UnionDecl

sealed interface FormatResult {
    data class Formatted(val text: String) : FormatResult

    data class Failed(val diagnostics: List<Diagnostic>) : FormatResult
}

/** Prints a schema file in the canonical layout; never analyses, so unresolved imports are fine. */
object Formatter {
    const val LINE_WIDTH = 100
    internal const val INDENT = "  "

    fun format(source: String, path: String): FormatResult {
        val parsed = Parser.parseForFormat(source, path)
        val file = parsed.file ?: return FormatResult.Failed(parsed.diagnostics)
        return FormatResult.Formatted(Printer(source, parsed.comments).file(file))
    }

    internal class Printer(val source: String, val comments: CommentTable) {
        val lines = source.lines()

        fun file(f: SourceFile): String = buildString {
            comments.fileLeading.forEach { appendLine(it.text) }
            f.doc?.let { docLines(it, "").forEach { l -> appendLine(l) } }
            f.annotations.forEach { appendLine(annotation(it)) }
            appendLine("namespace ${f.namespace.name}")
            if (f.imports.isNotEmpty()) {
                appendLine()
                f.imports.forEach { i ->
                    appendLine("import ${i.namespace}${i.alias?.let { " as $it" } ?: ""}")
                }
            }
            f.declarations.forEach { d ->
                appendLine()
                append(declaration(d, ""))
            }
            if (comments.fileTrailing.isNotEmpty()) {
                appendLine()
                comments.fileTrailing.forEach { appendLine(it.text) }
            }
        }

        // Every element prints as: leading comments, doc lines, own-line annotations, then the
        // element. A declaration returns its lines joined with '\n' and a trailing '\n'.
        fun declaration(d: Declaration, indent: String): String = buildString {
            comments.leading[d.span]?.forEach { appendLine(indent + it.text) }
            d.doc?.let { docLines(it, indent).forEach { l -> appendLine(l) } }
            d.annotations.forEach { appendLine(indent + annotation(it)) }
            when (d) {
                is RecordDecl -> append(record(d, indent))
                is EnumDecl -> append(enum(d, indent))
                is UnionDecl -> append(union(d, indent))
                is AliasDecl ->
                    appendLine(indent + "alias ${d.name} = ${typeExpr(d.type)}" + trailing(d.span))
            }
        }

        internal fun trailing(span: Span) =
            comments.trailing[span]?.joinToString("") { "  " + it.text } ?: ""

        internal fun docLines(doc: String, indent: String) =
            doc.lines().map { if (it.isEmpty()) "$indent///" else "$indent/// $it" }

        internal fun slice(span: Span): String =
            if (span.startLine == span.endLine) {
                lines[span.startLine - 1].substring(span.startColumn - 1, span.endColumn)
            } else {
                lines[span.startLine - 1].substring(span.startColumn - 1) +
                    "\n" +
                    lines.subList(span.startLine, span.endLine - 1).joinToString("\n") {
                        it + "\n"
                    } +
                    lines[span.endLine - 1].substring(0, span.endColumn)
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
         * True when a single annotation with at most one argument can sit inline before a member.
         */
        internal fun inlineAnnotation(annotations: List<Annotation>): Boolean =
            annotations.size == 1 && annotations[0].args.size <= 1

        internal fun record(d: RecordDecl, indent: String): String {
            if (canOneLineRecord(d)) {
                val body = d.fields.joinToString(" ") { fieldOneLine(it) }
                val oneLine =
                    indent + "record ${d.name} {" + (if (body.isEmpty()) "" else " $body") + " }"
                if (oneLine.length <= LINE_WIDTH) return oneLine + "\n"
            }
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
            if (d.reserved.isNotEmpty()) {
                members +=
                    BodyMember(
                        d.reserved.first().span,
                        isNested = false,
                        reservedLines(d.reserved, innerIndent),
                    )
            }
            val bodyLines = assembleBody(members).toMutableList()
            comments.endOfBlock[d.span]?.forEach { bodyLines += innerIndent + it.text }
            return buildString {
                appendLine(indent + "record ${d.name} {")
                bodyLines.forEach { appendLine(it) }
                appendLine(indent + "}")
            }
        }

        internal fun enum(d: EnumDecl, indent: String): String {
            if (canOneLineEnum(d)) {
                val body = d.values.joinToString(", ") { valueOneLine(it) }
                val oneLine =
                    indent + "enum ${d.name} {" + (if (body.isEmpty()) "" else " $body") + " }"
                if (oneLine.length <= LINE_WIDTH) return oneLine + "\n"
            }
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
            if (d.reserved.isNotEmpty()) {
                members +=
                    BodyMember(
                        d.reserved.first().span,
                        isNested = false,
                        reservedLines(d.reserved, innerIndent),
                    )
            }
            val bodyLines = assembleBody(members).toMutableList()
            comments.endOfBlock[d.span]?.forEach { bodyLines += innerIndent + it.text }
            return buildString {
                appendLine(indent + "enum ${d.name} {")
                bodyLines.forEach { appendLine(it) }
                appendLine(indent + "}")
            }
        }

        internal fun union(d: UnionDecl, indent: String): String {
            if (canOneLineUnion(d)) {
                val oneLine =
                    indent +
                        "union ${d.name} = " +
                        d.members.joinToString(" | ") { unionMemberOneLine(it) }
                if (oneLine.length <= LINE_WIDTH) return oneLine + "\n"
            }
            val innerIndent = indent + INDENT
            return buildString {
                appendLine(indent + "union ${d.name} =")
                d.members.forEach { m ->
                    comments.leading[m.span]?.forEach { appendLine(innerIndent + it.text) }
                    m.doc?.let { docLines(it, innerIndent).forEach { l -> appendLine(l) } }
                    appendLine(innerIndent + "| " + unionMemberOneLine(m) + trailing(m.span))
                }
                comments.endOfBlock[d.span]?.forEach { appendLine(innerIndent + it.text) }
            }
        }
    }
}
