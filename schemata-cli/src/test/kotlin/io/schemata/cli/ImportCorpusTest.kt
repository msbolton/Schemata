package io.schemata.cli

import io.schemata.importer.xsd.ImportInput
import io.schemata.importer.xsd.XsdImporter
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * Every `src/test/resources/import/<case>/` directory imports its `.xsd` files into exactly its
 * `expected/` tree, reports exactly the warnings in `expected/import-warnings.txt` (none when the
 * file is absent), and compiles under every target without errors. `SCHEMATA_GOLDEN_UPDATE=1`
 * rewrites both.
 */
class ImportCorpusTest {
    private val root = File("src/test/resources/import")
    private val update = System.getenv("SCHEMATA_GOLDEN_UPDATE") == "1"

    @TestFactory
    fun `import corpus cases render their expected tree and compile under every target`():
        List<DynamicTest> =
        root
            .listFiles { f -> f.isDirectory }!!
            .sortedBy { it.name }
            .map { case -> DynamicTest.dynamicTest(case.name) { check(case) } }

    private fun check(case: File) {
        val inputs =
            case
                .listFiles { f -> f.extension == "xsd" }!!
                .sortedBy { it.name }
                .map { ImportInput(it.name, it.readText()) }
        val result = XsdImporter.import(inputs)
        val actual = result.files.associate { it.path to it.content }
        val expectedDir = File(case, "expected")
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
                .filter { it.isFile && it.name != "import-warnings.txt" }
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
        val warningsFile = File(expectedDir, "import-warnings.txt")
        if (update) {
            if (warnings.isEmpty()) warningsFile.delete() else warningsFile.writeText(warnings)
        }
        assertEquals(
            if (warningsFile.isFile) warningsFile.readText() else "",
            warnings,
            "import warnings for ${case.name}; run with SCHEMATA_GOLDEN_UPDATE=1 to accept",
        )
        val all =
            Pipeline.compile(
                result.files.map { SourceInput(it.path, it.content) },
                Pipeline.targets,
            )
        assertFalse(
            all.hasErrors,
            "imported ${case.name} under all targets: " +
                all.diagnostics.joinToString("\n") { "${it.code.id} ${it.message}" },
        )
    }
}
