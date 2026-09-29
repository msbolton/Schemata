package io.schemata.lang.format

import io.schemata.lang.Span
import io.schemata.lang.ast.EnumDecl
import io.schemata.lang.ast.EnumValueDecl
import io.schemata.lang.ast.FieldDecl
import io.schemata.lang.ast.RecordDecl
import io.schemata.lang.ast.ReservedItem
import io.schemata.lang.ast.UnionDecl
import io.schemata.lang.ast.UnionMemberDecl

/**
 * One printed member of a record, enum or union body, in source order. [isNested] marks a nested
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

internal fun Formatter.Printer.canInlineField(f: FieldDecl): Boolean =
    f.doc == null &&
        comments.leading[f.span].isNullOrEmpty() &&
        comments.trailing[f.span].isNullOrEmpty() &&
        (f.annotations.isEmpty() || inlineAnnotation(f.annotations))

internal fun Formatter.Printer.canInlineValue(v: EnumValueDecl): Boolean =
    v.doc == null &&
        comments.leading[v.span].isNullOrEmpty() &&
        comments.trailing[v.span].isNullOrEmpty() &&
        (v.annotations.isEmpty() || inlineAnnotation(v.annotations))

internal fun Formatter.Printer.canOneLineRecord(d: RecordDecl): Boolean =
    d.nested.isEmpty() &&
        d.reserved.isEmpty() &&
        comments.endOfBlock[d.span].isNullOrEmpty() &&
        d.fields.all { canInlineField(it) }

internal fun Formatter.Printer.canOneLineEnum(d: EnumDecl): Boolean =
    d.reserved.isEmpty() &&
        comments.endOfBlock[d.span].isNullOrEmpty() &&
        d.values.all { canInlineValue(it) }

internal fun Formatter.Printer.canOneLineUnion(d: UnionDecl): Boolean =
    comments.endOfBlock[d.span].isNullOrEmpty() &&
        d.members.all {
            it.doc == null &&
                comments.leading[it.span].isNullOrEmpty() &&
                comments.trailing[it.span].isNullOrEmpty()
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
    val out = mutableListOf<String>()
    comments.leading[f.span]?.forEach { out += indent + it.text }
    f.doc?.let { out += docLines(it, indent) }
    val inline = f.annotations.isNotEmpty() && inlineAnnotation(f.annotations)
    if (f.annotations.isNotEmpty() && !inline)
        f.annotations.forEach { out += indent + annotation(it) }
    val annPrefix = if (inline) annotation(f.annotations[0]) + " " else ""
    val ordinalText = f.ordinal?.let { "#$it" } ?: ""
    val ordinalPart = if (ordWidth > 0) ordinalText.padEnd(ordWidth) + " " else ""
    val namePart = "${f.name}:".padEnd(nameWidth) + " "
    val default = f.default?.let { " = ${slice(it.span)}" } ?: ""
    out +=
        indent + annPrefix + ordinalPart + namePart + typeExpr(f.type) + default + trailing(f.span)
    return out
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
    val out = mutableListOf<String>()
    comments.leading[v.span]?.forEach { out += indent + it.text }
    v.doc?.let { out += docLines(it, indent) }
    val inline = v.annotations.isNotEmpty() && inlineAnnotation(v.annotations)
    if (v.annotations.isNotEmpty() && !inline)
        v.annotations.forEach { out += indent + annotation(it) }
    val annPrefix = if (inline) annotation(v.annotations[0]) + " " else ""
    val ordinalText = v.ordinal?.let { "#$it" } ?: ""
    val ordinalPart = if (ordWidth > 0) ordinalText.padEnd(ordWidth) + " " else ""
    out += indent + annPrefix + ordinalPart + v.name + trailing(v.span)
    return out
}

internal fun Formatter.Printer.unionMemberOneLine(m: UnionMemberDecl): String {
    val ordinal = m.ordinal?.let { "#$it " } ?: ""
    return "$ordinal${typeExpr(m.type)}"
}

/**
 * `reserved #2, #5..#7` merges every `reserved` statement of a record or enum into one printed
 * statement, positioned at the first item and keyed by it for comments, per [CommentTable].
 */
internal fun Formatter.Printer.reservedLines(
    items: List<ReservedItem>,
    indent: String,
): List<String> {
    val first = items.first()
    val out = mutableListOf<String>()
    comments.leading[first.span]?.forEach { out += indent + it.text }
    val text = items.joinToString(", ") { slice(it.span) }
    out += indent + "reserved $text" + trailing(first.span)
    return out
}
