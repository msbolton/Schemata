package io.schemata.cli

import io.schemata.cli.report.DiffRenderer
import io.schemata.cli.report.HumanRenderer
import io.schemata.cli.report.Palette
import io.schemata.cli.report.Report
import io.schemata.cli.report.Sources
import io.schemata.evolution.Evolution
import io.schemata.evolution.Rulebooks
import java.io.File
import kotlin.test.assertEquals
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * Every `src/test/resources/evolution/<case>` compares its `old/` and `new/` sides with every
 * rulebook and checks the human change list plus the rendered diagnostics against
 * `expected/diff.txt`, and the JSON comparison document against `expected/diff.json`: the same two
 * renderings `schemata diff` itself prints, at palette NONE and width 100.
 * `SCHEMATA_GOLDEN_UPDATE=1` rewrites both.
 */
class EvolutionCorpusTest {
    private val corpus = File("src/test/resources/evolution")
    private val update = System.getenv("SCHEMATA_GOLDEN_UPDATE") == "1"

    @TestFactory
    fun `corpus cases diff as expected`(): List<DynamicTest> =
        corpus
            .listFiles { f -> f.isDirectory }!!
            .sortedBy { it.name }
            .map { case -> DynamicTest.dynamicTest(case.name) { check(case) } }

    private fun check(case: File) {
        val oldSources = sources(File(case, "old"), "old")
        val newSources = sources(File(case, "new"), "new")
        val oldSide = analyzeSide(oldSources)
        val newSide = analyzeSide(newSources)
        val old = oldSide.schema
        val new = newSide.schema
        checkNotNull(old) { "${case.name}: OLD failed to analyze: ${oldSide.diagnostics}" }
        checkNotNull(new) { "${case.name}: NEW failed to analyze: ${newSide.diagnostics}" }
        val rulebooks = Rulebooks.all
        val comparison = Evolution.compare(old, new, rulebooks)
        val implicitOrdinals = oldSide.implicitOrdinals + newSide.implicitOrdinals
        val changes = DiffRenderer.changes(comparison, rulebooks, implicitOrdinals)
        val report = Report.of(comparison.diagnostics, emptyList(), emptyList(), strict = false)
        val rendered =
            HumanRenderer.render(
                report,
                Sources.of(oldSources + newSources),
                Palette.NONE,
                out = "",
                width = 100,
            )
        val text = changes + "\n" + rendered
        val json = DiffRenderer.json(comparison, report, rulebooks)
        val expectedDir = File(case, "expected")
        val txtFile = File(expectedDir, "diff.txt")
        val jsonFile = File(expectedDir, "diff.json")
        if (update) {
            expectedDir.mkdirs()
            txtFile.writeText(text)
            jsonFile.writeText(json)
        }
        assertEquals(
            txtFile.readText(),
            text,
            "diff.txt for ${case.name}; run with SCHEMATA_GOLDEN_UPDATE=1 to accept",
        )
        assertEquals(
            jsonFile.readText(),
            json,
            "diff.json for ${case.name}; run with SCHEMATA_GOLDEN_UPDATE=1 to accept",
        )
    }

    /**
     * `.schemata` files directly in [dir], paths kept under [prefix] (`old/s.schemata`) so the two
     * sides' excerpts don't collide once both share one [Sources] lookup.
     */
    private fun sources(dir: File, prefix: String): List<SourceInput> =
        dir.listFiles { f -> f.extension == "schemata" }!!
            .sortedBy { it.name }
            .map { SourceInput("$prefix/${it.name}", it.readText()) }
}
