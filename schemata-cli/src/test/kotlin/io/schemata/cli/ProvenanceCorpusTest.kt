package io.schemata.cli

import io.schemata.lang.Severity
import io.schemata.target.sql.SqlTarget
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * `schemata migrate` matches tables and columns by origin, so an origin must be an identity: no two
 * tables of a schema, and no two columns of a table, share one. Checked over every side of every
 * evolution corpus case and every example that lowers under the SQL target; it lives here rather
 * than beside the SQL target's own provenance tests because those sources use every target's
 * annotations, which only the CLI registers.
 */
class ProvenanceCorpusTest {
    private val corpus = File("src/test/resources/evolution")
    private val examples = File("../examples")

    @TestFactory
    fun `origins are unique per schema and per table`(): List<DynamicTest> {
        val cases =
            corpus
                .listFiles { f -> f.isDirectory }!!
                .sortedBy { it.name }
                .flatMap { case ->
                    listOf("old", "new").map { side -> "${case.name}/$side" to File(case, side) }
                }
        val trees =
            examples
                .listFiles { f -> f.isDirectory }!!
                .sortedBy { it.name }
                .map { "examples/${it.name}" to it }
        return (cases + trees).map { (name, dir) ->
            DynamicTest.dynamicTest(name) { check(name, dir) }
        }
    }

    private fun check(name: String, dir: File) {
        val sources =
            dir.listFiles { f -> f.extension == "schemata" }!!
                .sortedBy { it.name }
                .map { SourceInput(it.name, it.readText()) }
        val schema = analyzeSide(sources).schema
        assertNotNull(schema, "$name does not analyse")
        val lowered = SqlTarget.lower(schema)
        if (lowered.diagnostics.any { it.severity == Severity.ERROR }) return
        lowered.model.schemas.forEach { s ->
            val tables = s.tables.groupBy { it.origin }.filterValues { it.size > 1 }
            assertEquals(emptyMap(), tables.mapValues { (_, t) -> t.map { it.name } }, name)
            s.tables.forEach { table ->
                val columns = table.columns.groupBy { it.origin }.filterValues { it.size > 1 }
                assertEquals(
                    emptyMap(),
                    columns.mapValues { (_, c) -> c.map { it.name } },
                    "$name: ${table.name}",
                )
            }
        }
    }
}
