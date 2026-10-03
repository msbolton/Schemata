package io.schemata.cli

import io.schemata.importer.ImportInput
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.streams.asSequence

/**
 * One source file as handed to the compiler. [path] is what diagnostics print; [relative] is the
 * path under the directory argument it was found in, `/`-separated, or null for a file named on its
 * own.
 */
data class SourceInput(val path: String, val content: String, val relative: String? = null)

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

/**
 * Like [SourceSet], but for the `.proto` files `schemata import --from proto` reads; a file found
 * under a directory argument carries its path relative to it, as protoc addresses it.
 */
object ProtoSet {
    fun load(paths: List<Path>): List<SourceInput> = loadByExtension(paths, "proto")
}

private fun loadByExtension(paths: List<Path>, extension: String): List<SourceInput> =
    paths
        .flatMap { expand(it, extension) }
        .distinctBy { it.first.toAbsolutePath().normalize() }
        .map { (path, relative) -> path.normalize() to relative }
        .sortedBy { it.first.toString() }
        .map { (path, relative) -> SourceInput(path.toString(), path.readText(), relative) }

/** Each file [path] names, with its `/`-separated path under [path] when [path] is a directory. */
private fun expand(path: Path, extension: String): List<Pair<Path, String?>> =
    if (path.isDirectory()) {
        Files.walk(path).use { stream ->
            stream
                .asSequence()
                .filter { Files.isRegularFile(it) && it.toString().endsWith(".$extension") }
                .map { it to path.relativize(it).joinToString("/") }
                .toList()
        }
    } else {
        listOf(path to null)
    }

/**
 * Reads a file an importer could not find among the inputs (an `xs:import`/`xs:include` target, a
 * proto `import`): [path] is already resolved against the importing file's directory or a root, so
 * it is read as it stands, or `null` when no such file exists.
 */
fun locate(path: String): ImportInput? {
    val file = Path(path)
    return if (file.isRegularFile()) ImportInput(path, file.readText()) else null
}
