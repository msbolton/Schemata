package io.schemata.lsp.workspace

import io.schemata.core.AnalysisOptions
import io.schemata.core.Analyzer
import io.schemata.core.IndexedDecl
import io.schemata.core.ReferenceRecorder
import io.schemata.core.annotations.AnnotationRegistry
import io.schemata.lang.Category
import io.schemata.lang.Diagnostic
import io.schemata.lang.DiagnosticCode
import io.schemata.lang.ParseResult
import io.schemata.lang.Parser
import io.schemata.lang.Severity
import io.schemata.lang.Span
import io.schemata.lang.ast.ImportDecl
import io.schemata.lang.ast.SourceFile
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile

/** A file the parser threw on; the editor shows it like a syntax error. */
private val PARSE_FAILED =
    DiagnosticCode("LSP0001", Severity.ERROR, Category.SYNTAX, "the parser failed on a file")

/** A file the set lists but the disk would not let the server read. */
private val UNREADABLE =
    DiagnosticCode("LSP0002", Severity.ERROR, Category.SYNTAX, "a file could not be read")

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
 * [files] are the snapshots that were analysed, and [recorded] what the resolver resolved in them,
 * and [index] maps every name in them to what it refers to.
 */
class SetAnalysis(
    val key: SetKey,
    val members: List<Document>,
    val diagnostics: Map<String, List<Diagnostic>>,
    val gone: Set<String>,
    val files: List<SourceFile>,
    val recorded: Recorded,
) {
    /** Every definition and reference site of the set, built on first use. */
    val index: ReferenceIndex by lazy { IndexBuilder.build(files, recorded) }

    /** The snapshot the set analysed for [path], if that file has ever parsed. */
    fun snapshot(path: String): Snapshot? = members.firstOrNull { it.path == path }?.snapshot
}

/**
 * The files the server knows and the analysis of each set, with no protocol types. Not thread-safe:
 * the server calls it from one thread. A change marks the file's set stale; [analysis] recomputes a
 * stale set and otherwise returns what it computed last. Each analysis re-reads a closed file whose
 * size or modification time changed since it was read.
 */
