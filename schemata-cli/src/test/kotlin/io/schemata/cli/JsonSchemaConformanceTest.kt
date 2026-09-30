package io.schemata.cli

import io.schemata.target.jsonschema.JsonSchemaTarget
import io.schemata.testkit.JsonSchema
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * Every corpus case with an `expected/jsonschema` tree renders exactly that tree, reports exactly
 * the warnings in `expected/jsonschema-warnings.txt` (none when the file is absent), and is valid
 * draft 2020-12 with every reference resolved. `SCHEMATA_GOLDEN_UPDATE=1` rewrites both.
 */
class JsonSchemaConformanceTest {
    private val corpus = File("src/test/resources/corpus")
    private val update = System.getenv("SCHEMATA_GOLDEN_UPDATE") == "1"

    @TestFactory
    fun `corpus cases render their expected tree and validate`(): List<DynamicTest> =
        corpus
            .listFiles { f -> f.isDirectory && File(f, "expected/jsonschema").isDirectory }!!
            .sortedBy { it.name }
            .map { case -> DynamicTest.dynamicTest(case.name) { check(case) } }

    private fun check(case: File) {
        val result = Pipeline.compile(TestSources.of(case), listOf(JsonSchemaTarget))
        assertFalse(
            result.hasErrors,
            result.diagnostics.joinToString("\n") { "${it.code.id} ${it.message}" },
        )
        val actual = result.files.associate { it.file.path to it.file.content }
        val expectedDir = File(case, "expected/jsonschema")
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
        val warnings = result.diagnostics.joinToString("") { "${it.code.id} ${it.message}\n" }
        val warningsFile = File(case, "expected/jsonschema-warnings.txt")
        if (update) {
            if (warnings.isEmpty()) warningsFile.delete() else warningsFile.writeText(warnings)
        }
        assertEquals(
            if (warningsFile.isFile) warningsFile.readText() else "",
            warnings,
            "warnings for ${case.name}",
        )
        assertNull(JsonSchema.validate(actual), "the validator rejected ${case.name}")
    }
}
