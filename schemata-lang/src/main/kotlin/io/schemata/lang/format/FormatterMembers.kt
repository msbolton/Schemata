package io.schemata.lang.format

import io.schemata.lang.Span
import io.schemata.lang.ast.Annotation
import io.schemata.lang.ast.EnumDecl
import io.schemata.lang.ast.EnumValueDecl
import io.schemata.lang.ast.FieldDecl
import io.schemata.lang.ast.OperationDecl
import io.schemata.lang.ast.PayloadDecl
import io.schemata.lang.ast.RecordDecl
import io.schemata.lang.ast.ReservedItem
import io.schemata.lang.ast.UnionDecl
import io.schemata.lang.ast.UnionMemberDecl

/**
 * One printed member of a record or enum body, in source order. [isNested] marks a nested
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

internal fun Formatter.Printer.canOneLineRecord(d: RecordDecl): Boolean =
    d.nested.isEmpty() &&
        d.reserved.isEmpty() &&
        comments.headerTrailing[d.span].isNullOrEmpty() &&
        comments.endOfBlock[d.span].isNullOrEmpty() &&
        d.fields.all { canInlineMember(it.span, it.doc, it.annotations) }

internal fun Formatter.Printer.canOneLineEnum(d: EnumDecl): Boolean =
    d.reserved.isEmpty() &&
        comments.headerTrailing[d.span].isNullOrEmpty() &&
        comments.endOfBlock[d.span].isNullOrEmpty() &&
        d.values.all { canInlineMember(it.span, it.doc, it.annotations) }

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

internal fun Formatter.Printer.fieldOneLine(f: FieldDecl): String {
    val annPrefix = if (f.annotations.isNotEmpty()) annotation(f.annotations[0]) + " " else ""
    val ordinal = f.ordinal?.let { "#$it " } ?: ""
    val default = f.default?.let { " = ${slice(it.span)}" } ?: ""
    return "$annPrefix$ordinal${f.name}: ${typeExpr(f.type)}$default"
}

internal fun Formatter.Printer.fieldMultilineLines(
    f: FieldDecl,
    indent: String,
    ordWidth: Int,
    nameWidth: Int,
): List<String> {
    val prelude = memberPrelude(f.span, f.doc, f.annotations, f.ordinal, indent, ordWidth)
    val namePart = "${f.name}:".padEnd(nameWidth) + " "
    val default = f.default?.let { " = ${slice(it.span)}" } ?: ""
    val line =
        indent +
            prelude.annPrefix +
            prelude.ordinalPart +
            namePart +
            typeExpr(f.type) +
            default +
            trailing(f.span)
    return prelude.prefixLines + line
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
    return "$ordinal${typeExpr(m.type)}"
}

/**
 * An operation on one line, with two spaces before its binding (`get "/a/{id}"`); when that line is
 * wider than [Formatter.LINE_WIDTH] the binding moves to the next line, one level deeper. The path
 * prints as written, so its escapes stay as they were.
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
    val binding = op.binding?.let { "${it.verb} ${slice(it.pathSpan)}" }
    val trailing = trailing(op.span)
    if (binding == null) return prelude.prefixLines + (head + trailing)
    val oneLine = "$head  $binding$trailing"
    if (width(oneLine) <= Formatter.LINE_WIDTH) return prelude.prefixLines + oneLine
    return prelude.prefixLines + head + (indent + Formatter.INDENT + binding + trailing)
}

private fun Formatter.Printer.payload(p: PayloadDecl): String =
    (if (p.stream) "stream " else "") + typeExpr(p.type)

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
        is ReservedItem.Ordinals ->
            if (item.from == item.to) "#${item.from}" else "#${item.from}..#${item.to}"
        is ReservedItem.Name -> slice(item.span)
    }
