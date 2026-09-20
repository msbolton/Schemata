package io.schemata.cli

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.streams.asSequence

/** One source file as handed to the compiler. [path] is what diagnostics print. */
data class SourceInput(val path: String, val content: String)

/**
 * Expands the paths a user named into the compilation set (spec §12.1): directories contribute
 * every `*.schemata` beneath them; the result is deduplicated by absolute path and sorted by the
 * normalized path string so the compilation is deterministic.
 */
object SourceSet {
    fun load(paths: List<Path>): List<SourceInput> =
        paths
            .flatMap { expand(it) }
            .distinctBy { it.toAbsolutePath().normalize() }
            .map { it.normalize() }
            .sortedBy { it.toString() }
            .map { SourceInput(it.toString(), it.readText()) }

    private fun expand(path: Path): List<Path> =
        if (path.isDirectory()) {
            Files.walk(path).use { stream ->
                stream
                    .asSequence()
                    .filter { Files.isRegularFile(it) && it.toString().endsWith(".schemata") }
                    .toList()
            }
        } else {
            listOf(path)
        }
}
