package io.schemata.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import io.schemata.cli.report.Report
import io.schemata.cli.report.Sources
import io.schemata.config.ConfigLoader
import io.schemata.config.ProjectConfig
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** `--config` and `--no-config`, shared by every command that reads schemas (SCH-102). */
class ConfigOptions : OptionGroup() {
    val path: Path? by
        option(
                "--config",
                help =
                    "Configuration file (default: the nearest schemata.toml above the working directory)",
            )
            .path(mustExist = false)

    val disabled: Boolean by option("--no-config", help = "Ignore any schemata.toml").flag()

    /**
     * The configuration for this run, or null when none applies: `--no-config`, or no file named
     * and none found from the working directory up to the file-system root. A file with errors
     * prints them as a report in [reporting]'s style (the file's text in its sources, so each
     * diagnostic quotes its line) and exits 1 through [emit].
     */
    fun resolve(command: CliktCommand, reporting: ReportStyle): ProjectConfig? {
        if (disabled && path != null)
            throw command.usageError("--config and --no-config are mutually exclusive")
        if (disabled) return null
        val file = path ?: ConfigLoader.discover(workingDirectory()) ?: return null
        val shown = displayPath(file)
        val loaded =
            ConfigLoader.load(file, Pipeline.annotations, Pipeline.targets.map { it.name }, shown)
        loaded.config?.let {
            return it
        }
        val text =
            try {
                Files.readString(file)
            } catch (e: IOException) {
                null
            }
        val sources = text?.let { Sources.EMPTY.plus(shown, it) } ?: Sources.EMPTY
        val report = Report.of(loaded.diagnostics, emptyList(), emptyList(), strict = false)
        emit(command, report, sources, reporting, out = "")
        // Every loader diagnostic is an error, so emit has already exited; this keeps it so.
        throw ProgramResult(1)
    }
}

internal fun workingDirectory(): Path = Path.of("").toAbsolutePath()

/**
 * How output names [file]: relative to the working directory when it lies under it (`.` for the
 * directory itself), else absolute.
 */
internal fun displayPath(file: Path): String {
    val absolute = file.toAbsolutePath().normalize()
    val cwd = workingDirectory().normalize()
    if (!absolute.startsWith(cwd)) return absolute.toString()
    return cwd.relativize(absolute).toString().ifEmpty { "." }
}
