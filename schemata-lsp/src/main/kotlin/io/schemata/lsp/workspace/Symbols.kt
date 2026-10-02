package io.schemata.lsp.workspace

import io.schemata.core.ir.QualifiedName
import io.schemata.lang.Span
import io.schemata.lang.ast.Declaration
import io.schemata.lang.ast.SourceFile

/** Something a name in a schema can refer to. Two sites with equal symbols name the same thing. */
sealed interface Symbol {
    data class Declaration(val name: QualifiedName) : Symbol

    data class Namespace(val name: String) : Symbol

    /** An import alias is local to the file that writes it. */
    data class ImportAlias(val file: String, val alias: String) : Symbol

    data class Field(val owner: QualifiedName, val name: String) : Symbol

    data class EnumValue(val owner: QualifiedName, val name: String) : Symbol
}

/** One identifier in the source: where a symbol is defined, or where it is used. */
data class Site(val span: Span, val symbol: Symbol, val definition: Boolean)

/** A builtin type name as written (`int32`, `list`), which has no declaration to go to. */
data class BuiltinSite(val span: Span, val name: String)

data class DeclaredAt(val decl: Declaration, val file: SourceFile)

/** Every site of one schema set. */
class ReferenceIndex(
    internal val sites: List<Site>,
    private val builtins: List<BuiltinSite>,
    val declarations: Map<QualifiedName, DeclaredAt>,
) {
    /** The site under a compiler position; a cursor just past a name's last character counts. */
    fun at(file: String, line: Int, column: Int): Site? {
        val inFile = sites.filter { it.span.file == file }
        return inFile.firstOrNull { it.span.contains(line, column, slack = 0) }
            ?: inFile.firstOrNull { it.span.contains(line, column, slack = 1) }
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

/** Whether the span covers [line]:[column], allowing [slack] columns past its inclusive end. */
internal fun Span.contains(line: Int, column: Int, slack: Int): Boolean {
    val afterStart = line > startLine || (line == startLine && column >= startColumn)
    val beforeEnd = line < endLine || (line == endLine && column <= endColumn + slack)
    return afterStart && beforeEnd
}
