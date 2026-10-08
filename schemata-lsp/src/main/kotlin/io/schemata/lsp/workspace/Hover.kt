package io.schemata.lsp.workspace

import io.schemata.core.DeclarationIndex
import io.schemata.lang.Span
import io.schemata.lang.ast.AliasDecl
import io.schemata.lang.ast.Declaration
import io.schemata.lang.ast.EnumDecl
import io.schemata.lang.ast.RecordDecl

/** One line per builtin type name, shown when the cursor rests on it. */
object BuiltinDocs {
    val text: Map<String, String> =
        mapOf(
            "bool" to "true or false",
            "int32" to "a 32-bit signed integer; refinements: min, max",
            "int64" to "a 64-bit signed integer; refinements: min, max",
            "float32" to "a 32-bit IEEE 754 floating-point number; refinements: min, max",
            "float64" to "a 64-bit IEEE 754 floating-point number; refinements: min, max",
            "decimal" to
                "an exact decimal number, written decimal(precision, scale); refinements: min, max",
            "string" to "Unicode text; refinements: min, max (length), pattern",
            "bytes" to "a byte sequence; refinements: min, max (length)",
            "uuid" to "a universally unique identifier",
            "date" to "a calendar date with no time or zone",
            "time" to "a time of day with no date or zone",
            "instant" to "a point in time, in UTC",
            "duration" to "a length of time",
            "list" to "list<T>: an ordered collection; refinements: min, max (item count)",
            "map" to
                "map<K, V>: entries keyed by string, int32, or int64; refinements: min, max (entry count)",
        )
}

/** Markdown for the editor's hover, and the range of the name it describes. */
data class HoverInfo(val markdown: String, val range: TextRange)

private val whitespace = Regex("\\s+")

private fun block(signature: String, doc: String?): String =
    "```schemata\n$signature\n```" + (doc?.takeIf { it.isNotBlank() }?.let { "\n\n$it" } ?: "")

/** The source text of [span], on one line. */
private fun SetAnalysis.written(span: Span): String =
    snapshot(span.file)?.lines?.slice(span)?.replace(whitespace, " ")?.trim() ?: ""

/** From the character after [after] to the end of [whole]; both lie in the same file. */
private fun rest(after: Span, whole: Span): Span =
    Span(whole.file, after.endLine, after.endColumn + 1, whole.endLine, whole.endColumn)

private fun kindWord(decl: Declaration): String =
    if (decl is RecordDecl) "model" else DeclarationIndex.kindOf(decl)

/** What to show for [symbol], or null when the set no longer declares it. */
internal fun hoverText(analysis: SetAnalysis, symbol: Symbol): String? =
    when (symbol) {
        is Symbol.Declaration -> {
            val at = analysis.index.declarations[symbol.name]
            at?.let {
                val decl = it.decl
                val tail = if (decl is AliasDecl) " = " + analysis.written(decl.type.span) else ""
                block("${kindWord(decl)} ${symbol.name}$tail", decl.doc)
            }
        }
        is Symbol.Field -> {
            val model = analysis.index.declarations[symbol.owner]?.decl as? RecordDecl
            model
                ?.fields
                ?.firstOrNull { it.name == symbol.name }
                ?.let {
                    val tail = analysis.written(rest(it.nameSpan, it.span))
                    block("field ${symbol.owner}.${it.name} $tail", it.doc)
                }
        }
        is Symbol.EnumValue -> {
            val enum = analysis.index.declarations[symbol.owner]?.decl as? EnumDecl
            enum
                ?.values
                ?.firstOrNull { it.name == symbol.name }
                ?.let { block("value ${symbol.owner}.${it.name}", it.doc) }
        }
        is Symbol.Service ->
            analysis.index.services[symbol.name]?.let {
                block("service ${symbol.name}", it.decl.doc)
            }
        is Symbol.Operation -> {
            val at = analysis.index.services[symbol.service]
            val op = at?.decl?.operations?.firstOrNull { it.name == symbol.name }
            val lines = at?.let { analysis.snapshot(it.file.path)?.lines }
            if (op == null || lines == null) null
            else {
                // The operation's line as the formatter prints it, without its annotations.
                val ordinal = op.ordinal?.let { "#$it " } ?: ""
                val binding = op.binding?.let { "  ${it.verb} ${lines.slice(it.pathSpan)}" } ?: ""
                block("$ordinal${op.name}${payloadsText(op, lines::slice)}$binding", op.doc)
            }
        }
        is Symbol.Namespace -> {
            val declaring =
                analysis.files.sortedBy { it.path }.filter { it.namespace.name == symbol.name }
            if (declaring.isEmpty()) null
            else block("schema ${symbol.name}", declaring.firstNotNullOfOrNull { it.doc })
        }
        is Symbol.ImportAlias -> {
            val import =
                analysis.files
                    .firstOrNull { it.path == symbol.file }
                    ?.imports
                    ?.firstOrNull { it.alias == symbol.alias }
            import?.let { block("import ${it.namespace} as ${symbol.alias}", null) }
        }
    }

internal fun builtinHover(name: String): String =
    block(name, null) + "\n\n" + BuiltinDocs.text.getValue(name)
