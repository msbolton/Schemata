package io.schemata.cli.diagnostics

import io.schemata.cli.Pipeline
import io.schemata.cli.SourceInput
import io.schemata.cli.analyzeSide
import io.schemata.cli.cannotDiff
import io.schemata.cli.report.HumanRenderer
import io.schemata.cli.report.Palette
import io.schemata.cli.report.Report
import io.schemata.cli.report.Sources
import io.schemata.evolution.Evolution
import io.schemata.evolution.Rulebook
import io.schemata.evolution.Rulebooks
import io.schemata.importer.ImportInput
import io.schemata.importer.xsd.XsdImporter
import io.schemata.lang.Diagnostic
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

    val strict: Boolean = header?.contains("strict") == true

    /**
     * True for a `# diff=old,new` fixture: compared through [Evolution], not compiled or checked.
     */
    val isDiff: Boolean = header?.contains("diff=") == true

    private val targetNames: List<String>? =
        header?.let { Regex("targets=([a-z,]+)").find(it) }?.groupValues?.get(1)?.split(',')

    val targets: List<Target<*>> =
        if (isDiff) emptyList()
        else
            targetNames?.map { Pipeline.targetNamed(it) ?: error("$name: unknown target '$it'") }
                ?: Pipeline.targets

    val rulebooks: List<Rulebook> =
        if (!isDiff) emptyList()
        else
            targetNames?.map { Rulebooks.named(it) ?: error("$name: unknown target '$it'") }
                ?: Rulebooks.all

    val sources: List<SourceInput> =
        dir.listFiles { f -> f.extension == "schemata" }!!
            .sortedBy { it.name }
            .map { SourceInput(it.name, it.readText()) }

    /** `.xsd` sources for an import fixture; non-empty exactly when [render] runs the importer. */
    val xsd: List<SourceInput> =
        dir.listFiles { f -> f.extension == "xsd" }!!
            .sortedBy { it.name }
            .map { SourceInput(it.name, it.readText()) }

    /**
     * The two sides of a diff fixture, loaded from its `old/` and `new/` subdirectories. Paths keep
     * that prefix (`old/s.schemata`, `new/s.schemata`) so same-named files on each side don't
     * collide once both sides' sources share one [Sources] lookup.
     */
    val oldSources: List<SourceInput> =
        if (isDiff) schemataFiles(File(dir, "old"), "old") else emptyList()

    val newSources: List<SourceInput> =
        if (isDiff) schemataFiles(File(dir, "new"), "new") else emptyList()

    val expected: String
        get() = expectedFile.readText().let { if (header != null) it.substringAfter('\n') else it }

    /** The report as the CLI prints it, without excerpt and gutter lines. */
    fun render(): String {
        val full =
            when {
                isDiff -> {
                    val report = Report.of(diffDiagnostics(), emptyList(), emptyList(), strict)
                    HumanRenderer.render(
                        report,
                        Sources.of(oldSources + newSources),
                        Palette.NONE,
                        out = "",
                        width = 400,
                    )
                }
                xsd.isNotEmpty() -> {
                    val report = Report.of(importDiagnostics(), emptyList(), emptyList(), strict)
                    HumanRenderer.render(
                        report,
                        Sources.of(xsd),
                        Palette.NONE,
                        out = "",
                        width = 400,
                    )
                }
                else -> {
                    val result = Pipeline.check(sources, targets, strict)
                    val report = Report.of(result, strict, checkOnly = true)
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

    private fun diagnostics(): List<Diagnostic> =
        when {
            isDiff -> diffDiagnostics()
            xsd.isNotEmpty() -> importDiagnostics()
            else -> Pipeline.check(sources, targets, strict).diagnostics
        }

    /**
     * The diagnostics `schemata diff` reports for the two sides: [cannotDiff]'s when either side
     * fails to load or the two share no namespace, else the comparison's. Both sides are analysed
     * the way `diff` analyses them, so a `strict` header only promotes notes, as `--strict` does.
     */
    private fun diffDiagnostics(): List<Diagnostic> {
        val old = analyzeSide(oldSources)
        val new = analyzeSide(newSources)
        val cannotDiff = cannotDiff(old, new)
        if (cannotDiff.isNotEmpty()) return cannotDiff
        return Evolution.compare(old.schema!!, new.schema!!, rulebooks).diagnostics
    }

    private fun importDiagnostics(): List<Diagnostic> =
        XsdImporter.import(xsd.map { ImportInput(it.path, it.content) }).diagnostics

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
