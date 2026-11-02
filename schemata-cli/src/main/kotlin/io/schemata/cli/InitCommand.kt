package io.schemata.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.types.path
import io.schemata.config.ConfigLoader
import io.schemata.config.StarterFile
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/** `schemata init`: a starter `schemata.toml` with every key at its default (SCH-105). */
class InitCommand : CliktCommand(name = "init") {
    override fun help(context: Context) =
        "Write a starter schemata.toml, every key at its default and [sources] found from the files."

    private val directory by
        argument("DIR", help = "Where to write it (default: the working directory)")
            .path(mustExist = true, canBeFile = false)
            .optional()

    override fun run() {
        val dir = (directory ?: workingDirectory()).toAbsolutePath().normalize()
        val file = dir.resolve(ConfigLoader.FILE_NAME)
        if (Files.exists(file)) {
            echo("${ConfigLoader.FILE_NAME} already exists in ${displayPath(dir)}", err = true)
            throw ProgramResult(1)
        }
        val text =
            StarterFile.text(
                Pipeline.annotations,
                Pipeline.targets.map { it.name },
                sourcePathsIn(dir),
            )
        Files.writeString(file, text)
        echo("wrote ${displayPath(file)}")
    }
}

/**
 * The `[sources]` paths a new file names in [dir]: the directory itself when it holds a schema
 * file, since that reaches every file below it, else each top-level directory holding one at any
 * depth, sorted. Hidden directories and `out`, where `compile` writes by default, are skipped.
 */
internal fun sourcePathsIn(dir: Path): List<String> {
    val entries = dir.listDirectoryEntries()
    if (entries.any { it.isRegularFile() && it.name.endsWith(".schemata") }) return listOf(".")
    return entries
        .filter { it.isDirectory() && !it.name.startsWith(".") && it.name != "out" }
        .filter { sub ->
            Files.walk(sub).use { s ->
                s.anyMatch { it.isRegularFile() && it.name.endsWith(".schemata") }
            }
        }
        .map { it.name }
        .sorted()
}
