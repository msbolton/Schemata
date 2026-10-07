package io.schemata.lang.format

import io.schemata.lang.Span
import io.schemata.lang.antlr.Schemata1Lexer
import io.schemata.lang.antlr.SchemataLexer
import io.schemata.lang.ast.Annotation
import io.schemata.lang.ast.Declaration
import io.schemata.lang.ast.EnumDecl
import io.schemata.lang.ast.FieldDecl
import io.schemata.lang.ast.RecordDecl
import io.schemata.lang.ast.ReservedItem
import io.schemata.lang.ast.ServiceDecl
import io.schemata.lang.ast.SourceFile
import io.schemata.lang.ast.UnionDecl
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.Token

/** A `//` or `/* */` comment as written, with its 1-based position. */
data class Comment(val text: String, val line: Int, val column: Int, val endLine: Int)

/** Where each comment belongs, keyed by the owning element's span. */
class CommentTable(
    val fileLeading: List<Comment>,
    val leading: Map<Span, List<Comment>>,
    val trailing: Map<Span, List<Comment>>,
    val headerTrailing: Map<Span, List<Comment>>,
    val endOfBlock: Map<Span, List<Comment>>,
    val fileTrailing: List<Comment>,
) {
    companion object {
        val EMPTY =
            CommentTable(emptyList(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyList())
    }
}

/**
 * Reads the hidden-channel comments of a token stream and attaches each one to the AST element it
 * describes.
 *
 * A block is a list of members in source order: the file's own top level (its namespace, imports,
 * declarations and services), or the body of a record, enum, union or service. Placing one comment
 * walks a block's members looking, in order, for: a leaf member whose span holds the comment
 * (inside a field's multi-line type, say) — the comment trails it; a member that is itself a block
 * still open at the comment (the comment sits between that member's own braces) — recurse into it,
 * where a comment on a record's, enum's or service's header line before any of its members trails
 * the header (`record R { // c`); failing that, an earlier member ending on the comment's line,
 * before the comment's column — the comment trails it; failing that, the next member starting after
 * the comment — the comment leads it; and failing all of those, the comment sits after the block's
 * last member, so it belongs to that block's end (or, at the file's own top level, to the end of
 * the file). A union has no closing token, so its last member always holds any comment that is
 * still inside the union.
 *
 * Before any of that, a comment that falls inside a member's own span but before its ordinal or
 * name (its doc and annotations come first in the grammar, so this is the gap between them and the
 * keyword or ordinal) is attached to that member directly: trailing the annotation it shares a line
 * with, or leading the member itself when it shares no annotation's line. Otherwise a block
 * element's own prefix would wrongly be read as part of its body. A union member has no
 * annotations, only an optional doc, so this always reduces to leading the member for it — a `///`
 * line cannot carry a trailing `//`, since the doc token itself runs to the end of the line.
 *
 * The file's own header (its doc and annotations, before the namespace) follows the same idea: a
 * comment before the doc (or before everything, when there is no doc) leads the file, one on a file
 * annotation's line trails that annotation, and any other leads the next annotation, or the
 * namespace when no annotation follows.
 *
 * A `//` comment runs to the end of its line, so a line can end in at most one of them. When a
 * second comment is due to trail the same element (the first came from inside a multi-line type,
 * say), the earlier `//` comment moves to lead that element instead (for an annotation, the element
 * that owns it), where it prints on its own line above.
 */
object Comments {
    fun collect(tokens: CommonTokenStream): List<Comment> =
        collect(tokens, SchemataLexer.LINE_COMMENT, SchemataLexer.BLOCK_COMMENT)

    /** [collect] for a token stream of the 1.x lexer, whose token types are numbered apart. */
    fun collectV1(tokens: CommonTokenStream): List<Comment> =
        collect(tokens, Schemata1Lexer.LINE_COMMENT, Schemata1Lexer.BLOCK_COMMENT)

    private fun collect(tokens: CommonTokenStream, line: Int, block: Int): List<Comment> {
        tokens.fill()
        return tokens.tokens
            .filter { it.channel == Token.HIDDEN_CHANNEL && (it.type == line || it.type == block) }
            .map {
                Comment(
                    it.text,
                    it.line,
                    it.charPositionInLine + 1,
                    it.line + it.text.count { c -> c == '\n' },
                )
            }
    }

    fun attach(file: SourceFile, comments: List<Comment>, source: String): CommentTable {
        if (comments.isEmpty()) return CommentTable.EMPTY
        val t = Tables(source.lines())
        val topLevel =
            sortedBlock(
                listOf(leaf(file.namespace.span)) +
                    file.imports.map { leaf(it.span) } +
                    file.declarations.map { t.element(it) } +
                    file.services.map { t.element(it) }
            )
        for (c in comments) {
            if (before(c, file.namespace.span)) t.header(c, file) else t.place(c, topLevel, null)
        }
        return CommentTable(
            t.fileLeading,
            t.leading,
            t.trailing,
            t.headerTrailing,
            t.endOfBlock,
            t.fileTrailing,
        )
    }

