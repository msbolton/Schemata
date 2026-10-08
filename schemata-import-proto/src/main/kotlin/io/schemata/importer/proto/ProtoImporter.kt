package io.schemata.importer.proto

import io.schemata.importer.ImportCodes
import io.schemata.importer.ImportInput
import io.schemata.importer.ImportNames
import io.schemata.importer.ImportResult
import io.schemata.importer.Importer
import io.schemata.importer.Roots
import io.schemata.importer.UnitAnnotation
import io.schemata.importer.importResult
import io.schemata.lang.Diagnostic
import io.schemata.lang.DiagnosticCode
import io.schemata.lang.SchemataText
import io.schemata.lang.Span

/**
 * Runs the reader, the lowering, the emitter, and the formatter over a set of `.proto` inputs. An
 * `import` resolves among the inputs by its path under their roots, then beside the importing file,
 * then under each root through [Importer.import]'s `locate`; a file found that way is read and
 * lowered too.
 */
object ProtoImporter : Importer {
    /**
     * Where protoc's own files live: the lowering knows their types by name, mapping some and
     * reading the rest as strings, so importing one needs no file.
     */
    private const val WELL_KNOWN = "google/protobuf/"

    override fun import(
        inputs: List<ImportInput>,
        namespace: String?,
        locate: (String) -> ImportInput?,
    ): ImportResult {
        val diagnostics = mutableListOf<Diagnostic>()
        // Keyed by the path with `/` separators, so a path the platform spells with `\` still
        // matches one joined from an import.
        val files = LinkedHashMap<String, ProtoFile>()
        val sources = LinkedHashMap<ProtoFile, ImportInput>()
        val unreadable = mutableSetOf<String>()
        fun read(input: ImportInput): ProtoFile? {
            val key = slashed(input.path)
            files[key]?.let {
                return it
            }
            if (key in unreadable) return null
            return try {
                ProtoReader.read(input.path, input.content).also {
                    files[key] = it
                    sources[it] = input
                }
            } catch (e: ProtoSyntaxError) {
                unreadable += key
                diagnostics +=
                    Diagnostic(
                        ImportCodes.UNRESOLVED,
                        "${input.path}:${e.pos.line}:${e.pos.col}: cannot parse: ${e.message}",
                        Span(input.path, e.pos.line, e.pos.col, e.pos.line, e.pos.col),
                        "give the importer a file protoc accepts",
                    )
                null
            }
        }
        inputs.forEach { read(it) }
        val byRelative =
            inputs.filter { it.relative != null }.associateBy { slashed(it.relative!!) }
        val roots = inputs.mapNotNull { root(it) }.distinct()

        val imports = LinkedHashMap<ProtoFile, MutableList<ProtoFile>>()
        val queue = ArrayDeque(files.values)
        fun report(
            file: ProtoFile,
            code: DiagnosticCode,
            message: String,
            pos: Pos,
            help: String = ImportCodes.helpFor(code),
        ) {
            diagnostics +=
                Diagnostic(
                    code,
                    message,
                    Span(file.path, pos.line, pos.col, pos.line, pos.col),
                    help,
                )
        }
        fun found(input: ImportInput): ProtoFile? {
            val known = slashed(input.path) in files
            return read(input)?.also { if (!known) queue += it }
        }
        while (queue.isNotEmpty()) {
            val f = queue.removeFirst()
            f.imports.forEach { imp ->
                if (imp.public) {
                    report(
                        f,
                        ImportCodes.APPROXIMATED,
                        "${f.path}: import public '${imp.path}' re-exports nothing in Schemata",
                        imp.pos,
                    )
                }
                if (imp.path.startsWith(WELL_KNOWN)) return@forEach
                val listed = byRelative[imp.path]
                if (listed != null && slashed(listed.path) in unreadable) return@forEach
                val beside = resolvePath(f.path, imp.path)
                val target =
                    listed?.let { files[slashed(it.path)] }
                        ?: (files[beside] ?: locate(beside)?.let(::found))
                        ?: roots.firstNotNullOfOrNull { root ->
                            val path = if (root.isEmpty()) imp.path else "$root/${imp.path}"
                            files[path] ?: locate(path)?.let { found(it.copy(relative = imp.path)) }
                        }
                if (target == null) {
                    report(
                        f,
                        ImportCodes.UNRESOLVED,
                        "${f.path}: import '${imp.path}' cannot be resolved",
                        imp.pos,
                    )
                } else {
                    imports.getOrPut(f) { mutableListOf() } += target
                }
            }
        }

        val all = files.values.toList()
        val namespaces = LinkedHashMap<ProtoFile, String>()
        val annotations = LinkedHashMap<ProtoFile, List<UnitAnnotation>>()
        val shared = sharedPackages(all, sources)
        all.forEachIndexed { index, f ->
            val input = sources.getValue(f)
            val (name, derived) =
                when {
                    index == 0 && namespace != null -> namespace to false
                    f in shared -> packageNamespace(f.pkg!!)
                    else ->
                        Roots.namespaceFor(input, f.pkg, slashed(f.path).substringAfterLast('/'))
                }
            if (derived) {
                val from = if (f in shared) "the package '${f.pkg}'" else "the file name"
                report(
                    f,
                    ImportCodes.RENAMED,
                    "${f.path}: schema name '$name' was derived from $from",
                    Pos(1, 1),
                )
            }
            if (f.pkg != null && f.pkg != name) {
                annotations[f] =
                    listOf(UnitAnnotation("proto", "package", SchemataText.string(f.pkg)))
            }
            namespaces[f] = name
        }
        all.groupBy { namespaces.getValue(it) }
            .forEach { (name, group) ->
                val first = group.first()
                group
                    .drop(1)
                    .filter { it.pkg != first.pkg }
                    .forEach {
                        report(
                            it,
                            ImportCodes.UNRESOLVED,
                            "${it.path}: ${packageText(it)} and ${first.path}'s " +
                                "${packageText(first)} both lower to schema '$name'",
                            Pos(1, 1),
                            ImportCodes.RENAME_HELP,
                        )
                    }
            }

        val lowered = ProtoLowering.lower(all, namespaces, ProtoSymbols(all), annotations, imports)
        return importResult(lowered.units, diagnostics + lowered.diagnostics)
    }

