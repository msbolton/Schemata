package io.schemata.lang.format

import io.schemata.lang.SchemataText
import io.schemata.lang.Span
import io.schemata.lang.ast.Annotation
import io.schemata.lang.ast.BindingDecl
import io.schemata.lang.ast.EnumValueDecl
import io.schemata.lang.ast.ErrorResponseDecl
import io.schemata.lang.ast.ErrorsDecl
import io.schemata.lang.ast.OperationDecl
import io.schemata.lang.ast.PayloadDecl
import io.schemata.lang.ast.ReservedItem
import io.schemata.lang.ast.UnionDecl
import io.schemata.lang.ast.UnionMemberDecl

/**
 * One printed member of a model, enum, or service body, in source order. [isNested] marks a nested
 * declaration, which gets a blank line on each side when the body is assembled.
 */
internal class BodyMember(span: Span, val isNested: Boolean, val lines: List<String>) {
    val line = span.startLine
    val column = span.startColumn
}

/** Sorts members by source position and inserts one blank line around each nested declaration. */
internal fun assembleBody(members: List<BodyMember>): List<String> {
    val sorted = members.sortedWith(compareBy({ it.line }, { it.column }))
    val out = mutableListOf<String>()
    sorted.forEachIndexed { i, m ->
        if (i > 0 && (m.isNested || sorted[i - 1].isNested)) out += ""
        out += m.lines
    }
    return out
}

/** The widest `#n` among the ordinals that are present, or 0 when none of them has one. */
internal fun ordinalWidth(ordinals: List<Int?>): Int =
    ordinals.filterNotNull().maxOfOrNull { "#$it".length } ?: 0

/** Whether a field or enum value with this span/doc/annotations can join a one-line body. */
internal fun Formatter.Printer.canInlineMember(
    span: Span,
    doc: String?,
    annotations: List<Annotation>,
): Boolean =
    doc == null &&
        comments.leading[span].isNullOrEmpty() &&
        comments.trailing[span].isNullOrEmpty() &&
        canInline(annotations)

/**
 * A union has no closing token, so a comment after its last member reads back as the union's own
 * trailing comment: it does not force a break unless both end in `//` comments that cannot share
 * the line, per [lastMemberAbove].
 */
internal fun Formatter.Printer.canOneLineUnion(d: UnionDecl): Boolean =
    d.members.all { it.doc == null && comments.leading[it.span].isNullOrEmpty() } &&
        d.members.dropLast(1).all { comments.trailing[it.span].isNullOrEmpty() } &&
        lastMemberAbove(d).isEmpty()

/**
 * The last member's `//` comment when the union has a trailing comment of its own: both are due at
 * the end of the last member's line, where nothing can follow a `//` comment, so the member's
 * prints on its own line above the member.
 */
internal fun Formatter.Printer.lastMemberAbove(d: UnionDecl): List<Comment> =
    if (comments.trailing[d.span].isNullOrEmpty()) emptyList()
    else comments.trailing[d.members.last().span].orEmpty().filter { it.text.startsWith("//") }

/** The prelude common to a field's and an enum value's printed line, in a multi-line body. */
internal class MemberPrelude(
    val prefixLines: List<String>,
    val annPrefix: String,
    val ordinalPart: String,
)

internal fun Formatter.Printer.memberPrelude(
    span: Span,
    doc: String?,
    annotations: List<Annotation>,
    ordinal: Int?,
    indent: String,
    ordWidth: Int,
): MemberPrelude {
    val prefix = mutableListOf<String>()
    comments.leading[span]?.forEach { prefix += indent + it.text }
    doc?.let { prefix += docLines(it, indent) }
    val inline = canInline(annotations)
    if (annotations.isNotEmpty() && !inline) {
        annotations.forEach { prefix += indent + annotation(it) + trailing(it.span) }
    }
    val annPrefix = if (inline && annotations.isNotEmpty()) annotation(annotations[0]) + " " else ""
    val ordinalText = ordinal?.let { "#$it" } ?: ""
    val ordinalPart = if (ordWidth > 0) ordinalText.padEnd(ordWidth) + " " else ""
    return MemberPrelude(prefix, annPrefix, ordinalPart)
}

internal fun Formatter.Printer.valueOneLine(v: EnumValueDecl): String {
    val annPrefix = if (v.annotations.isNotEmpty()) annotation(v.annotations[0]) + " " else ""
    val ordinal = v.ordinal?.let { "#$it " } ?: ""
    return "$annPrefix$ordinal${v.name}"
}

internal fun Formatter.Printer.valueMultilineLines(
    v: EnumValueDecl,
    indent: String,
    ordWidth: Int,
): List<String> {
    val prelude = memberPrelude(v.span, v.doc, v.annotations, v.ordinal, indent, ordWidth)
    val line = indent + prelude.annPrefix + prelude.ordinalPart + v.name + trailing(v.span)
    return prelude.prefixLines + line
}

internal fun Formatter.Printer.unionMemberOneLine(m: UnionMemberDecl): String {
    val ordinal = m.ordinal?.let { "#$it " } ?: ""
    val options = if (m.options.isEmpty()) "" else " " + options(m.options)
    return "$ordinal${typeExpr(m.type)}$options"
}

/**
 * An operation on one line, with two spaces before its binding (`post "/a" 201`, the success code
 * one space after the path); when that line is wider than [Formatter.LINE_WIDTH] the binding moves
 * to the next line, one level deeper, and so it does when the author wrote it on a later line than
 * the signature. The path is never split, so a path wider than the line stays whole. It prints as
 * written, so its escapes stay as they were. An `errors` clause follows on the last of those lines,
 * two spaces on, per [errorsLines]; the operation's trailing comment then ends the clause.
 */
