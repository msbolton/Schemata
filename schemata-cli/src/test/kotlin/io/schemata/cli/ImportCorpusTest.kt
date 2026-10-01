package io.schemata.cli

import io.schemata.importer.xsd.ImportInput
import io.schemata.importer.xsd.XsdImporter
import io.schemata.lang.Severity
import io.schemata.target.jsonschema.JsonSchemaTarget
import io.schemata.target.proto.ProtoTarget
import io.schemata.target.sql.SqlCodes
import io.schemata.target.sql.SqlTarget
import io.schemata.target.xsd.XsdTarget
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * Every `src/test/resources/import/<case>/` directory imports its `.xsd` files into exactly its
 * `expected/` tree, reports exactly the warnings in `expected/import-warnings.txt` (none when the
 * file is absent), and compiles under proto, xsd, and jsonschema without errors; the sql target
 * cannot carry a key the xsd never declared, so it is checked separately, only for errors other
 * than a missing key. `SCHEMATA_GOLDEN_UPDATE=1` rewrites the tree and the warnings.
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
        val sources = result.files.map { SourceInput(it.path, it.content) }
        val noSql = Pipeline.compile(sources, listOf(ProtoTarget, XsdTarget, JsonSchemaTarget))
        assertFalse(
            noSql.hasErrors,
            "imported ${case.name} under proto, xsd, and jsonschema: " +
                noSql.diagnostics
                    .filter { it.severity == Severity.ERROR }
                    .joinToString("\n") { "${it.code.id} ${it.message}" },
        )
        val sqlErrors =
            Pipeline.compile(sources, listOf(SqlTarget)).diagnostics.filter {
                it.severity == Severity.ERROR
            }
        assertTrue(
            sqlErrors.all { it.code == SqlCodes.MISSING_KEY },
            "imported ${case.name} under sql had an error other than a missing key: " +
                sqlErrors.joinToString("\n") { "${it.code.id} ${it.message}" },
        )
    }
}
