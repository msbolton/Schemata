package io.schemata.cli.diagnostics

import io.schemata.cli.Pipeline
import io.schemata.cli.PipelineResult
import io.schemata.cli.SourceInput
import io.schemata.cli.analyzeSide
import io.schemata.cli.cannotDiff
import io.schemata.cli.migrate
import io.schemata.cli.report.HumanRenderer
import io.schemata.cli.report.Palette
import io.schemata.cli.report.Report
import io.schemata.cli.report.Sources
import io.schemata.evolution.Evolution
import io.schemata.evolution.Rulebook
import io.schemata.evolution.Rulebooks
import io.schemata.importer.ImportInput
import io.schemata.importer.Importer
import io.schemata.importer.proto.ProtoImporter
import io.schemata.importer.sql.SqlImporter
import io.schemata.importer.xsd.XsdImporter
import io.schemata.lang.Diagnostic
import io.schemata.lang.format.FormatResult
import io.schemata.lang.upgrade.Upgrader
import io.schemata.target.Target
import java.io.File

/**
 * One fixture directory: its sources, the options on the first line of `expected.txt`, and the
 * expected text.
 */
class Fixture(val dir: File) {
    val name: String = dir.name
    val code: String = name.substringBefore('-')
    private val expectedFile = File(dir, "expected.txt")
    private val header: String? =
        expectedFile
            .takeIf { it.isFile }
            ?.useLines { it.firstOrNull() }
            ?.takeIf { it.startsWith("#") }
            ?.also { checkHeader(it, name) }

    /** The header's options, each compared whole: `strict` is not matched inside `strictly`. */
    private val options: List<String> =
        header?.removePrefix("#")?.split(' ')?.filter { it.isNotEmpty() } ?: emptyList()

