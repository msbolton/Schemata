package io.schemata.cli

import com.github.ajalt.clikt.core.CliktCommand
import io.schemata.cli.report.Sources
import io.schemata.config.ConfigLoader
import io.schemata.config.ProjectConfig
import io.schemata.target.Target
import io.schemata.target.proto.ProtoTarget
import java.nio.file.Path
import kotlin.io.path.Path

/*
 * How each command's options fall back to `schemata.toml` (SCH-102): a value on the command line
 * wins, then the file's, then the built-in default.
 */

/** The paths a command reads: the ones it was given, else the configuration's `[sources]`. */
internal fun CliktCommand.inputPaths(inputs: List<Path>, config: ProjectConfig?): List<Path> {
    if (inputs.isNotEmpty()) return inputs
    val configured = config?.sourcePaths.orEmpty()
    if (configured.isNotEmpty()) return configured
    val where = config?.let { displayPath(it.path) } ?: "any ${ConfigLoader.FILE_NAME}"
    throw usageError("no paths given and no [sources] in $where")
}

/**
 * `--target`, else `[compile] targets`, else every target; then the layout. A layout from the file
 * is a default for the proto target only, so a run that leaves proto out ignores it, while
 * `--proto-layout` on such a run is still a usage error.
 */
internal fun CliktCommand.compileTargets(
    names: List<String>?,
    layout: String?,
    config: ProjectConfig?,
): List<Target<*>> {
    val targets = selectTargets(names ?: config?.compile?.targets?.value)
    val configured =
        config?.compile?.protoLayout?.value?.takeIf {
            targets.any { t -> t.name == ProtoTarget.name }
        }
    return withProtoLayout(targets, layout ?: configured)
}

/**
 * `--strict`, or `[compile] strict = true`, for every command that has the flag: the file can turn
 * strict on but never off.
 */
internal fun compileStrict(flag: Boolean, config: ProjectConfig?): Boolean =
    flag || config?.compile?.strict?.value == true

/**
 * `--out` as given (relative to the working directory), else `[compile] out` (relative to the
 * file's directory, like every path in the file), else `out`.
 */
internal fun outDirectory(flag: Path?, config: ProjectConfig?): Path =
    flag
        ?: config?.compile?.out?.value?.let { Path(displayPath(config.directory.resolve(it))) }
        ?: Path("out")

/** [found] less the files [config]'s `[sources].exclude` matches. */
internal fun withoutExcluded(found: List<SourceInput>, config: ProjectConfig?): List<SourceInput> =
    if (config == null) found
    else found.filterNot { config.excluded(Path(it.path).toAbsolutePath().normalize()) }

/** The report's sources: [inputs], and the configuration file so a diagnostic in it is quoted. */
internal fun reportSources(inputs: List<SourceInput>, config: ProjectConfig?): Sources {
    val sources = Sources.of(inputs)
    return config?.let { sources.plus(displayPath(it.path), it.text) } ?: sources
}
