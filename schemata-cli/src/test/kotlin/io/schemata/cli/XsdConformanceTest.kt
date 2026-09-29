package io.schemata.cli

import io.schemata.target.xsd.XsdTarget
import io.schemata.testkit.Xsd
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * Every corpus case with an `expected/xsd` tree renders exactly that tree, reports exactly the
 * warnings in `expected/xsd-warnings.txt` (none when the file is absent), and compiles under the
 * JDK's XML Schema processor. `SCHEMATA_GOLDEN_UPDATE=1` rewrites both.
 */
class XsdConformanceTest {
    private val corpus = File("src/test/resources/corpus")
    private val update = System.getenv("SCHEMATA_GOLDEN_UPDATE") == "1"

    @TestFactory
    fun `corpus cases render their expected tree and compile under the JDK`(): List<DynamicTest> =
        corpus
            .listFiles { f -> f.isDirectory && File(f, "expected/xsd").isDirectory }!!
            .sortedBy { it.name }
            .map { case -> DynamicTest.dynamicTest(case.name) { check(case) } }

    private fun check(case: File) {
        val inputs =
            case
                .listFiles { f -> f.extension == "schemata" }!!
                .sortedBy { it.name }
                .map { SourceInput(it.name, it.readText()) }
        val result = Pipeline.compile(inputs, listOf(XsdTarget))
        assertFalse(
            result.hasErrors,
            result.diagnostics.joinToString("\n") { "${it.code.id} ${it.message}" },
        )
        val actual = result.files.associate { it.file.path to it.file.content }
        val expectedDir = File(case, "expected/xsd")
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
        val warningsFile = File(case, "expected/xsd-warnings.txt")
        if (update) {
            if (warnings.isEmpty()) warningsFile.delete() else warningsFile.writeText(warnings)
        }
        assertEquals(
            if (warningsFile.isFile) warningsFile.readText() else "",
            warnings,
            "warnings for ${case.name}; run with SCHEMATA_GOLDEN_UPDATE=1 to accept",
        )
        assertNull(Xsd.validate(actual), "the JDK rejected ${case.name}")
    }
}