internal fun Formatter.Printer.operationLines(
    op: OperationDecl,
    indent: String,
    ordWidth: Int,
): List<String> {
    val prelude = memberPrelude(op.span, op.doc, op.annotations, op.ordinal, indent, ordWidth)
    val request = op.request?.let { payload(it) } ?: ""
    val response = op.response?.let { ": " + payload(it) } ?: ""
    val head = indent + prelude.annPrefix + prelude.ordinalPart + "${op.name}($request)$response"
    val trailing = trailing(op.span)
    val clause = op.errors
    val signatureTrailing = if (clause == null) trailing else ""
    val b = op.binding
    val signature =
        if (b == null) listOf(head)
        else {
            val binding = bindingText(b)
            val oneLine = "$head  $binding"
            val signatureEnd =
                maxOf(
                    op.nameSpan.endLine,
                    op.request?.span?.endLine ?: 0,
                    op.response?.span?.endLine ?: 0,
                )
            val brokenByAuthor = b.verbSpan.startLine > signatureEnd
            if (!brokenByAuthor && width(oneLine + signatureTrailing) <= Formatter.LINE_WIDTH)
                listOf(oneLine)
            else listOf(head, indent + Formatter.INDENT + binding)
        }
    val last = signature.last()
    val tail =
        if (clause == null) listOf(last + trailing)
        else errorsLines(clause, op.span, "$last  ", indent, trailing)
    return prelude.prefixLines + signature.dropLast(1) + tail
}

private fun Formatter.Printer.payload(p: PayloadDecl): String =
    (if (p.stream) "stream " else "") + typeExpr(p.type)

/** `get "/a/{id}"`, or `post "/a" 201` with its success code. */
private fun Formatter.Printer.bindingText(b: BindingDecl): String =
    "${b.verb} ${slice(b.pathSpan)}" + (b.status?.let { " $it" } ?: "")

/**
 * An `errors` clause after [open], keyed by [key] for its comments (the operation's span, or the
 * clause's own for a service's), with [close] after its `}`. It is one line, its entries two spaces
 * apart (`errors { 404 Problem 410 }`, or `errors {}`), when the author wrote it on one line, no
 * comment sits inside it, and the line fits in [Formatter.LINE_WIDTH]; otherwise its `{` ends the
 * first line, each entry takes its own line one level deeper than [indent], and its `}` starts the
 * last line at [indent].
 */
internal fun Formatter.Printer.errorsLines(
    e: ErrorsDecl,
    key: Span,
    open: String,
    indent: String,
    close: String,
): List<String> {
    val shareable =
        (e.responses.isEmpty() || e.span.startLine == e.span.endLine) &&
            comments.headerTrailing[key].isNullOrEmpty() &&
            comments.endOfBlock[key].isNullOrEmpty() &&
            e.responses.all {
                comments.leading[it.span].isNullOrEmpty() &&
                    comments.trailing[it.span].isNullOrEmpty()
            }
    if (shareable) {
        val body =
            if (e.responses.isEmpty()) "{}"
            else "{ " + e.responses.joinToString("  ") { errorResponse(it) } + " }"
        val line = "${open}errors $body$close"
        if (width(line) <= Formatter.LINE_WIDTH) return listOf(line)
    }
    val inner = indent + Formatter.INDENT
    val out = mutableListOf("${open}errors {" + lineEnd(comments.headerTrailing[key]))
    e.responses.forEach { r ->
        comments.leading[r.span]?.forEach { out += inner + it.text }
        out += inner + errorResponse(r) + trailing(r.span)
    }
    comments.endOfBlock[key]?.forEach { out += inner + it.text }
    out += "$indent}$close"
    return out
}

/** `404 Problem`, `410`, or `default Problem`. */
private fun Formatter.Printer.errorResponse(r: ErrorResponseDecl): String =
    (r.code?.toString() ?: "default") + (r.body?.let { " " + typeExpr(it) } ?: "")

/** A service's own `errors` clause: a member of its body, with its leading comments above it. */
internal fun Formatter.Printer.serviceErrorsLines(e: ErrorsDecl, indent: String): List<String> =
    comments.leading[e.span].orEmpty().map { indent + it.text } +
        errorsLines(e, e.span, indent, indent, trailing(e.span))

/**
 * One body member per `reserved` statement of a record, enum or service, each at its own place
 * among the other members and keyed by its first item for comments, per [CommentTable].
 */
internal fun Formatter.Printer.reservedMembers(
    items: List<ReservedItem>,
    indent: String,
): List<BodyMember> =
    reservedStatements(items, lines).map {
        BodyMember(it.first().span, isNested = false, reservedLines(it, indent))
    }

/** `reserved #2, #5..#7`: one statement, with the comments keyed by its first item. */
private fun Formatter.Printer.reservedLines(
    items: List<ReservedItem>,
    indent: String,
): List<String> {
    val first = items.first()
    val out = mutableListOf<String>()
    comments.leading[first.span]?.forEach { out += indent + it.text }
    val text = items.joinToString(", ") { reservedItemText(it) }
    out += indent + "reserved $text" + trailing(first.span)
    return out
}

private fun Formatter.Printer.reservedItemText(item: ReservedItem): String =
    when (item) {
        is ReservedItem.Ordinals -> SchemataText.ordinalRange(item.from, item.to)
        is ReservedItem.Name -> slice(item.span)
    }
