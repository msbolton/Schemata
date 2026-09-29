package io.schemata.lang.format

import io.schemata.lang.Span
import io.schemata.lang.antlr.SchemataLexer
import io.schemata.lang.ast.Annotation
import io.schemata.lang.ast.Declaration
import io.schemata.lang.ast.EnumDecl
import io.schemata.lang.ast.RecordDecl
import io.schemata.lang.ast.ReservedItem
import io.schemata.lang.ast.SourceFile
import io.schemata.lang.ast.UnionDecl
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.Token

/** A `//` or `/* */` comment as written, with its 1-based position. */
data class Comment(val text: String, val line: Int, val column: Int, val endLine: Int)

/** Where each comment belongs, keyed by the owning element's span. */
class CommentTable(
    val fileLeading: List<Comment>,
    val leading: Map<Span, List<Comment>>,
    val trailing: Map<Span, List<Comment>>,
    val endOfBlock: Map<Span, List<Comment>>,
    val fileTrailing: List<Comment>,
) {
    companion object {
        val EMPTY = CommentTable(emptyList(), emptyMap(), emptyMap(), emptyMap(), emptyList())
    }
}

/**
 * Reads the hidden-channel comments of a token stream and attaches each one to the AST element it
 * describes.
 *
 * A block is a list of members in source order: the file's own top level (its imports and
 * declarations), or the body of a record, enum or union. Placing one comment walks a block's
 * members looking, in order, for: a member that is itself a block still open at the comment (the
 * comment sits between that member's own braces) — recurse into it; failing that, an earlier member
 * ending on the comment's line, before the comment's column — the comment trails it; failing that,
 * the next member starting after the comment — the comment leads it; and failing all of those, the
 * comment sits after the block's last member, so it belongs to that block's end (or, at the file's
 * own top level, to the end of the file). A comment before the namespace declaration always leads
 * the file, since nothing else could own it.
 *
 * Before any of that, a comment that falls inside a member's own span but before its ordinal or
 * name (its doc and annotations come first in the grammar, so this is the gap between them and the
 * keyword or ordinal) is attached to that member directly: trailing the annotation it shares a line
 * with, or leading the member itself when it shares no annotation's line. Otherwise a block
 * element's own prefix would wrongly be read as part of its body.
 */
object Comments {
    fun collect(tokens: CommonTokenStream): List<Comment> {
        tokens.fill()
        return tokens.tokens
            .filter {
                it.channel == Token.HIDDEN_CHANNEL &&
                    (it.type == SchemataLexer.LINE_COMMENT ||
                        it.type == SchemataLexer.BLOCK_COMMENT)
            }
            .map {
                Comment(
                    it.text,
                    it.line,
                    it.charPositionInLine + 1,
                    it.line + it.text.count { c -> c == '\n' },
                )
            }
    }

    fun attach(file: SourceFile, comments: List<Comment>): CommentTable {
        if (comments.isEmpty()) return CommentTable.EMPTY
        val fileLeading = mutableListOf<Comment>()
        val leading = mutableMapOf<Span, MutableList<Comment>>()
        val trailing = mutableMapOf<Span, MutableList<Comment>>()
        val endOfBlock = mutableMapOf<Span, MutableList<Comment>>()
        val fileTrailing = mutableListOf<Comment>()
        val topLevel =
            sortedBlock(file.imports.map { leaf(it.span) } + file.declarations.map(::element))
        for (c in comments) {
            if (before(c, file.namespace.span)) {
                fileLeading += c
                continue
            }
            place(c, topLevel, parent = null, leading, trailing, endOfBlock, fileTrailing)
        }
        return CommentTable(fileLeading, leading, trailing, endOfBlock, fileTrailing)
    }

    /**
     * One AST node that can carry comments. [members] holds a block's own members, in source order,
     * and is always empty for a leaf; [isBlock] tells the two apart even when a block happens to
     * have no members of its own, such as `record Empty {}`. [prefixEnd] is the span of the
     * element's ordinal or name — whichever comes first — and marks where its "prefix" (doc and
     * annotations) ends; it is null for elements with no such prefix (an import, a union member, a
     * reserved item). [annotations] are the element's own annotations, used to tell a comment that
     * shares an annotation's line from one that merely precedes the element's keyword or ordinal.
     */
    private class Element(
        val span: Span,
        val key: Span,
        val members: List<Element>,
        val isBlock: Boolean,
        val prefixEnd: Span? = null,
        val annotations: List<Annotation> = emptyList(),
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

    private fun element(d: Declaration): Element =
        when (d) {
            is RecordDecl ->
                block(
                    d.span,
                    d.fields.map { leaf(it.span, it.ordinalSpan ?: it.nameSpan, it.annotations) } +
                        d.nested.map(::element) +
                        reservedElement(d.reserved),
                    d.nameSpan,
                    d.annotations,
                )
            is EnumDecl ->
                block(
                    d.span,
                    d.values.map { leaf(it.span, it.ordinalSpan ?: it.nameSpan, it.annotations) } +
                        reservedElement(d.reserved),
                    d.nameSpan,
                    d.annotations,
                )
            is UnionDecl ->
                block(d.span, d.members.map { leaf(it.span) }, d.nameSpan, d.annotations)
            else -> leaf(d.span, d.nameSpan, d.annotations)
        }

    private fun block(
        span: Span,
        members: List<Element>,
        prefixEnd: Span? = null,
        annotations: List<Annotation> = emptyList(),
    ) =
        Element(
            span,
            span,
            sortedBlock(members),
            isBlock = true,
            prefixEnd = prefixEnd,
            annotations = annotations,
        )

    private fun sortedBlock(members: List<Element>) =
        members.sortedWith(compareBy({ it.span.startLine }, { it.span.startColumn }))

    /**
     * `reserved #2, #5..#7` is one member, keyed by the first item and spanning through the last.
     */
    private fun reservedElement(reserved: List<ReservedItem>): List<Element> {
        val first = reserved.firstOrNull() ?: return emptyList()
        val last = reserved.last()
        val span =
            Span(
                first.span.file,
                first.span.startLine,
                first.span.startColumn,
                last.span.endLine,
                last.span.endColumn,
            )
        return listOf(Element(span, first.span, emptyList(), isBlock = false))
    }

    /** Whether [c] ends strictly before [s] starts. */
    private fun before(c: Comment, s: Span) =
        c.endLine < s.startLine || (c.endLine == s.startLine && c.column < s.startColumn)

    private fun place(
        c: Comment,
        members: List<Element>,
        parent: Element?,
        leading: MutableMap<Span, MutableList<Comment>>,
        trailing: MutableMap<Span, MutableList<Comment>>,
        endOfBlock: MutableMap<Span, MutableList<Comment>>,
        fileTrailing: MutableList<Comment>,
    ) {
        val prefixOwner =
            members.firstOrNull {
                it.prefixEnd != null && !before(c, it.span) && before(c, it.prefixEnd)
            }
        if (prefixOwner != null) {
            val onAnnotationLine =
                prefixOwner.annotations.lastOrNull { c.line in it.span.startLine..it.span.endLine }
            if (onAnnotationLine != null) {
                trailing.getOrPut(onAnnotationLine.span) { mutableListOf() } += c
            } else {
                leading.getOrPut(prefixOwner.key) { mutableListOf() } += c
            }
            return
        }
        val container =
            members.firstOrNull {
                it.isBlock &&
                    it.span.startLine <= c.line &&
                    !before(c, it.span) &&
                    (it.span.endLine > c.endLine ||
                        (it.span.endLine == c.endLine && it.span.endColumn > c.column))
            }
        if (container != null) {
            place(c, container.members, container, leading, trailing, endOfBlock, fileTrailing)
            return
        }
        val trailer =
            members.lastOrNull { it.span.endLine == c.line && it.span.endColumn < c.column }
        if (trailer != null) {
            trailing.getOrPut(trailer.key) { mutableListOf() } += c
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
}
