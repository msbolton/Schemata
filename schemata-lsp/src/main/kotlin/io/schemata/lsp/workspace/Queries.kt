package io.schemata.lsp.workspace

import io.schemata.lang.Span

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
        // Analysis first: it can re-read a closed file and replace the snapshot read below.
        val analysis = workspace.analysis(workspace.keyOf(path))
        val document = workspace.document(path) ?: return null
        if (document.broken) return null
        val snapshot = document.snapshot ?: return null
        return analysis to snapshot
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
     * target `name` overrides are plain strings, not references, and are left as they are. The set
     * is re-read from disk first, so the edits match the files they will be applied to.
     */
    fun rename(path: String, position: TextPosition, newName: String): RenameResult {
        workspace.refresh(workspace.keyOf(path))
        val hit = hit(path, position) ?: return RenameResult.Refused("nothing to rename here")
        return Rename(workspace, hit.analysis, hit.site.symbol, newName).run()
    }
}