    /**
     * One AST node that can carry comments. [members] holds a block's own members, in source order,
     * and is always empty for a leaf; [isBlock] tells the two apart even when a block happens to
     * have no members of its own, such as `record Empty {}`. [prefixEnd] is the span of the
     * element's ordinal or name — whichever comes first, an ordinal or a type for a union member —
     * and marks where its "prefix" (doc and annotations) ends; it is null for elements with no such
     * prefix (an import, a reserved statement). [annotations] are the element's own annotations,
     * used to tell a comment that shares an annotation's line from one that merely precedes the
     * element's keyword or ordinal. [hasBraces] marks a record, enum or service, whose header line
     * can carry a comment of its own.
     */
    private class Element(
        val span: Span,
        val key: Span,
        val members: List<Element>,
        val isBlock: Boolean,
        val prefixEnd: Span? = null,
        val annotations: List<Annotation> = emptyList(),
        val hasBraces: Boolean = false,
    )

    private fun leaf(
        span: Span,
        prefixEnd: Span? = null,
        annotations: List<Annotation> = emptyList(),
    ) =
        Element(
            span,
            span,
            emptyList(),
            isBlock = false,
            prefixEnd = prefixEnd,
            annotations = annotations,
        )

    private fun block(
        span: Span,
        members: List<Element>,
        prefixEnd: Span,
        annotations: List<Annotation>,
        hasBraces: Boolean,
    ) = Element(span, span, sortedBlock(members), true, prefixEnd, annotations, hasBraces)

    private fun sortedBlock(members: List<Element>) =
        members.sortedWith(compareBy({ it.span.startLine }, { it.span.startColumn }))

    /** Whether [c] ends strictly before [s] starts. */
    private fun before(c: Comment, s: Span) =
        c.endLine < s.startLine || (c.endLine == s.startLine && c.column < s.startColumn)

    /** Whether [c] lies between the first and last token of [s]. */
    private fun inside(c: Comment, s: Span) =
        !before(c, s) && (c.line < s.endLine || (c.line == s.endLine && c.column < s.endColumn))

    private fun isLineComment(c: Comment) = c.text.startsWith("//")

    private class Tables(val lines: List<String>) {
        val fileLeading = mutableListOf<Comment>()
        val leading = mutableMapOf<Span, MutableList<Comment>>()
        val trailing = mutableMapOf<Span, MutableList<Comment>>()
        val headerTrailing = mutableMapOf<Span, MutableList<Comment>>()
        val endOfBlock = mutableMapOf<Span, MutableList<Comment>>()
        val fileTrailing = mutableListOf<Comment>()

        fun element(d: Declaration): Element =
            when (d) {
                is RecordDecl ->
                    block(
                        d.span,
                        members(d),
                        d.nameSpan,
                        d.annotations.filterNot { it.block },
                        hasBraces = true,
                    )
                is EnumDecl ->
                    block(d.span, members(d), d.nameSpan, d.annotations, hasBraces = true)
                is UnionDecl ->
                    block(
                        d.span,
                        d.members.map { leaf(it.span, it.ordinalSpan ?: it.type.span) },
                        d.nameSpan,
                        d.annotations,
                        hasBraces = false,
                    )
                else -> leaf(d.span, d.nameSpan, d.annotations)
            }

        /**
         * A model's block attributes (`@@x`) are members of its body, each on its own line, so a
         * comment can trail or lead one like a field.
         */
        private fun members(d: RecordDecl): List<Element> =
            d.fields.map(::field) +
                d.nested.map(::element) +
                reservedElements(d.reserved) +
                d.annotations.filter { it.block }.map { leaf(it.span) }

        private fun members(d: EnumDecl): List<Element> =
            d.values.map { leaf(it.span, it.ordinalSpan ?: it.nameSpan, it.annotations) } +
                reservedElements(d.reserved)

        /**
         * A field whose type is an inline enum or shape is a block like a declared one, keyed by
         * the field's span: its members own the comments inside its braces, and a comment after its
         * `{` before any member trails that header line.
         */
        private fun field(f: FieldDecl): Element {
            val prefixEnd = f.ordinalSpan ?: f.nameSpan
            val shape = f.type.inlineShape
            val enum = f.type.inlineEnum
            return when {
                shape != null ->
                    block(f.span, members(shape), prefixEnd, f.annotations, hasBraces = true)
                enum != null ->
                    block(f.span, members(enum), prefixEnd, f.annotations, hasBraces = true)
                else -> leaf(f.span, prefixEnd, f.annotations)
            }
        }

        fun element(s: ServiceDecl): Element =
            block(
                s.span,
                s.operations.map { leaf(it.span, it.ordinalSpan ?: it.nameSpan, it.annotations) } +
                    reservedElements(s.reserved),
                s.nameSpan,
                s.annotations,
                hasBraces = true,
            )

