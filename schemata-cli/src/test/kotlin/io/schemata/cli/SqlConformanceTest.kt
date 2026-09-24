package io.schemata.cli

import io.schemata.target.sql.SqlTarget
import io.schemata.testkit.Postgres
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * Every corpus case with an `expected/sql` tree renders exactly that tree, applies cleanly to a
 * real Postgres, and leaves the catalog described by `expected/sql-catalog.txt`.
 * `SCHEMATA_GOLDEN_UPDATE=1` rewrites both.
 */
class SqlConformanceTest {
    private val corpus = File("src/test/resources/corpus")
    private val update = System.getenv("SCHEMATA_GOLDEN_UPDATE") == "1"

    @TestFactory
    fun `corpus cases render their DDL and match the live catalog`(): List<DynamicTest> =
        corpus
            .listFiles { f -> f.isDirectory && File(f, "expected/sql").isDirectory }!!
            .sortedBy { it.name }
            .map { case -> DynamicTest.dynamicTest(case.name) { check(case) } }

    private fun check(case: File) {
        val inputs =
            case
                .listFiles { f -> f.extension == "schemata" }!!
                .sortedBy { it.name }
                .map { SourceInput(it.name, it.readText()) }
        val result = Pipeline.compile(inputs, listOf(SqlTarget))
        assertFalse(
            result.hasErrors,
            result.diagnostics.joinToString("\n") { "${it.code.id} ${it.message}" },
        )
        val actual = result.files.associate { it.file.path to it.file.content }
        val expectedDir = File(case, "expected/sql")
        if (update) {
            expectedDir.deleteRecursively()
            actual.forEach { (path, content) ->
                File(expectedDir, path).apply {
                    parentFile.mkdirs()
                    writeText(content)
                }
            }
        }
        val expected =
            expectedDir
                .walkTopDown()
                .filter { it.isFile }
                .associate {
                    it.relativeTo(expectedDir).path.replace(File.separatorChar, '/') to
                        it.readText()
                }
        assertEquals(expected.keys, actual.keys, "output paths for ${case.name}")
        expected.forEach { (path, content) ->
            assertEquals(
                content,
                actual.getValue(path),
                "$path in ${case.name}; run with SCHEMATA_GOLDEN_UPDATE=1 to accept",
            )
        }
        assumeTrue(Postgres.available, "Docker is not available; skipping the live catalog check")
        val catalog = Postgres.withDatabase(actual) { Postgres.catalog(it) }
        val snapshot = File(case, "expected/sql-catalog.txt")
        if (update) snapshot.writeText(catalog)
        assertEquals(
            snapshot.readText(),
            catalog,
            "live catalog for ${case.name}; run with SCHEMATA_GOLDEN_UPDATE=1 to accept",
        )
    }
}
