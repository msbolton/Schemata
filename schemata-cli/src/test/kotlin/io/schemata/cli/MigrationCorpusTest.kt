package io.schemata.cli

import io.schemata.cli.report.HumanRenderer
import io.schemata.cli.report.MigrateRenderer
import io.schemata.cli.report.Palette
import io.schemata.cli.report.Report
import io.schemata.cli.report.Sources
import io.schemata.migrate.MigrationRenderer
import java.io.File
import kotlin.test.assertEquals
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * Every `src/test/resources/evolution/<case>` migrates its `old/` side to its `new/` side with
 * destructive steps allowed, and checks the step list plus the rendered diagnostics against
 * `expected/migrate.txt` and the written files, each headed by `-- <path>`, against
 * `expected/migrate.sql`: what `schemata migrate --allow-destructive` itself prints and writes, at
 * palette NONE and width 100. A case whose side has SQL errors records `not migrated` and those
 * errors. `SCHEMATA_GOLDEN_UPDATE=1` rewrites both.
 */
class MigrationCorpusTest {
    private val corpus = File("src/test/resources/evolution")
    private val update = System.getenv("SCHEMATA_GOLDEN_UPDATE") == "1"

    @TestFactory
    fun `corpus cases migrate as expected`(): List<DynamicTest> =
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
        val migrated = migrate(old, new, allowDestructive = true)
        val steps =
            if (migrated.lowered) MigrateRenderer.steps(migrated.migration)
            else "not migrated: a side has sql errors\n"
        val rendered =
            HumanRenderer.render(
                Report.of(migrated.diagnostics, emptyList(), emptyList(), strict = false),
                Sources.of(oldSources + newSources),
                Palette.NONE,
                out = "",
                width = 100,
            )
        val text = steps + "\n" + rendered
        val files =
            if (migrated.lowered)
                MigrationRenderer.files(migrated.migration, allowDestructive = true)
            else emptyList()
        val sql = joinFiles(files)
        val expectedDir = File(case, "expected")
        val txtFile = File(expectedDir, "migrate.txt")
        val sqlFile = File(expectedDir, "migrate.sql")
        if (update) {
            expectedDir.mkdirs()
            txtFile.writeText(text)
            sqlFile.writeText(sql)
        }
        assertEquals(
            txtFile.readText(),
            text,
            "migrate.txt for ${case.name}; run with SCHEMATA_GOLDEN_UPDATE=1 to accept",
        )
        assertEquals(
            sqlFile.readText(),
            sql,
            "migrate.sql for ${case.name}; run with SCHEMATA_GOLDEN_UPDATE=1 to accept",
        )
    }

    /**
     * Each file as `-- <path>` and its content, one blank line between files, whether or not a
     * file's content ends in a newline; nothing for no files.
     */
    private fun joinFiles(files: List<Pair<String, String>>): String =
        if (files.isEmpty()) ""
        else
            files.joinToString("\n\n", postfix = "\n") { (path, content) ->
                "-- $path\n${content.trimEnd('\n')}"
            }

    @Test
    fun `files join with one blank line whether or not they end in a newline`() {
        val expected = "-- a.sql\none\n\n-- b.sql\ntwo\n\n-- c.sql\nthree\n"
        assertEquals(
            expected,
            joinFiles(listOf("a.sql" to "one\n", "b.sql" to "two", "c.sql" to "three\n")),
        )
        assertEquals("", joinFiles(emptyList()))
    }

    /** `.schemata` files directly in [dir], paths kept under [prefix] (`old/s.schemata`). */
    private fun sources(dir: File, prefix: String): List<SourceInput> =
        dir.listFiles { f -> f.extension == "schemata" }!!
            .sortedBy { it.name }
            .map { SourceInput("$prefix/${it.name}", it.readText()) }
}
