package io.schemata.cli.diagnostics

import io.schemata.cli.Pipeline
import io.schemata.cli.SourceInput
import io.schemata.cli.report.HumanRenderer
import io.schemata.cli.report.Palette
import io.schemata.cli.report.Report
import io.schemata.cli.report.Sources
import io.schemata.importer.xsd.ImportInput
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
    val targets: List<Target<*>> =
        header
            ?.let { Regex("targets=([a-z,]+)").find(it) }
            ?.groupValues
            ?.get(1)
            ?.split(',')
            ?.map { Pipeline.targetNamed(it) ?: error("$name: unknown target '$it'") }
            ?: Pipeline.targets

    val sources: List<SourceInput> =
        dir.listFiles { f -> f.extension == "schemata" }!!
            .sortedBy { it.name }
            .map { SourceInput(it.name, it.readText()) }

    /** `.xsd` sources for an import fixture; non-empty exactly when [render] runs the importer. */
    val xsd: List<SourceInput> =
        dir.listFiles { f -> f.extension == "xsd" }!!
            .sortedBy { it.name }
            .map { SourceInput(it.name, it.readText()) }

    val expected: String
        get() = expectedFile.readText().let { if (header != null) it.substringAfter('\n') else it }

    /** The report as the CLI prints it, without excerpt and gutter lines. */
    fun render(): String {
        val full =
            if (xsd.isNotEmpty()) {
                val report = Report.of(importDiagnostics(), emptyList(), emptyList(), strict)
                HumanRenderer.render(report, Sources.of(xsd), Palette.NONE, out = "", width = 400)
            } else {
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
        return full.lines().filter { keep(it) }.joinToString("\n").trimEnd() + "\n"
    }

    fun codes(): Set<String> = diagnostics().map { it.code.id }.toSet()

    fun helps(): List<Pair<String, String?>> = diagnostics().map { it.code.id to it.help }

    private fun diagnostics(): List<Diagnostic> =
        if (xsd.isNotEmpty()) importDiagnostics()
        else Pipeline.check(sources, targets, strict).diagnostics

    private fun importDiagnostics(): List<Diagnostic> =
        XsdImporter.import(xsd.map { ImportInput(it.path, it.content) }).diagnostics

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
