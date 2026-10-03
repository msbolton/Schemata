package io.schemata.importer.proto

import io.schemata.importer.ImportCodes
import io.schemata.importer.ImportInput
import io.schemata.importer.ImportNames
import io.schemata.importer.ImportResult
import io.schemata.importer.Importer
import io.schemata.importer.Roots
import io.schemata.importer.UnitAnnotation
import io.schemata.importer.emitUnits
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
    /** Files whose types the lowering knows by name; importing them needs no file. */
    private val WELL_KNOWN =
        setOf("timestamp", "duration", "wrappers", "any", "struct", "field_mask", "empty")
            .map { "google/protobuf/$it.proto" }
            .toSet()

    override fun import(
        inputs: List<ImportInput>,
        namespace: String?,
        locate: (String) -> ImportInput?,
    ): ImportResult {
        val diagnostics = mutableListOf<Diagnostic>()
        val files = LinkedHashMap<String, ProtoFile>()
        val sources = LinkedHashMap<ProtoFile, ImportInput>()
        val unreadable = mutableSetOf<String>()
        fun read(input: ImportInput): ProtoFile? {
            files[input.path]?.let {
                return it
            }
            if (input.path in unreadable) return null
            return try {
                ProtoReader.read(input.path, input.content).also {
                    files[input.path] = it
                    sources[it] = input
                }
            } catch (e: ProtoSyntaxError) {
                unreadable += input.path
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
        val byRelative = inputs.filter { it.relative != null }.associateBy { it.relative!! }
        val roots = inputs.mapNotNull { root(it) }.distinct()

        val imports = LinkedHashMap<ProtoFile, MutableList<ProtoFile>>()
        val queue = ArrayDeque(files.values)
        fun report(file: ProtoFile, code: DiagnosticCode, message: String, pos: Pos) {
            diagnostics +=
                Diagnostic(
                    code,
                    message,
                    Span(file.path, pos.line, pos.col, pos.line, pos.col),
                    ImportCodes.helpFor(code),
                )
        }
        fun found(input: ImportInput): ProtoFile? {
            val known = input.path in files
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
                if (imp.path in WELL_KNOWN) return@forEach
                val listed = byRelative[imp.path]
                if (listed != null && listed.path in unreadable) return@forEach
                val beside = resolvePath(f.path, imp.path)
                val target =
                    listed?.let { files[it.path] }
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
                    else -> Roots.namespaceFor(input, f.pkg, f.path.substringAfterLast('/'))
                }
            if (derived) {
                val from = if (f in shared) "the package '${f.pkg}'" else "the file name"
                report(
                    f,
                    ImportCodes.RENAMED,
                    "${f.path}: namespace '$name' was derived from $from",
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
                                "${packageText(first)} both lower to namespace '$name'",
                            Pos(1, 1),
                        )
                    }
            }

        val lowered = ProtoLowering.lower(all, namespaces, ProtoSymbols(all), annotations, imports)
        return ImportResult(emitUnits(lowered.units), diagnostics + lowered.diagnostics)
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

    /** The directory an input was found under: its path without its relative part. */
    private fun root(input: ImportInput): String? =
        input.relative?.let { input.path.removeSuffix(it).trimEnd('/', '\\') }

    /**
     * [relative] joined to [basePath]'s directory, with `.` segments dropped and each `..` taking
     * back the segment before it.
     */
    private fun resolvePath(basePath: String, relative: String): String {
        val dir = basePath.substringBeforeLast('/', "")
        val joined = if (dir.isEmpty()) relative else "$dir/$relative"
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
