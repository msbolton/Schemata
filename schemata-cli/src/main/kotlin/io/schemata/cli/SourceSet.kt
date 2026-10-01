package io.schemata.cli

import io.schemata.importer.xsd.ImportInput
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.streams.asSequence

/** One source file as handed to the compiler. [path] is what diagnostics print. */
data class SourceInput(val path: String, val content: String)

/**
 * Expands the paths a user named into the compilation set: directories contribute every
 * `*.schemata` beneath them; the result is deduplicated by absolute path and sorted by the
 * normalized path string so the compilation is deterministic.
 */
object SourceSet {
    fun load(paths: List<Path>): List<SourceInput> = loadByExtension(paths, "schemata")
}

/** Like [SourceSet], but for the `.xsd` files `schemata import --from xsd` reads. */
object XsdSet {
    fun load(paths: List<Path>): List<SourceInput> = loadByExtension(paths, "xsd")
}

private fun loadByExtension(paths: List<Path>, extension: String): List<SourceInput> =
    paths
        .flatMap { expand(it, extension) }
        .distinctBy { it.toAbsolutePath().normalize() }
        .map { it.normalize() }
        .sortedBy { it.toString() }
        .map { SourceInput(it.toString(), it.readText()) }

private fun expand(path: Path, extension: String): List<Path> =
    if (path.isDirectory()) {
        Files.walk(path).use { stream ->
            stream
                .asSequence()
                .filter { Files.isRegularFile(it) && it.toString().endsWith(".$extension") }
                .toList()
        }
    } else {
        listOf(path)
    }

/**
 * Resolves an `xs:import`/`xs:include` target `XsdImporter` could not find among [files]: read
 * directly when [relative] already names a file on disk (the facade resolves it against the
 * importing document's own directory before calling this), else that same name tried against each
 * input's directory in turn.
 */
fun locate(files: List<SourceInput>, relative: String): ImportInput? {
    val direct = Path(relative)
    if (direct.isRegularFile()) return ImportInput(relative, direct.readText())
    files.forEach { input ->
        val dir = Path(input.path).parent ?: return@forEach
        val candidate = dir.resolve(relative)
        if (candidate.isRegularFile())
            return ImportInput(candidate.toString(), candidate.readText())
    }
    return null
}