class Workspace(
    private val annotations: AnnotationRegistry,
    /** Parses one file's text; replaceable so a test can make parsing fail. */
    private val parse: (String, String) -> ParseResult = Parser::parse,
    /** Lists the schema files under a directory; replaceable so a test can count the walks. */
    private val walk: (Path, Boolean) -> Set<String> = ::walkSchemata,
) {
    private var sets = SchemaSets(emptyList())
    private var strict = false
    private val documents = sortedMapOf<String, Document>()
    private val analyses = mutableMapOf<SetKey, SetAnalysis>()
    private val stale = mutableSetOf<String>()

    /**
     * The files each set held when its directory was last walked. Walking is the costly part of an
     * analysis, and an edit of a file already in the list cannot change what the directory holds,
     * so the list is dropped only when a file may have appeared or gone: [open] of an unlisted
     * file, [close], [diskChanged], [refresh], and [configure].
     */
    private val listings = mutableMapOf<SetKey, Set<String>>()

    fun configure(roots: List<Path>, strict: Boolean) {
        sets = SchemaSets(roots)
        this.strict = strict
        analyses.clear()
        listings.clear()
    }

    fun keyOf(path: String): SetKey = sets.keyOf(path)

    fun document(path: String): Document? = documents[path]

    /** The sets that hold at least one open document. */
    fun openSets(): Set<SetKey> = documents.values.filter { it.open }.map { keyOf(it.path) }.toSet()

    fun open(path: String, text: String): SetKey {
        val document = documents.getOrPut(path) { Document(path) }
        document.open = true
        forgetListingUnless(path)
        return update(document, text)
    }

    /**
     * The editor's text for [path] replaced. The editor sends this only after opening the file, so
     * a file the workspace has not been told is open is opened here rather than ignored.
     */
    fun change(path: String, text: String): SetKey {
        val document = documents[path]?.takeIf { it.open } ?: return open(path, text)
        return update(document, text)
    }

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
        listings.remove(key)
        return key
    }

    fun analysis(key: SetKey): SetAnalysis = analyses.getOrPut(key) { analyze(key) }

    /** Drops [key]'s analysis, so the next [analysis] checks the disk again. */
    fun refresh(key: SetKey) {
        analyses.remove(key)
        listings.remove(key)
    }

    /**
     * Analyses [files] with this workspace's options, apart from every set and its cache: the
     * diagnostics, and what the resolver resolved. For trying out an edit before making it.
     */
    internal fun analyzeApart(files: List<SourceFile>): Pair<List<Diagnostic>, Recorded> {
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
        return result.diagnostics to recorded
    }

    /** Drops [path]'s set's file list unless the list already holds [path]. */
    private fun forgetListingUnless(path: String) {
        val key = keyOf(path)
        if (listings[key]?.contains(path) != true) listings.remove(key)
    }

    /**
     * A parser that throws is a bug in the parser, but one file must not take the rest of its set
     * down with it or vanish from the set without a word: the file is reported as broken, with the
     * exception's message, and its last clean parse stays in force.
     */
    private fun parseOrReport(text: String, path: String): ParseResult =
        try {
            parse(text, path)
        } catch (e: Exception) {
            val why = e.message ?: e::class.simpleName
            ParseResult(
                null,
                listOf(
                    Diagnostic(PARSE_FAILED, "the parser failed on this file: $why", startOf(path))
                ),
            )
        }

    private fun update(document: Document, text: String): SetKey {
        document.text = text
        document.unreadable = false
        val parsed = parseOrReport(text, document.path)
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
            if (document?.open == true) continue
            // A watcher can miss a change, or not be registered at all, so the disk has the last
            // word.
            val stamp = stamp(path)
            if (
                document == null || path in stale || stamp != document.stamp || document.unreadable
            ) {
                val text = read(path)
                val read = documents.getOrPut(path) { Document(path) }
                read.stamp = stamp
                if (text != null) {
                    update(read, text)
                } else {
                    // Silence would drop the file from the set and leave its dependants to report
                    // names that vanished for no visible reason.
                    read.unreadable = true
                    read.parseDiagnostics =
                        listOf(
                            Diagnostic(UNREADABLE, "cannot read this file from disk", startOf(path))
                        )
                }
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
        val (found, recorded) = analyzeApart(files)
        val analysed = found.groupBy { it.span.file }
        val diagnostics =
            members.associate { document ->
                document.path to
                    if (document.broken) document.parseDiagnostics
                    else document.parseDiagnostics + (analysed[document.path] ?: emptyList())
            }
        return SetAnalysis(key, members, diagnostics, gone, files, recorded)
    }

    private fun listFiles(key: SetKey): Set<String> =
        listings.getOrPut(key) {
            walk(Paths.get(key.directory), key.recursive).filterTo(mutableSetOf()) {
                sets.contains(key, it)
            }
        }

    private fun startOf(path: String) = Span(path, 1, 1, 1, 1)

    private fun stamp(path: String): DiskStamp? =
        try {
            val attributes = Files.readAttributes(Paths.get(path), BasicFileAttributes::class.java)
            DiskStamp(attributes.size(), attributes.lastModifiedTime().toMillis())
        } catch (e: IOException) {
            null
        }

    private fun read(path: String): String? =
        try {
            Files.readString(Paths.get(path))
        } catch (e: IOException) {
            null
        }
}

/**
 * The `.schemata` files under [directory], or directly in it when not [recursive], as absolute
 * normalized paths. A directory that cannot be read is skipped, so one unreadable folder does not
 * stop the rest of the set; so is every hidden directory (`.git`, `.cache`).
 */
internal fun walkSchemata(directory: Path, recursive: Boolean): Set<String> {
    if (!Files.isDirectory(directory)) return emptySet()
    val depth = if (recursive) Int.MAX_VALUE else 1
    val found = mutableSetOf<String>()
    val visitor =
        object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                if (dir != directory && dir.fileName.toString().startsWith(".")) {
                    FileVisitResult.SKIP_SUBTREE
                } else {
                    FileVisitResult.CONTINUE
                }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (file.extension == "schemata" && file.isRegularFile()) {
                    found += file.toAbsolutePath().normalize().toString()
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult =
                FileVisitResult.CONTINUE
        }
    try {
        Files.walkFileTree(directory, emptySet(), depth, visitor)
    } catch (e: IOException) {
        // The root itself went away while it was being walked; what was found still counts.
    }
    return found
}
