package io.schemata.cli

import io.schemata.core.CoreCodes
import io.schemata.evolution.EvolutionCodes
import io.schemata.importer.ImportCodes
import io.schemata.lang.LangCodes
import io.schemata.migrate.MigrateCodes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The catalogs live in different modules; this is the one place that can see them all. */
class DiagnosticCodesTest {
    private val all =
        LangCodes.all +
            CoreCodes.all +
            Pipeline.targets.flatMap { it.codes } +
            ImportCodes.all +
            EvolutionCodes.all +
            MigrateCodes.all

    @Test
    fun `every code id is unique across modules`() {
        val duplicates = all.groupBy { it.id }.filter { it.value.size > 1 }.keys
        assertEquals(emptySet(), duplicates)
    }

    @Test
    fun `every code id matches SCH followed by four digits`() {
        all.forEach { assertTrue(Regex("SCH\\d{4}").matches(it.id), it.id) }
    }

    @Test
    fun `ranges are respected by module`() {
        assertTrue(LangCodes.all.all { it.id.startsWith("SCH0") })
        assertTrue(CoreCodes.all.all { it.id.startsWith("SCH1") })
        assertTrue(Pipeline.targetNamed("proto")!!.codes.all { it.id.startsWith("SCH20") })
        assertTrue(Pipeline.targetNamed("sql")!!.codes.all { it.id.startsWith("SCH21") })
        assertTrue(Pipeline.targetNamed("xsd")!!.codes.all { it.id.startsWith("SCH22") })
        assertTrue(Pipeline.targetNamed("jsonschema")!!.codes.all { it.id.startsWith("SCH23") })
        assertTrue(ImportCodes.all.all { it.id.startsWith("SCH24") })
        assertTrue(EvolutionCodes.all.all { it.id.startsWith("SCH25") })
        assertTrue(Pipeline.targetNamed("openapi")!!.codes.all { it.id.startsWith("SCH26") })
        assertTrue(MigrateCodes.all.all { it.id.startsWith("SCH27") })
    }

    @Test
    fun `every target publishes at least one code`() {
        Pipeline.targets.forEach { assertTrue(it.codes.isNotEmpty(), it.name) }
    }

    @Test
    fun `every live code has a one-clause description`() {
        val bad =
            all.filter {
                it.description.isBlank() ||
                    it.description.first().isUpperCase() ||
                    it.description.endsWith(".") ||
                    it.description.contains(it.id)
            }
        assertEquals(emptyList(), bad.map { it.id })
    }
}
