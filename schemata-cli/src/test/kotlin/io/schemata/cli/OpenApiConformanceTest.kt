package io.schemata.cli

import io.schemata.cli.guide.Guide
import io.schemata.core.ir.services
import io.schemata.target.openapi.OpenApiTarget
import io.schemata.testkit.OpenApi
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * Every OpenAPI document the compiler writes is valid OpenAPI 3.1: each corpus case with services
 * renders exactly its `expected/openapi` tree and the warnings in `expected/openapi-warnings.txt`
 * (none when the file is absent), each example with services validates (its goldens are
 * `ExamplesTest`'s), and so does each guide block that declares a service. A source set without a
 * service writes no document. `SCHEMATA_GOLDEN_UPDATE=1` rewrites the corpus goldens.
 */
class OpenApiConformanceTest {
    private val corpus = File("src/test/resources/corpus")
    private val examples = File("../examples")
    private val update = System.getenv("SCHEMATA_GOLDEN_UPDATE") == "1"
    private val service = Regex("(?m)^\\s*(@[^\\n]*\\s+)?service\\s")

    @TestFactory
    fun `corpus cases with services render their expected tree and validate`(): List<DynamicTest> =
        dirsWithServices(corpus).map { case ->
            DynamicTest.dynamicTest(case.name) {
                val result = compile(TestSources.of(case), case.name)
                val files = filesOf(result)
                golden(
                    case,
                    files,
                    result.diagnostics.joinToString("") { "${it.code.id} ${it.message}\n" },
                )
                validate(files, case.name)
            }
        }

    @TestFactory
    fun `examples with services validate`(): List<DynamicTest> =
        dirsWithServices(examples).map { dir ->
            DynamicTest.dynamicTest(dir.name) {
                val files = filesOf(compile(TestSources.of(dir), dir.name))
                assertTrue(files.isNotEmpty(), "${dir.name} declares a service but writes nothing")
                validate(files, dir.name)
            }
        }

    @TestFactory
    fun `guide blocks with services validate`(): List<DynamicTest> =
        listOf(File(Guide.dir, "reference.md"), File(Guide.root, "README.md")).flatMap { file ->
            Guide.blocks(file.readText(), "schemata")
                .filter { "error" !in it.info.split(' ') && service.containsMatchIn(it.body) }
                .map { block ->
                    val name = "${file.name}:${block.line}"
                    DynamicTest.dynamicTest(name) {
                        val files = filesOf(compile(Guide.sources(block.body), name))
                        assertTrue(
                            files.isNotEmpty(),
                            "$name declares a service but writes nothing",
                        )
                        validate(files, name)
                    }
                }
        }

    /** The directories under [root] whose sources declare at least one service. */
    private fun dirsWithServices(root: File): List<File> =
        root
            .listFiles { f -> f.isDirectory }!!
            .sortedBy { it.name }
            .filter { dir ->
                val schema = Pipeline.analyze(TestSources.of(dir)).schema
                schema != null && schema.services().isNotEmpty()
            }

    private fun compile(sources: List<SourceInput>, name: String): PipelineResult {
        val result = Pipeline.compile(sources, listOf(OpenApiTarget))
        assertFalse(
            result.hasErrors,
            "$name: " + result.diagnostics.joinToString("\n") { "${it.code.id} ${it.message}" },
        )
        return result
    }

    private fun filesOf(result: PipelineResult): Map<String, String> =
        result.files.associate { it.file.path to it.file.content }

    private fun validate(files: Map<String, String>, name: String) {
        files.forEach { (path, content) ->
            assertNull(OpenApi.validate(content), "the validator rejected $path in $name")
        }
    }

    private fun golden(case: File, actual: Map<String, String>, warnings: String) {
        val expectedDir = File(case, "expected/openapi")
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
        val warningsFile = File(case, "expected/openapi-warnings.txt")
        if (update) {
            if (warnings.isEmpty()) warningsFile.delete() else warningsFile.writeText(warnings)
        }
        assertEquals(
            if (warningsFile.isFile) warningsFile.readText() else "",
            warnings,
            "warnings for ${case.name}",
        )
    }
}
