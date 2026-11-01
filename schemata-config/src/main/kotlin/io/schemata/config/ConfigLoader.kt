package io.schemata.config

import io.schemata.core.annotations.AnnotationRegistry
import io.schemata.lang.Diagnostic
import io.schemata.lang.DiagnosticCode
import io.schemata.lang.Span
import java.io.IOException
import java.io.OutputStream
import java.io.PrintStream
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.nio.file.Path
import org.tomlj.Toml

/** What [ConfigLoader.load] found: a configuration, or the diagnostics that stopped it. */
data class LoadedConfig(val config: ProjectConfig?, val diagnostics: List<Diagnostic>)

/** Finds, reads and validates `schemata.toml` (SCH-102). */
object ConfigLoader {
    const val FILE_NAME = "schemata.toml"

    /** U+FEFF, which some editors write at the start of a UTF-8 file. */
    private const val BYTE_ORDER_MARK = "\uFEFF"

    /**
     * The nearest `schemata.toml` in [start] or an ancestor, or null. With [ceiling], the search
     * stops after that directory, so a caller rooted in a known directory reads nothing above it.
     */
    fun discover(start: Path, ceiling: Path? = null): Path? {
        val stop = ceiling?.let(::real)
        var directory: Path? = start.toAbsolutePath().normalize()
        while (directory != null) {
            val candidate = directory.resolve(FILE_NAME)
            if (Files.isRegularFile(candidate)) return candidate
            if (stop != null && real(directory) == stop) return null
            directory = directory.parent
        }
        return null
    }

    /**
     * [path] with its links resolved when it exists, so `/var` and `/private/var` compare equal.
     */
    private fun real(path: Path): Path =
        try {
            path.toRealPath()
        } catch (e: IOException) {
            path.toAbsolutePath().normalize()
        }

    /**
     * Reads and validates [path]. [registry] gives each target's schema-line keys and kinds;
     * [targetNames] the tables a target may have; [displayPath] is how diagnostics name the file
     * (relative to the working directory when under it). Any diagnostic means config is null.
     */
    fun load(
        path: Path,
        registry: AnnotationRegistry,
        targetNames: List<String>,
        displayPath: String = path.toString(),
    ): LoadedConfig {
        val absolute = path.toAbsolutePath().normalize()
        val text =
            try {
                // a byte order mark is not TOML, and the file means nothing by it
                Files.readString(absolute).removePrefix(BYTE_ORDER_MARK)
            } catch (e: IOException) {
                return LoadedConfig(null, listOf(unreadable(displayPath, reason(absolute, e))))
            }
        TomljClasses.load()
        val parsed = Toml.parse(text)
        val lines = text.lines()
        if (parsed.hasErrors()) {
            val syntax =
                parsed.errors().map { error ->
                    val line = error.position()?.line() ?: 1
                    at(
                        ConfigCodes.UNREADABLE,
                        displayPath,
                        lines,
                        line,
                        (error.message ?: "invalid TOML").replaceFirstChar { it.lowercase() },
                        "fix the TOML syntax",
                    )
                }
            return LoadedConfig(null, syntax)
        }
        val validator =
            Validator(parsed, lines, displayPath, registry, targetNames, absolute.parent)
        val config = validator.read(absolute, text)
        val diagnostics = validator.diagnostics.sortedBy { it.span.startLine }
        return if (diagnostics.isEmpty()) LoadedConfig(config, emptyList())
        else LoadedConfig(null, diagnostics)
    }

    private fun reason(path: Path, e: IOException): String =
        when {
            !Files.exists(path) -> "no such file"
            Files.isDirectory(path) -> "it is a directory"
            e is CharacterCodingException -> "it is not UTF-8 text"
            else -> (e.message ?: e.javaClass.simpleName).replaceFirstChar { it.lowercase() }
        }

    private fun unreadable(file: String, reason: String): Diagnostic =
        Diagnostic(
            ConfigCodes.UNREADABLE,
            "$file: cannot read the configuration file: $reason",
            Span(file, 1, 1, 1, 1),
            "create the file, name another with --config, or pass --no-config",
        )

    /** A diagnostic at column 1 of [line], underlining that whole line. */
    internal fun at(
        code: DiagnosticCode,
        file: String,
        lines: List<String>,
        line: Int,
        message: String,
        help: String,
    ): Diagnostic {
        val width = lines.getOrNull(line - 1)?.length ?: 0
        return Diagnostic(
            code,
            "$file:$line: $message",
            Span(file, line, 1, line, maxOf(1, width)),
            help,
        )
    }
}

/**
 * tomlj was generated with ANTLR 4.11.1 and the ANTLR runtime here is 4.13.2. The parser works, but
 * the first time tomlj's lexer and parser classes load, ANTLR checks the versions and prints four
 * warning lines to stderr, and the CLI's and the language server's stderr must stay clean. So both
 * classes are loaded once, before the first parse, with stderr pointed at a discarding stream.
 */
private object TomljClasses {
    private val generated = listOf("org.tomlj.internal.TomlLexer", "org.tomlj.internal.TomlParser")

    // A synchronized lazy: one thread swaps stderr while the others wait for it to finish.
    private val loaded: Unit by lazy {
        val original = System.err
        System.setErr(PrintStream(OutputStream.nullOutputStream()))
        try {
            for (name in generated) {
                try {
                    Class.forName(name, true, Toml::class.java.classLoader)
                } catch (e: ClassNotFoundException) {
                    // A tomlj that names its classes differently still parses; it is only noisier.
                }
            }
        } finally {
            System.setErr(original)
        }
    }

    fun load() = loaded
}
