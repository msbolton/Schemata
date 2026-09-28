package io.schemata.cli.guide

import java.io.File
import kotlin.test.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * Every schema excerpt and quoted warning in examples.md is exactly what the example directory
 * holds.
 */
class GuideExamplesTest {
    private val text = File(Guide.dir, "examples.md").readText()
    private val lines = text.lines()

    @TestFactory
    fun `schema excerpts are verbatim runs of lines from their file`(): List<DynamicTest> =
        Guide.blocks(text, "schemata").map { block ->
            DynamicTest.dynamicTest("examples.md:${block.line}") {
                val source = sourceFor(block.line, "From `examples/")
                val file = File(Guide.root, source).readText()
                assertTrue(
                    contiguous(file, block.body),
                    "block at line ${block.line} is not a verbatim run of $source",
                )
            }
        }

    @TestFactory
    fun `quoted warnings exist in the warnings file`(): List<DynamicTest> =
        Guide.blocks(text, "text")
            .filter { intro(it.line).startsWith("Warnings from `examples/") }
            .map { block ->
                DynamicTest.dynamicTest("examples.md:${block.line}") {
                    val source = sourceFor(block.line, "Warnings from `examples/")
                    val warnings = File(Guide.root, source).readLines()
                    block.body
                        .lines()
                        .filter { it.isNotBlank() }
                        .forEach { quoted ->
                            assertTrue(
                                quoted in warnings,
                                "line '$quoted' at ${block.line} is not in $source",
                            )
                        }
                }
            }

    /** The line immediately before the fence (fence is at block.line - 1, 1-based). */
    private fun intro(blockLine: Int): String = lines.getOrNull(blockLine - 3)?.trim() ?: ""

    /** The path between the first pair of backticks on the intro line. */
    private fun sourceFor(blockLine: Int, prefix: String): String {
        val intro = intro(blockLine)
        assertTrue(
            intro.startsWith(prefix),
            "block at $blockLine needs an intro line starting with $prefix",
        )
        return Regex("`([^`]+)`").find(intro)!!.groupValues[1]
    }

    private fun contiguous(file: String, excerpt: String): Boolean {
        val f = file.lines()
        val e = excerpt.trimEnd().lines()
        return (0..f.size - e.size).any { i -> f.subList(i, i + e.size) == e }
    }
}
