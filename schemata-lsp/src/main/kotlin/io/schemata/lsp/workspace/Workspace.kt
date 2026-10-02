package io.schemata.lsp.workspace

import io.schemata.core.AnalysisOptions
import io.schemata.core.Analyzer
import io.schemata.core.IndexedDecl
import io.schemata.core.ReferenceRecorder
import io.schemata.core.annotations.AnnotationRegistry
import io.schemata.lang.Diagnostic
import io.schemata.lang.Parser
import io.schemata.lang.Span
import io.schemata.lang.ast.ImportDecl
import io.schemata.lang.ast.SourceFile
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile

/** Everything the resolver reported while one set was analysed. */
class Recorded : ReferenceRecorder {
    val types = mutableListOf<Pair<Span, IndexedDecl>>()
    val aliases = mutableListOf<Pair<Span, ImportDecl>>()
    val namespaces = mutableListOf<Pair<Span, String>>()

    override fun type(site: Span, target: IndexedDecl) {
        types += site to target
    }

    override fun alias(site: Span, import: ImportDecl) {
        aliases += site to import
    }

    override fun namespace(site: Span, namespace: String) {
        namespaces += site to namespace
    }
}

/**
 * One analysis of one set. [diagnostics] has an entry for every member, empty when the file is
 * clean; [gone] lists files that were members last time and no longer exist, each reported once.
 * [files] are the snapshots that were analysed, and [recorded] what the resolver resolved in them.
 */
class SetAnalysis(
    val key: SetKey,
    val members: List<Document>,
    val diagnostics: Map<String, List<Diagnostic>>,
    val gone: Set<String>,
    val files: List<SourceFile>,
    val recorded: Recorded,
)

/**
 * The files the server knows and the analysis of each set, with no protocol types. Not thread-safe:
 * the server calls it from one thread. A change marks the file's set stale; [analysis] recomputes a
 * stale set and otherwise returns what it computed last.
 */
class Workspace(private val annotations: AnnotationRegistry) {
    private var sets = SchemaSets(emptyList())
    private var strict = false
    private val documents = sortedMapOf<String, Document>()
    private val analyses = mutableMapOf<SetKey, SetAnalysis>()
    private val stale = mutableSetOf<String>()

    fun configure(roots: List<Path>, strict: Boolean) {
        sets = SchemaSets(roots)
        this.strict = strict
        analyses.clear()
    }

    fun keyOf(path: String): SetKey = sets.keyOf(path)

    fun document(path: String): Document? = documents[path]

    /** The sets that hold at least one open document. */
    fun openSets(): Set<SetKey> = documents.values.filter { it.open }.map { keyOf(it.path) }.toSet()

    fun open(path: String, text: String): SetKey {
        val document = documents.getOrPut(path) { Document(path) }
        document.open = true
        return update(document, text)
    }

    fun change(path: String, text: String): SetKey = open(path, text)

    fun close(path: String): SetKey {
        documents[path]?.open = false
        return diskChanged(path)
    }

    /**
     * A file was created, changed, or deleted on disk; an open document keeps the editor's text.
     */
    fun diskChanged(path: String): SetKey {
        val key = keyOf(path)
        if (documents[path]?.open != true) stale += path
        analyses.remove(key)
        return key
    }

    fun analysis(key: SetKey): SetAnalysis = analyses.getOrPut(key) { analyze(key) }

    private fun update(document: Document, text: String): SetKey {
        document.text = text
        val parsed = Parser.parse(text, document.path)
        document.parseDiagnostics = parsed.diagnostics
        parsed.file?.let { document.snapshot = Snapshot(text, it) }
        val key = keyOf(document.path)
        analyses.remove(key)
        return key
    }

    private fun analyze(key: SetKey): SetAnalysis {
        val previous = documents.keys.filter { sets.contains(key, it) }.toSet()
        val onDisk = listFiles(key)
        for (path in onDisk) {
            val document = documents[path]
            if (document == null || (!document.open && path in stale)) {
                val text = read(path) ?: continue
                update(documents.getOrPut(path) { Document(path) }, text)
            }
        }
        stale -= onDisk
        val gone = previous.filter { it !in onDisk && documents[it]?.open != true }.toSet()
        gone.forEach {
            documents.remove(it)
            stale -= it
        }
        val members = documents.values.filter { sets.contains(key, it.path) }
        val files = members.mapNotNull { it.snapshot?.file }
        val recorded = Recorded()
        val result =
            Analyzer.analyze(
                files,
                AnalysisOptions(
                    strictOrdinals = strict,
                    annotations = annotations,
                    references = recorded,
                ),
            )
        val analysed = result.diagnostics.groupBy { it.span.file }
        val diagnostics =
            members.associate { document ->
                document.path to
                    if (document.broken) document.parseDiagnostics
                    else document.parseDiagnostics + (analysed[document.path] ?: emptyList())
            }
        return SetAnalysis(key, members, diagnostics, gone, files, recorded)
    }

    private fun listFiles(key: SetKey): Set<String> {
        val directory = Paths.get(key.directory)
        if (!Files.isDirectory(directory)) return emptySet()
        val depth = if (key.recursive) Int.MAX_VALUE else 1
        return try {
            Files.walk(directory, depth).use { paths ->
                paths
                    .filter { it.isRegularFile() && it.extension == "schemata" }
                    .map { it.toAbsolutePath().normalize().toString() }
                    .filter { sets.contains(key, it) }
                    .toList()
                    .toSet()
            }
        } catch (e: IOException) {
            emptySet()
        }
    }

    private fun read(path: String): String? =
        try {
            Files.readString(Paths.get(path))
        } catch (e: IOException) {
            null
        }
}
