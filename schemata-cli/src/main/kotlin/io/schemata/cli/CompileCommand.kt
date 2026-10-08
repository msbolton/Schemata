package io.schemata.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.split
import com.github.ajalt.clikt.parameters.types.path
import com.github.ajalt.mordant.rendering.AnsiLevel
import io.schemata.cli.report.HumanRenderer
import io.schemata.cli.report.JsonRenderer
import io.schemata.cli.report.Report
import io.schemata.cli.report.Sources
import io.schemata.target.Target
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.createParentDirectories
import kotlin.io.path.writeText

class CompileCommand : CliktCommand(name = "compile") {
    override fun help(context: Context) =
        "Compile .schemata files (or directories of them) to one or more targets."

    private val targetNames by
        option(
                "--target",
                help =
                    "Comma-separated targets (default: all): ${Pipeline.targets.joinToString(", ") { it.name }}",
            )
            .split(",")

    private val out by
        option("--out", help = "Output directory (default: out)")
            .path(canBeFile = false)
            .default(Path("out"))

    private val reporting by ReportingOptions()

    private val inputs by argument("PATHS").path(mustExist = true).multiple(required = true)

    override fun run() {
        val targets = selectTargets(targetNames)
        val sources = loadSources(inputs)
        val result = Pipeline.compile(sources, targets, strict = reporting.strict)
        val report = Report.of(result, strict = reporting.strict, checkOnly = false)
        val written = report.written.map { it.target }.toSet()
        result.targets
            .filter { it.name in written }
            .forEach { target ->
                target.files.forEach { file ->
                    val destination = out.resolve(target.name).resolve(file.path)
                    try {
                        destination.createParentDirectories()
                        destination.writeText(file.content)
                    } catch (e: IOException) {
                        throw CliktError("cannot write $destination: ${e.message}")
                    }
                }
            }
        emit(this, report, Sources.of(sources), reporting, out.toString())
    }
}

/**
 * Resolves `--target`; every registered target when absent. A usage error carries the running
 * command's context so Clikt prints that command's usage line, not the root's.
 */
internal fun CliktCommand.selectTargets(names: List<String>?): List<Target<*>> =
    names?.distinct()?.map { name ->
        Pipeline.targetNamed(name)
            ?: throw usageError(
                "unknown target '$name'; available: ${Pipeline.targets.joinToString(", ") { it.name }}"
            )
    } ?: Pipeline.targets

internal fun CliktCommand.loadSources(inputs: List<Path>): List<SourceInput> {
    val sources = SourceSet.load(inputs)
    if (sources.isEmpty())
        throw usageError("no .schemata files found under: ${inputs.joinToString(", ")}")
    return sources
}

/**
 * A usage error for the running command. Clikt prints the usage line of the command whose context
 * the error carries, and an error thrown from `run()` carries none, so it would fall back to the
 * root command's line.
 */
internal fun CliktCommand.usageError(message: String): UsageError =
    UsageError(message).also { it.context = currentContext }

/**
 * Prints the report in the chosen format and exits with its code when non-zero. Uses the terminal's
 * raw print so a palette's own escape codes reach the stream unchanged instead of being
 * reinterpreted (and stripped or rewritten) by the terminal's own ANSI handling.
 */
internal fun emit(
    command: CliktCommand,
    report: Report,
    sources: Sources,
    reporting: ReportStyle,
    out: String,
    stderrIsTerminal: () -> Boolean = ::probeStderrTerminal,
) {
    val terminal = command.currentContext.terminal
    // The human report goes to stderr, so colour needs stderr itself to be a terminal: the
    // terminal Clikt detected reflects stdout, which can be a terminal while stderr is a file.
    val ansiSupported = terminal.terminalInfo.ansiLevel != AnsiLevel.NONE && stderrIsTerminal()
    val width = if (terminal.terminalInfo.outputInteractive) terminal.size.width else 100
    when (reporting.format) {
        Format.JSON -> terminal.rawPrint(JsonRenderer.report(report, out))
        Format.HUMAN ->
            terminal.rawPrint(
                HumanRenderer.render(report, sources, reporting.palette(ansiSupported), out, width),
                stderr = true,
            )
    }
    if (report.exitCode != 0) throw ProgramResult(report.exitCode)
}

/**
 * Whether this process's stderr is a terminal. The JVM only offers `System.console()`, which covers
 * stdin and stdout, so ask the shell: a child that inherits stderr can run `test -t 2`. Where there
 * is no shell, stderr is taken not to be a terminal.
 */
internal fun probeStderrTerminal(): Boolean =
    try {
        ProcessBuilder("sh", "-c", "test -t 2")
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start()
            .waitFor() == 0
    } catch (e: IOException) {
        false
    }

/** Writes [content] to [destination], creating its parent directories. */
internal fun writeOutput(destination: Path, content: String) {
    try {
        destination.createParentDirectories()
        destination.writeText(content)
    } catch (e: IOException) {
        throw CliktError("cannot write $destination: ${e.message}")
    }
}