    /**
     * Files found under one root that declare one package with another file there: protoc reads
     * them as one package, so they lower to one namespace, the package's.
     */
    private fun sharedPackages(
        files: List<ProtoFile>,
        sources: Map<ProtoFile, ImportInput>,
    ): Set<ProtoFile> =
        files
            .filter { it.pkg != null && sources.getValue(it).relative != null }
            .groupBy { root(sources.getValue(it)) to it.pkg }
            .values
            .filter { it.size > 1 }
            .flatten()
            .toSet()

    private fun packageText(f: ProtoFile): String = f.pkg?.let { "package '$it'" } ?: "no package"

    /** A package as a namespace name: as it stands, or with each bad segment lower-snaked. */
    private fun packageNamespace(pkg: String): Pair<String, Boolean> {
        val segments = pkg.split('.')
        val fixed =
            segments.map {
                if (ImportNames.isNamespaceSegment(it)) it else ImportNames.lowerSnake(it)
            }
        return fixed.joinToString(".") to (fixed != segments)
    }

    /** [path] with `/` for every separator, which every platform's paths accept. */
    internal fun slashed(path: String): String = path.replace('\\', '/')

    /**
     * The directory an input was found under, `/`-separated: its path without its relative part,
     * whichever separator either is spelled with.
     */
    internal fun root(input: ImportInput): String? =
        input.relative?.let { slashed(input.path).removeSuffix(slashed(it)).trimEnd('/') }

    /**
     * [relative] joined to [basePath]'s directory, `/`-separated, with `.` segments dropped and
     * each `..` taking back the segment before it.
     */
    internal fun resolvePath(basePath: String, relative: String): String {
        val dir = slashed(basePath).substringBeforeLast('/', "")
        val joined = if (dir.isEmpty()) slashed(relative) else "$dir/${slashed(relative)}"
        val out = ArrayDeque<String>()
        joined.split('/').forEach { seg ->
            when (seg) {
                "",
                "." -> Unit
                ".." ->
                    if (out.isNotEmpty() && out.last() != "..") out.removeLast()
                    else out.addLast(seg)
                else -> out.addLast(seg)
            }
        }
        return (if (joined.startsWith("/")) "/" else "") + out.joinToString("/")
    }
}
