package io.schemata.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.types.path
import io.schemata.cli.report.Json
import io.schemata.cli.report.Report
import io.schemata.config.AnnotationDefaults
import io.schemata.config.ProjectConfig
import io.schemata.config.Setting
import io.schemata.config.Synthesized
import io.schemata.core.ir.AnnotationValue
import io.schemata.lang.SchemataText
import io.schemata.target.proto.ProtoLayout

/**
 * `schemata config`: the configuration the other commands would run with (SCH-102). Each setting
 * prints with where it came from, a line of the file or the built-in default; given schemas (as
 * paths, or through `[sources]`), it also lists the annotations the file adds to each.
 */
class ConfigCommand : CliktCommand(name = "config") {
    override fun help(context: Context) =
        "Print each setting schemata.toml resolves to, where it came from, and the annotations it adds."

    private val reporting by FormatOptions()

    private val configuration by ConfigOptions()

    private val inputs by argument("PATHS").path(mustExist = true).multiple()

    override fun run() {
        val config = configuration.resolve(this, reporting)
        val style = reporting.resolve(config)
        // Counted without the usage errors a compile would raise, so an empty [sources] still
        // prints the settings that explain it.
        val configured =
            config
                ?.sourcePaths
                ?.takeIf { it.isNotEmpty() }
                ?.let { withoutExcluded(SourceSet.load(it), config) }
        val schemas =
            if (inputs.isNotEmpty()) loadSources(inputs, config)
            else configured?.takeIf { it.isNotEmpty() }
        val analyzed = schemas?.let { Pipeline.analyze(it) }
        val shown = Shown(config, configured)
        if (analyzed != null && analyzed.schema == null) {
            // Under json stdout holds one document, the report; a human report goes to stderr
            // after the settings.
            if (style.format == Format.HUMAN) echo(shown.human(null), trailingNewline = false)
            val report = Report.of(analyzed.diagnostics, emptyList(), emptyList(), strict = false)
            emit(this, report, reportSources(schemas, config), style, out = "")
            return
        }
        val synthesized =
            analyzed?.schema?.let { schema ->
                config?.let { AnnotationDefaults.plan(schema, it, Pipeline.annotations) }.orEmpty()
            }
        val text =
            when (style.format) {
                Format.HUMAN -> shown.human(synthesized)
                Format.JSON -> shown.json(synthesized.orEmpty())
            }
        echo(text, trailingNewline = false)
    }
}

/** One key of a table, with the line that set it, or null for a built-in default. */
private class Entry(val key: String, val value: Any, val line: Int?)

private class Table(
    val name: String,
    val entries: List<Entry>,
    val schemas: Map<String, List<Entry>> = emptyMap(),
)

