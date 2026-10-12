package io.schemata.config

import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.PathMatcher

/** A value read from the file with the line that set it. */
data class Setting<T>(val value: T, val line: Int)

/** `[compile]`: defaults for the options of `compile` and `check`. */
data class CompileSettings(
    val targets: Setting<List<String>>? = null,
    val out: Setting<String>? = null,
    val strict: Setting<Boolean>? = null,
    val protoLayout: Setting<String>? = null,
)

/** `[import]`: defaults for the options of `import`. */
data class ImportSettings(
    val from: Setting<String>? = null,
    val namespace: Setting<String>? = null,
    val include: Setting<List<String>>? = null,
)

/** `[migrate]`: defaults for the options of `migrate`. */
data class MigrateSettings(val allowDestructive: Setting<Boolean>? = null)

/** `[report]`: defaults for how diagnostics are printed. */
data class ReportSettings(val format: Setting<String>? = null, val color: Setting<String>? = null)

/** [paths] and [exclude] as written; [ConfigLoader] has already resolved and checked [paths]. */
data class SourcesSettings(
    val paths: Setting<List<String>>,
    val exclude: Setting<List<String>>? = null,
)

/**
 * One target's defaults: the table's keys, and per-schema tables by schema name. Each value is a
 * `String`, a `Boolean` or a `Long` (TOML's integer), as the key's annotation kind asks.
 */
data class TargetDefaults(
    val keys: Map<String, Setting<Any>>,
    val schemas: Map<String, Map<String, Setting<Any>>>,
)

/** A loaded and validated `schemata.toml`. */
data class ProjectConfig(
    /** The absolute path of the file. */
    val path: Path,
    /** The file's text, for excerpts. */
    val text: String,
    val compile: CompileSettings,
    val import: ImportSettings,
    val migrate: MigrateSettings,
    val report: ReportSettings,
    val sources: SourcesSettings?,
    /** By target name; only the target tables the file holds. */
    val defaults: Map<String, TargetDefaults>,
) {
    /** Paths in the file are relative to the directory that holds it. */
    val directory: Path
        get() = path.parent

    /** [sources] paths resolved against [directory], in order. */
    val sourcePaths: List<Path>
        get() = sources?.paths?.value?.map { directory.resolve(it).normalize() } ?: emptyList()

    private val excludeMatchers: List<PathMatcher> by lazy {
        sources?.exclude?.value?.mapNotNull { globMatcher(it) } ?: emptyList()
    }

    /**
     * A matcher for every [SourcesSettings.exclude] glob, relative to [directory]. [ConfigLoader]
     * rejects an invalid glob; one that reaches here anyway matches nothing rather than throwing.
     */
    fun excluded(file: Path): Boolean {
        if (excludeMatchers.isEmpty()) return false
        val relative = directory.relativize(file.toAbsolutePath().normalize())
        return excludeMatchers.any { it.matches(relative) }
    }
}

/** A `glob:` matcher on the default file system, or null when [glob] is not valid glob syntax. */
internal fun globMatcher(glob: String): PathMatcher? =
    try {
        FileSystems.getDefault().getPathMatcher("glob:$glob")
    } catch (e: IllegalArgumentException) {
        // PatternSyntaxException is an IllegalArgumentException.
        null
    }
