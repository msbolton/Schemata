package io.schemata.cli.guide

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/** Every relative link in the README and the guide points at a path that exists. */
class GuideLinksTest {
    private val files =
        listOf(File(Guide.root, "README.md")) +
            Guide.dir.listFiles { f -> f.extension == "md" }!!.sortedBy { it.name }

    @TestFactory
    fun `relative links resolve`(): List<DynamicTest> =
        files.flatMap { file ->
            Guide.relativeLinks(file.readText()).map { link ->
                DynamicTest.dynamicTest("${file.name} -> $link") {
                    val target = File(file.parentFile, link).canonicalFile
                    assertTrue(target.exists(), "${file.name} links to missing $link")
                }
            }
        }

    @Test
    fun `the stability page is linked from the readme and the reference`() {
        assertTrue(File(Guide.dir, "stability.md").isFile, "guide/stability.md is missing")
        assertTrue(File(Guide.root, "README.md").readText().contains("](guide/stability.md)"))
        val opening = File(Guide.dir, "reference.md").readLines().take(12).joinToString("\n")
        assertTrue(opening.contains("](stability.md)"), "the reference's opening does not link it")
    }

    @Test
    fun `the stability page names every code family and every target`() {
        val text = File(Guide.dir, "stability.md").readText()
        listOf("SCH0", "SCH1", "SCH20", "SCH21", "SCH22", "SCH23", "SCH24", "SCH25").forEach {
            assertTrue(text.contains(it), "$it is not mentioned")
        }
        listOf("Protobuf", "Postgres", "XML Schema", "JSON Schema").forEach {
            assertTrue(text.contains(it), "$it is not mentioned")
        }
    }
}
