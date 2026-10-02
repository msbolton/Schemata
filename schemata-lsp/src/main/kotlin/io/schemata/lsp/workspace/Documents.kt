package io.schemata.lsp.workspace

import io.schemata.lang.Diagnostic
import io.schemata.lang.ast.SourceFile
import io.schemata.lang.hasErrors

/** The last text of a file that parsed cleanly, with its tree. */
data class Snapshot(val text: String, val file: SourceFile) {
    val lines: LineIndex by lazy { LineIndex(text) }
}

/** A file's size and modification time when it was last read, to tell when it changed. */
data class DiskStamp(val size: Long, val modified: Long)

/**
 * One `.schemata` file: its current [text] (the editor's while [open], the disk's otherwise), the
 * [snapshot] the rest of its set analyses against, and what the last parse reported. While
 * [broken], the snapshot is older than the text and positions in the file cannot be trusted.
 * [stamp] is the file as it was when its text was last read from disk.
 */
class Document(val path: String) {
    var text: String = ""
    var open: Boolean = false
    var stamp: DiskStamp? = null
    var snapshot: Snapshot? = null
    var parseDiagnostics: List<Diagnostic> = emptyList()

    val broken: Boolean
        get() = parseDiagnostics.hasErrors
}