/** What `config` prints for [config], whose `[sources]` resolves to [configured]. */
private class Shown(
    private val config: ProjectConfig?,
    private val configured: List<SourceInput>?,
) {
    private val file: String? = config?.let { displayPath(it.path) }

    /**
     * The run settings with their built-in defaults (`import.from` and `import.namespace` have
     * none, so they show only when the file sets them), `[sources]` when the file has it, and each
     * target table the file holds.
     */
    private val tables: List<Table> = buildList {
        val compile = config?.compile
        add(
            Table(
                "compile",
                listOfNotNull(
                    entry("targets", compile?.targets, Pipeline.targets.map { it.name }),
                    entry("out", compile?.out, "out"),
                    entry("strict", compile?.strict, false),
                    entry("proto_layout", compile?.protoLayout, ProtoLayout.SCHEMA.flag),
                ),
            )
        )
        val import = config?.import
        add(
            Table(
                "import",
                listOfNotNull(
                    entry("from", import?.from, null),
                    entry("namespace", import?.namespace, null),
                    entry("include", import?.include, emptyList<String>()),
                ),
            )
        )
        add(
            Table(
                "migrate",
                listOfNotNull(entry("allow_destructive", config?.migrate?.allowDestructive, false)),
            )
        )
        val report = config?.report
        add(
            Table(
                "report",
                listOfNotNull(
                    entry("format", report?.format, "human"),
                    entry("color", report?.color, "auto"),
                ),
            )
        )
        config?.sources?.let { sources ->
            add(
                Table(
                    "sources",
                    listOfNotNull(
                        entry("paths", sources.paths, null),
                        entry("exclude", sources.exclude, null),
                    ),
                )
            )
        }
        config?.defaults?.forEach { (target, defaults) ->
            add(
                Table(
                    target,
                    defaults.keys.map { (key, s) -> Entry(key, s.value, s.line) },
                    defaults.schemas.mapValues { (_, keys) ->
                        keys.map { (key, s) -> Entry(key, s.value, s.line) }
                    },
                )
            )
        }
    }

    private fun entry(key: String, setting: Setting<out Any>?, default: Any?): Entry? =
        setting?.let { Entry(key, it.value, it.line) } ?: default?.let { Entry(key, it, null) }

    /** [synthesized] is null when no schemas were analysed, and then has no block. */
    fun human(synthesized: List<Synthesized>?): String = buildString {
        appendLine(file?.let { "file: $it" } ?: "no configuration file")
        for (table in tables) {
            table.entries.forEach { appendLine(line("${table.name}.${it.key}", it)) }
            if (table.name == "sources")
                appendLine("sources: ${plural(configured.orEmpty().size, "file")}")
            table.schemas.forEach { (schema, entries) ->
                entries.forEach {
                    appendLine(line("${table.name}.schemas.${Json.string(schema)}.${it.key}", it))
                }
            }
        }
        when {
            synthesized == null -> Unit
            synthesized.isEmpty() -> appendLine("synthesized: none")
            else -> {
                appendLine("synthesized:")
                synthesized.forEach {
                    appendLine(
                        "  ${it.namespace}: @${it.target}(${it.key}: ${literal(it.value)})  " +
                            "($file:${it.line})"
                    )
                }
            }
        }
    }

    private fun line(name: String, entry: Entry): String {
        val origin = entry.line?.let { "$file:$it" } ?: "default"
        return "$name = ${toml(entry.value)}  ($origin)"
    }

    fun json(synthesized: List<Synthesized>): String =
        Json.document(
            Json.Obj(
                "file" to file,
                "settings" to Json.Obj(*tables.map { it.name to json(it) }.toTypedArray()),
                "sources" to configured.orEmpty().map { it.path },
                "synthesized" to
                    synthesized.map {
                        Json.Obj(
                            "schema" to it.namespace,
                            "target" to it.target,
                            "key" to it.key,
                            "value" to jsonValue(it.value),
                            "line" to it.line,
                        )
                    },
            )
        )

    private fun json(table: Table): Json.Obj {
        val schemas =
            if (table.schemas.isEmpty()) emptyList()
            else
                listOf(
                    "schemas" to
                        Json.Obj(
                            *table.schemas
                                .map { (schema, entries) -> schema to json(entries) }
                                .toTypedArray()
                        )
                )
        return Json.Obj(*(fields(table.entries) + schemas).toTypedArray())
    }

    private fun json(entries: List<Entry>): Json.Obj = Json.Obj(*fields(entries).toTypedArray())

    private fun fields(entries: List<Entry>): List<Pair<String, Any?>> =
        entries.map { it.key to Json.Obj("value" to it.value, "line" to it.line) }

    /** A setting as TOML writes it. */
    private fun toml(value: Any): String =
        when (value) {
            is String -> Json.string(value)
            is List<*> -> value.joinToString(", ", "[", "]") { Json.string(it.toString()) }
            else -> value.toString()
        }

    /** An annotation value as a schema line writes it. */
    private fun literal(value: AnnotationValue): String =
        when (value) {
            is AnnotationValue.Str -> SchemataText.string(value.value)
            is AnnotationValue.Name -> value.value
            is AnnotationValue.Bool -> value.value.toString()
            is AnnotationValue.Num -> value.value.toString()
            else -> error("a configuration never synthesizes $value")
        }

    private fun jsonValue(value: AnnotationValue): Any =
        when (value) {
            is AnnotationValue.Str -> value.value
            is AnnotationValue.Name -> value.value
            is AnnotationValue.Bool -> value.value
            is AnnotationValue.Num -> value.value
            else -> error("a configuration never synthesizes $value")
        }

    private fun plural(n: Int, noun: String) = if (n == 1) "1 $noun" else "$n ${noun}s"
}