    private fun option(prefix: String): String? =
        options.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)

    val strict: Boolean = "strict" in options

    /** True for a `# migrate=old,new` fixture: planned through `schemata migrate`. */
    val isMigrate: Boolean = option("migrate=") != null

    val allowDestructive: Boolean = "allow-destructive" in options

    /** True for a `# upgrade` fixture: its 1.x sources are run through `schemata upgrade`. */
    val isUpgrade: Boolean = header == "# upgrade"

    /**
     * True for a `# diff=old,new` fixture: compared through [Evolution], not compiled or checked.
     */
    val isDiff: Boolean = option("diff=") != null

    private val targetNames: List<String>? = option("targets=")?.split(',')

    val targets: List<Target<*>> =
        if (isDiff || isMigrate) emptyList()
        else
            targetNames?.map { Pipeline.targetNamed(it) ?: error("$name: unknown target '$it'") }
                ?: Pipeline.targets

    val rulebooks: List<Rulebook> =
        if (!isDiff || isMigrate) emptyList()
        else
            targetNames?.map { Rulebooks.named(it) ?: error("$name: unknown target '$it'") }
                ?: Rulebooks.all

    val sources: List<SourceInput> =
        dir.listFiles { f -> f.extension == "schemata" }!!
            .sortedBy { it.name }
            .map { SourceInput(it.name, it.readText()) }

    /**
     * The `.xsd`, `.proto`, or `.sql` sources of an import fixture, all of one format; non-empty
     * exactly when [render] runs the importer.
     */
    val foreign: List<SourceInput> =
        dir.listFiles { f -> f.extension in importers }!!
            .sortedBy { it.name }
            .map { SourceInput(it.name, it.readText()) }

    /**
     * True when the importer reads [foreign] as files named on their own (`# import=sql lone`);
     * otherwise a proto or SQL file is read as found under the fixture directory, its name its path
     * under the root.
     */
    private val lone: Boolean = "lone" in options

    /**
     * The two sides of a diff fixture, loaded from its `old/` and `new/` subdirectories. Paths keep
     * that prefix (`old/s.schemata`, `new/s.schemata`) so same-named files on each side don't
     * collide once both sides' sources share one [Sources] lookup.
     */
    val oldSources: List<SourceInput> =
        if (isDiff || isMigrate) schemataFiles(File(dir, "old"), "old") else emptyList()

    val newSources: List<SourceInput> =
        if (isDiff || isMigrate) schemataFiles(File(dir, "new"), "new") else emptyList()

    val expected: String
        get() = expectedFile.readText().let { if (header != null) it.substringAfter('\n') else it }

    /** The report as the CLI prints it, without excerpt and gutter lines. */
    fun render(): String {
        val full =
            when {
                isDiff || isMigrate -> {
                    val report = Report.of(diffDiagnostics(), emptyList(), emptyList(), strict)
                    HumanRenderer.render(
                        report,
                        Sources.of(oldSources + newSources),
                        Palette.NONE,
                        out = "",
                        width = 400,
                    )
                }
                isUpgrade -> {
                    val report = Report.of(upgradeDiagnostics(), emptyList(), emptyList(), strict)
                    HumanRenderer.render(
                        report,
                        Sources.of(sources),
                        Palette.NONE,
                        out = "",
                        width = 400,
                    )
                }
                foreign.isNotEmpty() -> {
                    val report = Report.of(importDiagnostics(), emptyList(), emptyList(), strict)
                    HumanRenderer.render(
                        report,
                        Sources.of(foreign),
                        Palette.NONE,
                        out = "",
                        width = 400,
                    )
                }
                else -> {
                    val report = Report.of(checked, strict, checkOnly = true)
                    HumanRenderer.render(
                        report,
                        Sources.of(sources),
                        Palette.NONE,
                        out = "",
                        width = 400,
                    )
                }
            }
        return full.lines().filter { keep(it) }.joinToString("\n").trimEnd() + "\n"
    }

    fun codes(): Set<String> = diagnostics().map { it.code.id }.toSet()

    fun helps(): List<Pair<String, String?>> = diagnostics().map { it.code.id to it.help }

    fun diagnostics(): List<Diagnostic> =
        when {
            isUpgrade -> upgradeDiagnostics()
            isDiff || isMigrate -> diffDiagnostics()
            foreign.isNotEmpty() -> importDiagnostics()
            else -> checked.diagnostics
        }

    /**
     * What the compile pipeline reports for [sources]. Every query on a fixture (its rendering, its
     * codes, its helps) reads the same run, so each case is compiled once. The same goes for the
     * other kinds of run below.
     */
    private val checked: PipelineResult by lazy { Pipeline.check(sources, targets, strict) }

    /**
     * The diagnostics `schemata diff` reports for the two sides: [cannotDiff]'s when either side
     * fails to load or the two share no namespace, else the comparison's. Both sides are analysed
     * the way `diff` analyses them, so a `strict` header only promotes notes, as `--strict` does.
     */
    private fun diffDiagnostics(): List<Diagnostic> = diffed

    private val diffed: List<Diagnostic> by lazy { computeDiff() }

    private fun computeDiff(): List<Diagnostic> {
        val old = analyzeSide(oldSources)
        val new = analyzeSide(newSources)
        val cannotDiff = cannotDiff(old, new)
        if (cannotDiff.isNotEmpty()) return cannotDiff
        if (isMigrate) return migrate(old.schema!!, new.schema!!, allowDestructive).diagnostics
        return Evolution.compare(old.schema!!, new.schema!!, rulebooks).diagnostics
    }

    /**
     * What `schemata upgrade` reports for each source: its errors when it cannot upgrade, else its
     * warnings.
     */
    private fun upgradeDiagnostics(): List<Diagnostic> = upgraded

    private val upgraded: List<Diagnostic> by lazy {
        sources.flatMap { source ->
            when (val r = Upgrader.upgrade(source.content, source.path)) {
                is FormatResult.Failed -> r.diagnostics
                is FormatResult.Formatted -> r.warnings
            }
        }
    }

    /** What the importer for [foreign]'s format reports, as `schemata import` would. */
    fun importDiagnostics(): List<Diagnostic> = imported

    private val imported: List<Diagnostic> by lazy {
        val extension = foreign.map { it.path.substringAfterLast('.') }.distinct().single()
        val importer = importers.getValue(extension)
        val rooted = importer !is XsdImporter && !lone
        importer
            .import(foreign.map { ImportInput(it.path, it.content, it.path.takeIf { rooted }) })
            .diagnostics
    }

    private fun schemataFiles(d: File, prefix: String): List<SourceInput> =
        d.listFiles { f -> f.extension == "schemata" }!!
            .sortedBy { it.name }
            .map { SourceInput("$prefix/${it.name}", it.readText()) }

    fun write(text: String) {
        expectedFile.writeText((header?.let { "$it\n" } ?: "") + text)
    }

    private fun keep(line: String): Boolean {
        val t = line.trim()
        if (t.isEmpty()) return true
        if (line.startsWith("error[") || line.startsWith("warning[")) return true
        if (t.startsWith("-->") || t.startsWith("= help:")) return true
        // trailer lines carry no gutter and no leading space
        return !line.startsWith(" ") && !t.startsWith("|") && !Regex("^\\d+ \\|").containsMatchIn(t)
    }

    companion object {
        val root = File("src/test/resources/diagnostics")

        /**
         * A header is `#` then space-separated options, each one of a fixed set; any other text, or
         * text after an option, is a mistake in the fixture rather than something to ignore.
         */
        private val HEADER =
            Regex(
                "^#(?: (?:strict|upgrade|lone|allow-destructive|diff=old,new|migrate=old,new" +
                    "|import=(?:xsd|proto|sql)|targets=[a-z,]+))+$"
            )

        /**
         * The report blocks (a header line, its location, its help) that [expected] holds more than
         * once. A fixture shows each shape once; two identical blocks are a pasted duplicate that
         * proves nothing the first does not.
         */
        fun duplicateBlocks(expected: String): List<String> =
            expected
                .trim()
                .split(Regex("\\n\\s*\\n"))
                .filter { it.startsWith("error[") || it.startsWith("warning[") }
                .groupingBy { it }
                .eachCount()
                .filterValues { it > 1 }
                .keys
                .toList()

        fun checkHeader(header: String, fixture: String) {
            require(HEADER.matches(header)) { "$fixture: unrecognised header '$header'" }
        }

        private val importers: Map<String, Importer> =
            mapOf("xsd" to XsdImporter, "proto" to ProtoImporter, "sql" to SqlImporter)

        fun all(): List<Fixture> =
            root.listFiles { f -> f.isDirectory }!!.sortedBy { it.name }.map { Fixture(it) }

        fun pending(): Set<String> =
            File(root, "pending.txt")
                .takeIf { it.isFile }
                ?.readLines()
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.toSet() ?: emptySet()
    }
}
