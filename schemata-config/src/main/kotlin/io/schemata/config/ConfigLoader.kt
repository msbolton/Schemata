package io.schemata.config

import io.schemata.core.annotations.AnnotationRegistry
import io.schemata.core.annotations.Element
import io.schemata.core.annotations.ValueKind
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
import org.tomlj.TomlArray
import org.tomlj.TomlParseResult
import org.tomlj.TomlTable

/** What [ConfigLoader.load] found: a configuration, or the diagnostics that stopped it. */
data class LoadedConfig(val config: ProjectConfig?, val diagnostics: List<Diagnostic>)

/** Finds, reads and validates `schemata.toml` (SCH-102). */
object ConfigLoader {
    const val FILE_NAME = "schemata.toml"

    /** The nearest `schemata.toml` in [start] or an ancestor, or null. */
    fun discover(start: Path): Path? {
        var directory: Path? = start.toAbsolutePath().normalize()
        while (directory != null) {
            val candidate = directory.resolve(FILE_NAME)
            if (Files.isRegularFile(candidate)) return candidate
            directory = directory.parent
        }
        return null
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
                Files.readString(absolute)
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

/** The value a key takes: the wording of SCH2903 and the example its help shows. */
private sealed class Expect(val wording: String, val example: String) {
    object Text : Expect("a string", "\"…\"")

    object Flag : Expect("true or false", "true")

    object Integer : Expect("an integer", "1")

    object Texts : Expect("an array of strings", "[\"…\"]")

    /** A string out of [choices]; with [many], an array of such strings. */
    class OneOf(val choices: List<String>, val many: Boolean = false) :
        Expect(
            "one of " + choices.joinToString(", ") { "\"$it\"" },
            choices.firstOrNull().orEmpty().let { if (many) "[\"$it\"]" else "\"$it\"" },
        )
}

/** Walks one parsed file against the known tables, collecting diagnostics as it goes. */
private class Validator(
    private val toml: TomlParseResult,
    private val lines: List<String>,
    private val file: String,
    private val registry: AnnotationRegistry,
    private val targetNames: List<String>,
    private val directory: Path,
) {
    val diagnostics = mutableListOf<Diagnostic>()

    private val runTables: Map<String, Map<String, Expect>> =
        linkedMapOf(
            "compile" to
                linkedMapOf(
                    "targets" to Expect.OneOf(targetNames, many = true),
                    "out" to Expect.Text,
                    "strict" to Expect.Flag,
                    "proto_layout" to Expect.OneOf(listOf("schema", "type")),
                ),
            "import" to
                linkedMapOf(
                    "from" to Expect.OneOf(listOf("xsd", "proto", "sql")),
                    "namespace" to Expect.Text,
                    "include" to Expect.Texts,
                ),
            "migrate" to linkedMapOf("allow_destructive" to Expect.Flag),
            "report" to
                linkedMapOf(
                    "format" to Expect.OneOf(listOf("human", "json")),
                    "color" to Expect.OneOf(listOf("auto", "always", "never")),
                ),
            "sources" to linkedMapOf("paths" to Expect.Texts, "exclude" to Expect.Texts),
        )

    private val tablesHelp =
        "tables are " +
            (runTables.keys + targetNames).joinToString(", ") +
            ", and <target>.$SCHEMAS.\"<schema>\""

    fun read(path: Path, text: String): ProjectConfig {
        val run = mutableMapOf<String, Map<String, Setting<Any>>>()
        val defaults = mutableMapOf<String, TargetDefaults>()
        for (name in toml.keySet()) {
            val table = listOf(name)
            when {
                !toml.isTable(table) -> unknownTable(table, name)
                name in runTables -> run[name] = readTable(table, name, runTables.getValue(name))
                name in targetNames -> defaults[name] = readTarget(name)
                else -> unknownTable(table, name)
            }
        }
        val sources = run["sources"]?.let { readSources(it, lineOf(listOf("sources"))) }
        val compile = run["compile"]
        val import = run["import"]
        val report = run["report"]
        return ProjectConfig(
            path = path,
            text = text,
            compile =
                CompileSettings(
                    compile.setting("targets"),
                    compile.setting("out"),
                    compile.setting("strict"),
                    compile.setting("proto_layout"),
                ),
            import =
                ImportSettings(
                    import.setting("from"),
                    import.setting("namespace"),
                    import.setting("include"),
                ),
            migrate = MigrateSettings(run["migrate"].setting("allow_destructive")),
            report = ReportSettings(report.setting("format"), report.setting("color")),
            sources = sources,
            defaults = targetNames.filter { it in defaults }.associateWith { defaults.getValue(it) },
        )
    }

    /**
     * A `[sources]` table without `paths` names no sources of its own; its line stands in for the
     * key's. Every path must exist, relative to the file's directory.
     */
    private fun readSources(table: Map<String, Setting<Any>>, tableLine: Int): SourcesSettings {
        val paths = table.setting<List<String>>("paths") ?: Setting(emptyList(), tableLine)
        for (entry in paths.value) {
            if (!Files.exists(directory.resolve(entry))) {
                report(
                    ConfigCodes.MISSING_SOURCE,
                    paths.line,
                    "sources path '$entry' names nothing",
                    "create it or remove it from [sources].paths",
                )
            }
        }
        val exclude = table.setting<List<String>>("exclude")
        exclude
            ?.value
            ?.filter { globMatcher(it) == null }
            ?.forEach { glob ->
                report(
                    ConfigCodes.BAD_VALUE,
                    exclude.line,
                    "sources.exclude holds an invalid glob '$glob'",
                    "globs use the syntax of java.nio.file.PathMatcher, for example \"schemas/drafts/**\"",
                )
            }
        return SourcesSettings(paths, exclude)
    }

    /** A target table: its schema-line keys, and `schemas` holding one table per schema. */
    private fun readTarget(target: String): TargetDefaults {
        val keys = schemaLineKeys(target)
        val table = listOf(target)
        val values = readTable(table, target, keys, placeholdersOf = target, skip = SCHEMAS)
        val schemas = linkedMapOf<String, Map<String, Setting<Any>>>()
        val schemasPath = table + SCHEMAS
        if (SCHEMAS in toml.getTable(table)!!.keySet()) {
            if (!toml.isTable(schemasPath)) {
                unknownKey(schemasPath, "$target.$SCHEMAS", tablesHelp)
            } else {
                for (schema in toml.getTable(schemasPath)!!.keySet()) {
                    val schemaPath = schemasPath + schema
                    val display = "$target.$SCHEMAS.\"$schema\""
                    if (!toml.isTable(schemaPath)) unknownKey(schemaPath, display, tablesHelp)
                    else
                        schemas[schema] =
                            readTable(schemaPath, display, keys, placeholdersOf = target)
                }
            }
        }
        return TargetDefaults(values, schemas)
    }

    /**
     * The keys [target] declares for a schema line, by the TOML value each takes. A key whose
     * annotation is a bare flag or a name tuple has no single literal to default to, so the file
     * cannot set it.
     */
    private fun schemaLineKeys(target: String): Map<String, Expect> =
        registry
            .specs(target)
            .filter { Element.NAMESPACE in it.elements }
            .mapNotNull { spec ->
                val expect =
                    when (spec.valueKind) {
                        ValueKind.STRING,
                        ValueKind.NAME ->
                            spec.choices?.let { Expect.OneOf(it.toList()) } ?: Expect.Text
                        ValueKind.BOOL -> Expect.Flag
                        ValueKind.INT -> Expect.Integer
                        ValueKind.FLAG,
                        ValueKind.NAME_TUPLE -> null
                    }
                expect?.let { spec.key to it }
            }
            .toMap()

    /**
     * Reads every key of the table at [path] against [keys]. With [placeholdersOf], free string
     * values are target defaults and their placeholders are checked; [skip] names a key the caller
     * reads itself.
     */
    private fun readTable(
        path: List<String>,
        display: String,
        keys: Map<String, Expect>,
        placeholdersOf: String? = null,
        skip: String? = null,
    ): Map<String, Setting<Any>> {
        val table = toml.getTable(path)!!
        val values = linkedMapOf<String, Setting<Any>>()
        for (key in table.keySet()) {
            if (key == skip) continue
            val keyPath = path + key
            val value = table.get(listOf(key))
            val expect = keys[key]
            if (expect == null) {
                if (value is TomlTable) unknownTable(keyPath, "$display.$key")
                else unknownKey(keyPath, "$display.$key", keyHelp(display, key, keys.keys))
                continue
            }
            val line = lineOf(keyPath)
            val failed = mismatch(value, expect)
            if (failed != null) {
                report(
                    ConfigCodes.BAD_VALUE,
                    line,
                    "$display.$key expects ${failed.wording}",
                    "for example $display.$key = ${failed.example}",
                )
                continue
            }
            val plain: Any =
                if (value is TomlArray) value.toList().map { it as String } else value!!
            if (placeholdersOf != null && plain is String && expect == Expect.Text) {
                checkPlaceholders(
                    plain,
                    allowPackage = placeholdersOf == "proto" && key != "package",
                    "$display.$key",
                    line,
                )
            }
            values[key] = Setting(plain, line)
        }
        return values
    }

    /** The expectation [value] fails, or null when it meets [expect]. */
    private fun mismatch(value: Any?, expect: Expect): Expect? {
        val ok =
            when (expect) {
                Expect.Text -> value is String
                Expect.Flag -> value is Boolean
                Expect.Integer -> value is Long
                Expect.Texts -> isStrings(value)
                is Expect.OneOf ->
                    if (expect.many) {
                        if (!isStrings(value)) return Expect.Texts
                        (value as TomlArray).toList().all { it in expect.choices }
                    } else value is String && value in expect.choices
            }
        return if (ok) null else expect
    }

    // TOML arrays may mix types, so every element is checked.
    private fun isStrings(value: Any?): Boolean =
        value is TomlArray && value.toList().all { it is String }

    /** `{package}` and `{Package}` are known names that only some keys accept. */
    private fun checkPlaceholders(value: String, allowPackage: Boolean, key: String, line: Int) {
        val name = Placeholders.check(value, allowPackage) ?: return
        val message =
            if (name == "package" || name == "Package") {
                "$key holds '{$name}', which only [proto] keys other than package accept"
            } else {
                // An unclosed placeholder comes back with its opening brace and no closing one.
                val shown = if (name.startsWith("{")) name else "{$name}"
                "$key holds the unknown placeholder '$shown'"
            }
        report(
            ConfigCodes.BAD_PLACEHOLDER,
            line,
            message,
            "placeholders are {schema}, {Schema} and {schema_}; [proto] keys other than package " +
                "also take {package} and {Package}",
        )
    }

    private fun keyHelp(table: String, key: String, known: Set<String>): String {
        val nearest =
            known
                .sorted()
                .map { it to levenshtein(key, it) }
                .filter { it.second <= 3 }
                .minByOrNull { it.second }
        return if (nearest != null) "did you mean '${nearest.first}'"
        else "keys of [$table] are ${known.sorted().joinToString(", ")}"
    }

    private fun unknownTable(path: List<String>, display: String) =
        report(ConfigCodes.UNKNOWN_KEY, lineOf(path), "unknown table '$display'", tablesHelp)

    private fun unknownKey(path: List<String>, display: String, help: String) =
        report(ConfigCodes.UNKNOWN_KEY, lineOf(path), "unknown key '$display'", help)

    private fun report(code: DiagnosticCode, line: Int, message: String, help: String) {
        diagnostics += ConfigLoader.at(code, file, lines, line, message, help)
    }

    private fun lineOf(path: List<String>): Int = toml.inputPositionOf(path)?.line() ?: 1

    private fun levenshtein(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, substitution)
            }
            previous = current
        }
        return previous[b.length]
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> Map<String, Setting<Any>>?.setting(key: String): Setting<T>? =
        this?.get(key) as Setting<T>?

    companion object {
        /** The subtable of a target table that holds per-schema tables. */
        const val SCHEMAS = "schemas"
    }
}
