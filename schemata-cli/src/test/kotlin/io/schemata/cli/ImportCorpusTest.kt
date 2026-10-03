package io.schemata.cli

import io.schemata.importer.ImportInput
import io.schemata.importer.proto.ProtoImporter
import io.schemata.importer.sql.SqlImporter
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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * Every `src/test/resources/import/<case>/` directory imports the `.xsd`, `.proto`, or `.sql` files
 * under it (named by their path relative to the case, `expected/` aside, which is also a proto or
 * sql file's path under its root; an include or import outside the inputs is read from the case
 * directory too) into exactly its `expected/` tree, reports exactly the warnings in
 * `expected/import-warnings.txt` (none when the file is absent), and compiles under proto, xsd, and
 * jsonschema without errors. The sql target cannot carry a key the xsd never declared, nor some
 * shapes it documents as beyond it, so its errors other than a missing key are compared with
 * `expected/sql-errors.txt` (none when absent). `SCHEMATA_GOLDEN_UPDATE=1` rewrites the tree, the
 * warnings, and the sql errors.
 */
class ImportCorpusTest {
    private val root = File("src/test/resources/import")
    private val update = System.getenv("SCHEMATA_GOLDEN_UPDATE") == "1"
    private val goldenTexts = setOf("import-warnings.txt", "sql-errors.txt")

    @TestFactory
    fun `import corpus cases render their expected tree and compile under every target`():
        List<DynamicTest> =
        root
            .listFiles { f -> f.isDirectory }!!
            .sortedBy { it.name }
            .map { case -> DynamicTest.dynamicTest(case.name) { check(case) } }

    private fun check(case: File) {
        val sources =
            case
                .walkTopDown()
                .filter {
                    it.isFile &&
                        it.extension in setOf("xsd", "proto", "sql") &&
                        !it.relativeTo(case).path.startsWith("expected")
                }
                .sortedBy { it.relativeTo(case).path }
                .toList()
        val extensions = sources.map { it.extension }.toSet()
        check(extensions.size == 1) {
            "${case.name} must hold .xsd, .proto, or .sql inputs, one kind only: $extensions"
        }
        val format = extensions.single()
        val inputs =
            sources.map {
                val path = it.relativeTo(case).path.replace(File.separatorChar, '/')
                ImportInput(path, it.readText(), if (format == "xsd") null else path)
            }
        val importer =
            when (format) {
                "proto" -> ProtoImporter
                "sql" -> SqlImporter
                else -> XsdImporter
            }
        val result =
            importer.import(inputs, null) { path ->
                File(case, path).takeIf { it.isFile }?.let { ImportInput(path, it.readText()) }
            }
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
                .filter { it.isFile && it.name !in goldenTexts }
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
        val imported = result.files.map { SourceInput(it.path, it.content) }
        val noSql = Pipeline.compile(imported, listOf(ProtoTarget, XsdTarget, JsonSchemaTarget))
        assertFalse(
            noSql.hasErrors,
            "imported ${case.name} under proto, xsd, and jsonschema: " +
                noSql.diagnostics
                    .filter { it.severity == Severity.ERROR }
                    .joinToString("\n") { "${it.code.id} ${it.message}" },
        )
        val sqlErrors =
            Pipeline.compile(imported, listOf(SqlTarget))
                .diagnostics
                .filter { it.severity == Severity.ERROR && it.code != SqlCodes.MISSING_KEY }
                .joinToString("") { "${it.code.id} ${it.message}\n" }
        val sqlErrorsFile = File(expectedDir, "sql-errors.txt")
        if (update) {
            if (sqlErrors.isEmpty()) sqlErrorsFile.delete() else sqlErrorsFile.writeText(sqlErrors)
        }
        assertEquals(
            if (sqlErrorsFile.isFile) sqlErrorsFile.readText() else "",
            sqlErrors,
            "sql errors other than a missing key for ${case.name}; " +
                "run with SCHEMATA_GOLDEN_UPDATE=1 to accept",
        )
    }

    /**
     * The same schema hand-written and as pg_dump writes it imports to the same Schemata: only the
     * warnings may differ.
     */
    @Test
    fun `a hand written schema and its dump import alike`() {
        fun tree(case: String): Map<String, String> {
            val dir = File(root, "$case/expected")
            return dir.walkTopDown()
                .filter { it.isFile && it.name !in goldenTexts }
                .associate { it.relativeTo(dir).path to it.readText() }
        }
        val hand = tree("sql-handwritten")
        assertTrue(hand.isNotEmpty(), "sql-handwritten has an expected tree")
        assertEquals(hand, tree("sql-dump"))
    }
}