        /**
         * `reserved #2, #5..#7` is one member, keyed by its first item and spanning through its
         * last; each `reserved` statement is its own member.
         */
        private fun reservedElements(reserved: List<ReservedItem>): List<Element> =
            reservedStatements(reserved, lines).map { items ->
                val first = items.first().span
                val last = items.last().span
                val span =
                    Span(
                        first.file,
                        first.startLine,
                        first.startColumn,
                        last.endLine,
                        last.endColumn,
                    )
                Element(span, first, emptyList(), isBlock = false)
            }

        fun header(c: Comment, file: SourceFile) {
            val onAnnotationLine =
                file.annotations.lastOrNull {
                    c.line in it.span.startLine..it.span.endLine && !before(c, it.span)
                }
            when {
                onAnnotationLine != null ->
                    addTrailing(onAnnotationLine.span, onAnnotationLine.span, c)
                before(c, file.span) -> fileLeading += c
                else -> {
                    // A 1.x file's annotations come before `namespace`; a 2.0 header's attributes
                    // follow `schema`, so a comment above the header leads the header itself.
                    val ns = file.namespace.span
                    val next =
                        file.annotations
                            .firstOrNull {
                                before(c, it.span) &&
                                    (it.span.startLine < ns.startLine ||
                                        (it.span.startLine == ns.startLine &&
                                            it.span.startColumn < ns.startColumn))
                            }
                            ?.span
                    leading.getOrPut(next ?: file.namespace.span) { mutableListOf() } += c
                }
            }
        }

        fun place(c: Comment, members: List<Element>, parent: Element?) {
            val prefixOwner =
                members.firstOrNull {
                    it.prefixEnd != null && !before(c, it.span) && before(c, it.prefixEnd)
                }
            if (prefixOwner != null) {
                val onAnnotationLine =
                    prefixOwner.annotations.lastOrNull {
                        c.line in it.span.startLine..it.span.endLine
                    }
                if (onAnnotationLine != null) {
                    addTrailing(onAnnotationLine.span, prefixOwner.key, c)
                } else {
                    leading.getOrPut(prefixOwner.key) { mutableListOf() } += c
                }
                return
            }
            val holder = members.firstOrNull { !it.isBlock && inside(c, it.span) }
            if (holder != null) {
                addTrailing(holder.key, holder.key, c)
                return
            }
            val container = members.firstOrNull { it.isBlock && inside(c, it.span) }
            if (container != null) {
                if (
                    container.hasBraces &&
                        c.line == container.prefixEnd!!.endLine &&
                        container.members.all { before(c, it.span) }
                ) {
                    headerTrailing.getOrPut(container.key) { mutableListOf() } += c
                } else {
                    place(c, container.members, container)
                }
                return
            }
            val trailer =
                members.lastOrNull { it.span.endLine == c.line && it.span.endColumn < c.column }
            if (trailer != null) {
                addTrailing(trailer.key, trailer.key, c)
                return
            }
            val next = members.firstOrNull { before(c, it.span) }
            if (next != null) {
                leading.getOrPut(next.key) { mutableListOf() } += c
                return
            }
            if (parent != null) endOfBlock.getOrPut(parent.key) { mutableListOf() } += c
            else fileTrailing += c
        }

        /**
         * Adds [c] to the comments trailing [key], first moving any `//` comment already there to
         * lead [owner], since nothing can follow a `//` comment on its line.
         */
        private fun addTrailing(key: Span, owner: Span, c: Comment) {
            val list = trailing.getOrPut(key) { mutableListOf() }
            val displaced = list.filter(::isLineComment)
            if (displaced.isNotEmpty()) {
                list.removeAll(displaced)
                leading.getOrPut(owner) { mutableListOf() } += displaced
            }
            list += c
        }
    }
}

/**
 * Splits the merged `reserved` items of one record or enum back into the statements they were
 * written as: two consecutive items belong to one statement when nothing but a comma (and
 * whitespace or comments) separates them in [lines], the source they were parsed from.
 */
internal fun reservedStatements(
    items: List<ReservedItem>,
    lines: List<String>,
): List<List<ReservedItem>> {
    val out = mutableListOf<MutableList<ReservedItem>>()
    items.forEachIndexed { i, item ->
        if (i > 0 && sameStatement(items[i - 1].span, item.span, lines)) out.last() += item
        else out += mutableListOf(item)
    }
    return out
}

private fun sameStatement(a: Span, b: Span, lines: List<String>): Boolean {
    val lexer = SchemataLexer(CharStreams.fromString(textBetween(a, b, lines)))
    lexer.removeErrorListeners()
    return lexer.allTokens.filter { it.channel == Token.DEFAULT_CHANNEL }.all { it.text == "," }
}

/** The source text after [a]'s last column and before [b]'s first. */
private fun textBetween(a: Span, b: Span, lines: List<String>): String {
    val first = lines[a.endLine - 1]
    val from = first.offsetByCodePoints(0, a.endColumn)
    val last = lines[b.startLine - 1]
    val to = last.offsetByCodePoints(0, b.startColumn - 1)
    if (a.endLine == b.startLine) return first.substring(from, to)
    return (listOf(first.substring(from)) +
            lines.subList(a.endLine, b.startLine - 1) +
            last.substring(0, to))
        .joinToString("\n")
}
