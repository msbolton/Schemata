package io.schemata.lsp.workspace

import io.schemata.core.ir.QualifiedName
import io.schemata.lang.Span
import io.schemata.lang.ast.EnumDecl
import io.schemata.lang.ast.RecordDecl

/** One replacement in a file, in editor coordinates. */
data class TextEdit(val range: TextRange, val newText: String)

sealed interface RenameResult {
    /** Replacements per file path. */
    data class Edits(val edits: Map<String, List<TextEdit>>) : RenameResult

    /** Why the rename cannot be done, in words for the user. */
    data class Refused(val message: String) : RenameResult
}

private val identifier = Regex("[A-Za-z_][A-Za-z0-9_]*")

private val keywords =
    setOf(
        "namespace",
        "import",
        "as",
        "record",
        "enum",
        "union",
        "alias",
        "reserved",
        "true",
        "false",
        "null",
        "service",
        "operation",
        "stream",
    )

/** A range in one file, in editor coordinates. */
data class Location(val path: String, val range: TextRange)

/**
 * What the editor asks about a position. Every answer is empty when the file is unknown, when its
 * current text does not parse (its snapshot's positions are then stale), or when no symbol is under
 * the position. Other files of the set answer from their snapshots.
 */
class Queries(private val workspace: Workspace) {
    internal class Hit(val analysis: SetAnalysis, val snapshot: Snapshot, val site: Site)

    internal fun analysisOf(path: String): Pair<SetAnalysis, Snapshot>? {
        val document = workspace.document(path) ?: return null
        if (document.broken) return null
        val snapshot = document.snapshot ?: return null
        return workspace.analysis(workspace.keyOf(path)) to snapshot
    }

    internal fun hit(path: String, position: TextPosition): Hit? {
        val (analysis, snapshot) = analysisOf(path) ?: return null
        val (line, column) = snapshot.lines.toCompiler(position)
        val site = analysis.index.at(path, line, column) ?: return null
        return Hit(analysis, snapshot, site)
    }

    internal fun location(analysis: SetAnalysis, span: Span): Location? =
        analysis.snapshot(span.file)?.let { Location(span.file, it.lines.range(span)) }

    fun definition(path: String, position: TextPosition): List<Location> {
        val hit = hit(path, position) ?: return emptyList()
        return hit.analysis.index.definitions(hit.site.symbol).mapNotNull {
            location(hit.analysis, it)
        }
    }

    fun references(
        path: String,
        position: TextPosition,
        includeDeclaration: Boolean,
    ): List<Location> {
        val hit = hit(path, position) ?: return emptyList()
        val index = hit.analysis.index
        val spans =
            (if (includeDeclaration) index.definitions(hit.site.symbol) else emptyList()) +
                index.references(hit.site.symbol)
        return spans.mapNotNull { location(hit.analysis, it) }
    }

    fun hover(path: String, position: TextPosition): HoverInfo? {
        val (analysis, snapshot) = analysisOf(path) ?: return null
        val (line, column) = snapshot.lines.toCompiler(position)
        analysis.index.at(path, line, column)?.let { site ->
            val text = hoverText(analysis, site.symbol) ?: return null
            return HoverInfo(text, snapshot.lines.range(site.span))
        }
        return analysis.index.builtinAt(path, line, column)?.let {
            HoverInfo(builtinHover(it.name), snapshot.lines.range(it.span))
        }
    }

    fun symbols(path: String): List<OutlineNode> {
        val (_, snapshot) = analysisOf(path) ?: return emptyList()
        return outline(snapshot)
    }

    /** The range of the name under the cursor when it can be renamed. */
    fun prepareRename(path: String, position: TextPosition): TextRange? {
        val hit = hit(path, position) ?: return null
        if (hit.site.symbol is Symbol.Namespace) return null
        return hit.snapshot.lines.range(hit.site.span)
    }

    /**
     * Replaces a symbol's definition and every reference in its set. Reserved name strings and
     * target `name` overrides are plain strings, not references, and are left as they are.
     */
    fun rename(path: String, position: TextPosition, newName: String): RenameResult {
        val hit = hit(path, position) ?: return RenameResult.Refused("nothing to rename here")
        val symbol = hit.site.symbol
        if (symbol is Symbol.Namespace) {
            return RenameResult.Refused(
                "a namespace cannot be renamed; it is the name its files declare"
            )
        }
        hit.analysis.members
            .firstOrNull { it.broken }
            ?.let {
                val file = it.path.substringAfterLast('/').substringAfterLast('\\')
                return RenameResult.Refused("fix the syntax errors in $file before renaming")
            }
        if (!identifier.matches(newName)) {
            return RenameResult.Refused("'$newName' is not a valid name")
        }
        if (newName in keywords) return RenameResult.Refused("'$newName' is a keyword")
        collision(hit.analysis, symbol, newName)?.let {
            return RenameResult.Refused(it)
        }
        val index = hit.analysis.index
        val edits =
            (index.definitions(symbol) + index.references(symbol))
                .mapNotNull { location(hit.analysis, it) }
                .groupBy({ it.path }, { TextEdit(it.range, newName) })
        return RenameResult.Edits(edits)
    }

    /** A message when [newName] is already taken where [symbol] lives. */
    private fun collision(analysis: SetAnalysis, symbol: Symbol, newName: String): String? {
        val declarations = analysis.index.declarations
        return when (symbol) {
            is Symbol.Declaration -> {
                val parent = symbol.name.path.dropLast(1)
                val sibling = QualifiedName(symbol.name.namespace, parent + newName)
                val scope =
                    if (parent.isEmpty()) symbol.name.namespace
                    else QualifiedName(symbol.name.namespace, parent).toString()
                if (sibling in declarations) "'$newName' is already declared in $scope" else null
            }
            is Symbol.Field -> {
                val record = declarations[symbol.owner]?.decl as? RecordDecl
                if (record?.fields?.any { it.name == newName } == true)
                    "'$newName' is already a field of ${symbol.owner}"
                else null
            }
            is Symbol.EnumValue -> {
                val enum = declarations[symbol.owner]?.decl as? EnumDecl
                if (enum?.values?.any { it.name == newName } == true)
                    "'$newName' is already a value of ${symbol.owner}"
                else null
            }
            is Symbol.ImportAlias -> {
                val file = analysis.files.firstOrNull { it.path == symbol.file }
                if (file?.imports?.any { it.alias == newName } == true)
                    "'$newName' is already an import alias in this file"
                else null
            }
            is Symbol.Namespace -> null
        }
    }
}
