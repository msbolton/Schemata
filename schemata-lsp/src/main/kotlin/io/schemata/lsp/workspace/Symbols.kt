package io.schemata.lsp.workspace

import io.schemata.core.Hoisting
import io.schemata.core.ir.QualifiedName
import io.schemata.lang.Span
import io.schemata.lang.ast.Declaration
import io.schemata.lang.ast.FieldDecl
import io.schemata.lang.ast.RecordDecl
import io.schemata.lang.ast.ServiceDecl
import io.schemata.lang.ast.SourceFile
import java.util.Collections
import java.util.IdentityHashMap

/** Something a name in a schema can refer to. Two sites with equal symbols name the same thing. */
sealed interface Symbol {
    data class Declaration(val name: QualifiedName) : Symbol

    data class Namespace(val name: String) : Symbol

    /** An import alias is local to the file that writes it. */
    data class ImportAlias(val file: String, val alias: String) : Symbol

    data class Field(val owner: QualifiedName, val name: String) : Symbol

    data class EnumValue(val owner: QualifiedName, val name: String) : Symbol

    data class Service(val name: QualifiedName) : Symbol

    data class Operation(val service: QualifiedName, val name: String) : Symbol
}

/** One identifier in the source: where a symbol is defined, or where it is used. */
data class Site(val span: Span, val symbol: Symbol, val definition: Boolean)

/** A builtin type name as written (`int32`, `list`), which has no declaration to go to. */
data class BuiltinSite(val span: Span, val name: String)

data class DeclaredAt(val decl: Declaration, val file: SourceFile)

data class ServiceAt(val decl: ServiceDecl, val file: SourceFile)

/** Every site of one schema set. */
class ReferenceIndex(
    internal val sites: List<Site>,
    private val builtins: List<BuiltinSite>,
    val declarations: Map<QualifiedName, DeclaredAt>,
    val services: Map<QualifiedName, ServiceAt> = emptyMap(),
    /** Declarations that exist only as an inline enum or shape written in a field's type. */
    val hoisted: Set<QualifiedName> = emptySet(),
) {
    /** Each file's sites in position order, so a lookup need not look at every site of the set. */
    private val byFile: Map<String, SortedSites> by lazy {
        sites.withIndex().groupBy({ it.value.span.file }, { it }).mapValues { (_, listed) ->
            SortedSites(listed)
        }
    }

    /**
     * The site under a compiler position; a cursor just past a name's last character counts, but
     * only when no site covers the position itself. Where several sites cover it, the one listed
     * first wins.
     */
    fun at(file: String, line: Int, column: Int): Site? {
        val inFile = byFile[file] ?: return null
        return inFile.find(line, column, slack = 0) ?: inFile.find(line, column, slack = 1)
    }

    fun builtinAt(file: String, line: Int, column: Int): BuiltinSite? =
        builtins.firstOrNull { it.span.file == file && it.span.contains(line, column, slack = 1) }

    fun definitions(symbol: Symbol): List<Span> = spans(symbol, definition = true)

    fun references(symbol: Symbol): List<Span> = spans(symbol, definition = false)

    private fun spans(symbol: Symbol, definition: Boolean): List<Span> =
        sites
            .filter { it.symbol == symbol && it.definition == definition }
            .map { it.span }
            .distinct()
            .sortedWith(compareBy({ it.file }, { it.startLine }, { it.startColumn }))

    companion object {
        val EMPTY = ReferenceIndex(emptyList(), emptyList(), emptyMap())
    }
}

/**
 * One file's sites ordered by where they start. [reach] holds, for each position in that order, the
 * furthest end among the sites up to it, which tells a backwards scan when nothing earlier can
 * still cover the position.
 */
private class SortedSites(listed: List<IndexedValue<Site>>) {
    private val sorted = listed.sortedBy { key(it.value.span.startLine, it.value.span.startColumn) }
    private val starts = LongArray(sorted.size) { key(sorted[it].value.span) }
    private val reach =
        LongArray(sorted.size).also { reach ->
            var furthest = Long.MIN_VALUE
            sorted.forEachIndexed { i, site ->
                furthest = maxOf(furthest, key(site.value.span.endLine, site.value.span.endColumn))
                reach[i] = furthest
            }
        }

    /** The earliest-listed site covering the position, allowing [slack] columns past its end. */
    fun find(line: Int, column: Int, slack: Int): Site? {
        val at = key(line, column)
        var i = firstAfter(at) - 1
        var best: IndexedValue<Site>? = null
        while (i >= 0 && reach[i] + slack >= at) {
            val candidate = sorted[i]
            if (candidate.value.span.contains(line, column, slack)) {
                if (best == null || candidate.index < best.index) best = candidate
            }
            i--
        }
        return best?.value
    }

    /** The number of sites that start at or before [at]. */
    private fun firstAfter(at: Long): Int {
        var low = 0
        var high = starts.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (starts[middle] <= at) low = middle + 1 else high = middle
        }
        return low
    }

    private companion object {
        fun key(line: Int, column: Int): Long = (line.toLong() shl 32) or column.toLong()

        fun key(span: Span): Long = key(span.startLine, span.startColumn)
    }
}

/** Whether the span covers [line]:[column], allowing [slack] columns past its inclusive end. */
internal fun Span.contains(line: Int, column: Int, slack: Int): Boolean {
    val afterStart = line > startLine || (line == startLine && column >= startColumn)
    val beforeEnd = line < endLine || (line == endLine && column <= endColumn + slack)
    return afterStart && beforeEnd
}

/**
 * A file as the analyzer sees it: inline enums and shapes are declarations of their own, and
 * `@@timestamps` has added its two fields. Those two have no text of their own, only the
 * attribute's, so they are not symbols; [generated] says which fields they are. The analyzer
 * appends them after the fields the model wrote, which is how they are told apart from a field
 * written with an unusual span.
 */
internal class HoistedFile
private constructor(val file: SourceFile, private val generated: Set<FieldDecl>) {
    fun generated(field: FieldDecl): Boolean = field in generated

    companion object {
        fun of(written: SourceFile): HoistedFile {
            val file = Hoisting.apply(written) {}
            val generated = Collections.newSetFromMap(IdentityHashMap<FieldDecl, Boolean>())
            fun walk(before: List<Declaration>, after: List<Declaration>) {
                before.zip(after).forEach { (was, now) ->
                    if (was is RecordDecl && now is RecordDecl) {
                        generated += now.fields.drop(was.fields.size)
                        walk(was.nested, now.nested)
                    }
                }
            }
            walk(written.declarations, file.declarations)
            return HoistedFile(file, generated)
        }
    }
}
