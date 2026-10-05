package io.schemata.cli.guide

import io.schemata.cli.Pipeline
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * Every fenced `schemata` block in the reference and in the README compiles through both targets
 * without an error; every block fenced `schemata error SCHnnnn...` must report each named code
 * among its errors. A block may hold several files separated by a line `--- <name>.schemata`;
 * otherwise it is `example.schemata`. A README block whose body does not start with `namespace` is
 * a fragment, not a full example, and fails with a message naming it instead of being compiled.
 */
class GuideReferenceTest {
    private val referenceFile = File(Guide.dir, "reference.md")
    private val readmeFile = File(Guide.root, "README.md")

    @TestFactory
    fun `reference and README blocks compile or fail as marked`(): List<DynamicTest> =
        listOf(referenceFile, readmeFile).flatMap { file ->
            Guide.blocks(file.readText(), "schemata").map { block ->
                DynamicTest.dynamicTest("${file.name}:${block.line}") { check(file, block) }
            }
        }

    @Test
    fun `reference has at least one schemata block`() {
        assertTrue(Guide.blocks(referenceFile.readText(), "schemata").isNotEmpty())
    }

    private fun check(file: File, block: Guide.Block) {
        if (file == readmeFile && !block.body.trimStart().startsWith("namespace"))
            fail("${file.name}:${block.line} is not a full example; give it a namespace line")
        val result = Pipeline.check(Guide.sources(block.body), Pipeline.targets, strict = false)
        val errors = result.diagnostics.filter { it.severity.name == "ERROR" }
        val words = block.info.split(' ').filter { it.isNotBlank() }
        val mustFail = words.contains("error")
        val codes = words.dropWhile { it != "error" }.drop(1)
        val report =
            errors.joinToString("\n") {
                "${it.code.id} ${it.span.file}:${it.span.startLine} ${it.message}"
            }
        if (mustFail) {
            assertTrue(
                errors.isNotEmpty(),
                "block at ${file.name}:${block.line} is marked error but compiled",
            )
            assertTrue(
                codes.isNotEmpty(),
                "block at ${file.name}:${block.line} is marked error but names no code",
            )
            codes.forEach { code ->
                assertTrue(
                    errors.any { it.code.id == code },
                    "block at ${file.name}:${block.line} does not report $code:\n$report",
                )
            }
        } else
            assertTrue(
                errors.isEmpty(),
                "block at ${file.name}:${block.line} does not compile:\n$report",
            )
    }
}
