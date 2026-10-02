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
}
