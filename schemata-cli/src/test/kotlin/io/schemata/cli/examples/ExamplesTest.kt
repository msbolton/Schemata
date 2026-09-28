package io.schemata.cli.examples

import io.schemata.cli.Pipeline
import io.schemata.cli.SourceInput
import io.schemata.target.proto.ProtoTarget
import io.schemata.target.sql.SqlTarget
import io.schemata.testkit.Postgres
import io.schemata.testkit.Protoc
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * Every directory under `examples/` compiles to both targets without errors, renders exactly its
 * `expected/` tree and warnings, compiles under protoc, applies to Postgres, and matches the
 * committed catalog snapshot. `SCHEMATA_GOLDEN_UPDATE=1` rewrites the tree.
 */
class ExamplesTest {
    private val root = File("../examples")
    private val update = System.getenv("SCHEMATA_GOLDEN_UPDATE") == "1"

    @TestFactory
    fun `examples compile, match their expected output, and pass protoc and Postgres`():
        List<DynamicTest> =
        root
            .listFiles { f -> f.isDirectory }!!
            .sortedBy { it.name }
            .map { dir -> DynamicTest.dynamicTest(dir.name) { check(dir) } }

    @TestFactory
    fun `ledger is clean under strict`(): List<DynamicTest> =
        listOf(
            DynamicTest.dynamicTest("ledger --strict") {
                val result =
                    Pipeline.check(sources(File(root, "ledger")), Pipeline.targets, strict = true)
                assertEquals(
                    emptyList(),
                    result.diagnostics.filter { it.code.id == "SCH1014" }.map { it.message },
                )
            }
        )

    private fun sources(dir: File) =
        dir.listFiles { f -> f.extension == "schemata" }!!
            .sortedBy { it.name }
            .map { SourceInput(it.name, it.readText()) }

    private fun check(dir: File) {
        val inputs = sources(dir)
        val result = Pipeline.compile(inputs, listOf(ProtoTarget, SqlTarget))
        assertFalse(
            result.hasErrors,
            result.diagnostics.joinToString("\n") { "${it.code.id} ${it.message}" },
        )
        val expected = File(dir, "expected")
        val coreWarnings = result.core.joinToString("") { "${it.code.id} ${it.message}\n" }
        val coreWarningsFile = File(expected, "core-warnings.txt")
        if (update) {
            if (coreWarnings.isEmpty()) coreWarningsFile.delete()
            else {
                expected.mkdirs()
                coreWarningsFile.writeText(coreWarnings)
            }
        }
        assertEquals(
            if (coreWarningsFile.isFile) coreWarningsFile.readText() else "",
            coreWarnings,
            "core warnings for ${dir.name}",
        )
        for (target in result.targets) {
            val files = target.files.associate { it.path to it.content }
            golden(File(expected, target.name), files, dir.name)
            val warnings = target.diagnostics.joinToString("") { "${it.code.id} ${it.message}\n" }
            val warningsFile = File(expected, "${target.name}-warnings.txt")
            if (update) {
                if (warnings.isEmpty()) warningsFile.delete() else warningsFile.writeText(warnings)
            }
            assertEquals(
                if (warningsFile.isFile) warningsFile.readText() else "",
                warnings,
                "${target.name} warnings for ${dir.name}",
            )
            when (target.name) {
                "proto" -> assertNull(Protoc.compile(files), "protoc rejected ${dir.name}")
                "sql" -> {
                    assumeTrue(
                        Postgres.available,
                        "Docker is not available; skipping the live catalog check",
                    )
                    val catalog = Postgres.withDatabase(files) { Postgres.catalog(it) }
                    val snapshot = File(expected, "sql-catalog.txt")
                    if (update) snapshot.writeText(catalog)
                    assertEquals(snapshot.readText(), catalog, "live catalog for ${dir.name}")
                }
            }
        }
    }

    private fun golden(dir: File, actual: Map<String, String>, name: String) {
        if (update) {
            dir.deleteRecursively()
            actual.forEach { (path, content) ->
                File(dir, path).apply {
                    parentFile.mkdirs()
                    writeText(content)
                }
            }
        }
        val expected =
            dir.walkTopDown()
                .filter { it.isFile }
                .associate {
                    it.relativeTo(dir).path.replace(File.separatorChar, '/') to it.readText()
                }
        assertEquals(expected.keys, actual.keys, "output paths for $name")
        expected.forEach { (path, content) ->
            assertEquals(content, actual.getValue(path), "$path in $name")
        }
    }
}
