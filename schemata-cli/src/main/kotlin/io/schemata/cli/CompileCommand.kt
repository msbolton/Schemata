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
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.split
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.path
import com.github.ajalt.mordant.rendering.AnsiLevel
import io.schemata.cli.report.HumanRenderer
import io.schemata.cli.report.JsonRenderer
import io.schemata.cli.report.Report
import io.schemata.cli.report.Sources
import io.schemata.config.ProjectConfig
import io.schemata.target.ProtoLayout
import io.schemata.target.Target
import io.schemata.target.proto.ProtoTarget
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

    private val protoLayout by protoLayoutOption()

    private val outFlag by
        option("--out", help = "Output directory (default: out)").path(canBeFile = false)

    private val reporting by ReportingOptions()

    private val configuration by ConfigOptions()

    private val inputs by argument("PATHS").path(mustExist = true).multiple()

    override fun run() {
        val config = configuration.resolve(this, reporting)
        val style = reporting.resolve(config)
        val strict = compileStrict(reporting.strict, config)
        val targets = compileTargets(targetNames, protoLayout, config)
        val out = outDirectory(outFlag, config)
        val sources = loadSources(inputPaths(inputs, config), config)
        val result = Pipeline.compile(sources, targets, strict, config)
        val report = Report.of(result, strict = strict, checkOnly = false)
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
        emit(this, report, reportSources(sources, config), style, out.toString())
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

/**
 * The `--proto-layout` option shared by compile, check, and diff; absent, the configuration's
 * layout applies, else the schema layout.
 */
internal fun CliktCommand.protoLayoutOption() =
    option(
            "--proto-layout",
            help =
                "Protobuf file layout: schema (one file per schema or reference cycle, the default) " +
                    "or type (one file per type under the package directory)",
        )
        .choice(*ProtoLayout.flags.toTypedArray())

/**
 * [targets] with the proto entry configured for [layout]; null keeps the default. A layout given
 * without the proto target would do nothing, so it is a usage error.
 */
internal fun CliktCommand.withProtoLayout(
    targets: List<Target<*>>,
    layout: String?,
): List<Target<*>> {
    val chosen = layout?.let { ProtoLayout.of(it) } ?: return targets
    if (targets.none { it.name == ProtoTarget.name })
        throw usageError("--proto-layout needs the proto target; add proto to --target")
    return targets.map { if (it.name == ProtoTarget.name) ProtoTarget(chosen) else it }
}

/**
 * The files [load] finds under [inputs] (the `.schemata` files by default), less those [config]'s
 * `[sources].exclude` matches. Finding none is a usage error naming [noun], what was looked for,
 * and so is an exclude that removes every file found, which names the configuration file instead.
 */
internal fun CliktCommand.loadSources(
    inputs: List<Path>,
    config: ProjectConfig? = null,
    extension: String = "schemata",
    load: (List<Path>) -> List<SourceInput> = SourceSet::load,
    noun: String = ".$extension files",
): List<SourceInput> {
    val found = load(inputs)
    if (found.isEmpty()) throw usageError("no $noun found under: ${inputs.joinToString(", ")}")
    if (config == null) return found
    val kept = withoutExcluded(found, config)
    if (kept.isEmpty())
        throw usageError(
            "every file under ${inputs.joinToString(", ")} is excluded by [sources].exclude " +
                "in ${displayPath(config.path)}"
        )
    return kept
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
    stderrIsTerminal: () -> Boolean? = ::probeStderrTerminal,
) {
    val terminal = command.currentContext.terminal
    val width = if (terminal.terminalInfo.outputInteractive) terminal.size.width else 100
    when (reporting.format) {
        Format.JSON -> terminal.rawPrint(JsonRenderer.report(report, out))
        Format.HUMAN -> {
            // The human report goes to stderr, so colour needs stderr itself to be a terminal: the
            // terminal Clikt detected reflects stdout, which can be a terminal while stderr is a
            // file. The probe runs only here, and an unknown answer defers to the detection.
            val ansiSupported =
                terminal.terminalInfo.ansiLevel != AnsiLevel.NONE && (stderrIsTerminal() ?: true)
            terminal.rawPrint(
                HumanRenderer.render(report, sources, reporting.palette(ansiSupported), out, width),
                stderr = true,
            )
        }
    }
    if (report.exitCode != 0) throw ProgramResult(report.exitCode)
}

/**
 * Whether this process's stderr is a terminal, or null when that cannot be told. Mordant 3 only
 * detects stdout and the JVM's `System.console()` covers stdin and stdout, so ask a POSIX shell: a
 * child that inherits stderr can run `test -t 2`. On Windows, where a `sh` may be absent or
 * disagree with the real console, when no shell can be started, or when the wait is interrupted,
 * the answer is null and the caller trusts Mordant's own detection, as it did before this probe.
 */
internal fun probeStderrTerminal(): Boolean? {
    if (System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true))
        return null
    return try {
        val exit =
            ProcessBuilder("sh", "-c", "test -t 2")
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
                .waitFor()
        when (exit) {
            0 -> true
            1 -> false
            else -> null
        }
    } catch (e: IOException) {
        null
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        null
    }
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
