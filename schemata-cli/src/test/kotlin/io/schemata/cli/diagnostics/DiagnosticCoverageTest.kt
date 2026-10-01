package io.schemata.cli.diagnostics

import io.schemata.cli.Pipeline
import io.schemata.core.CoreCodes
import io.schemata.importer.xsd.ImportCodes
import io.schemata.lang.LangCodes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every live code has a fixture, every diagnostic a fixture produces carries help, and a fixture
 * produces only the codes its name and expected text announce.
 */
class DiagnosticCoverageTest {
    private val live =
        (LangCodes.all + CoreCodes.all + Pipeline.targets.flatMap { it.codes } + ImportCodes.all)
            .map { it.id }
            .toSet()

    @Test
    fun `every live code has a fixture unless listed as pending`() {
        val covered = Fixture.all().map { it.code }.toSet()
        val pending = Fixture.pending()
        assertEquals(emptySet(), pending - live, "pending.txt names codes that do not exist")
        assertEquals(
            emptySet(),
            pending intersect covered,
            "pending.txt lists codes that already have fixtures",
        )
        assertEquals(emptySet(), live - covered - pending, "codes without a fixture")
    }

    @Test
    fun `every fixture diagnostic carries help`() {
        val missing =
            Fixture.all().flatMap { f ->
                f.helps().filter { it.second == null }.map { "${f.name}: ${it.first}" }
            }
        assertEquals(emptyList(), missing)
    }

    @Test
    fun `a fixture produces its own code and only codes its expected text names`() {
        Fixture.all().forEach { f ->
            val produced = f.codes()
            assertTrue(f.code in produced, "${f.name} does not produce ${f.code}")
            val named = Regex("(SCH\\d{4})").findAll(f.expected).map { it.value }.toSet()
            assertEquals(
                emptySet(),
                produced - named,
                "${f.name} produces codes its expected.txt does not name",
            )
        }
    }
}
