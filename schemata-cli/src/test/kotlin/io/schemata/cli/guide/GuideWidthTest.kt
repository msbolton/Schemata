package io.schemata.cli.guide

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * The README and every guide page wrap their prose at 100 columns. Fenced blocks, table rows, and
 * indented code are exempt because their lines are quoted output or one row of a table, which
 * cannot wrap; `guide/annotations.md` is the exception that holds its fences to the width too,
 * since the page is generated and carries only prose and tables.
 */
class GuideWidthTest {
    private val width = 100

    private val files =
        listOf(File(Guide.root, "README.md")) +
            Guide.dir.listFiles { f -> f.extension == "md" }!!.sortedBy { it.name }

    @TestFactory
    fun `prose lines fit the wrapped width`(): List<DynamicTest> =
        files.map { file ->
            DynamicTest.dynamicTest(file.name) {
                val over = overlong(file.readText(), includeFences = file.name == "annotations.md")
                assertTrue(
                    over.isEmpty(),
                    "${file.name} has lines over $width columns:\n" +
                        over.joinToString("\n") { (n, line) -> "  $n (${line.length}): $line" },
                )
            }
        }

    @Test
    fun `the check flags a long prose line and spares fences, tables, and indented code`() {
        val long = "word ".repeat(21).trim()
        val text =
            listOf(
                    "# Title",
                    long,
                    "",
                    "| a | $long |",
                    "",
                    "    $long",
                    "",
                    "```text",
                    long,
                    "```",
                )
                .joinToString("\n")
        assertEquals(listOf(2), overlong(text, includeFences = false).map { it.first })
        assertEquals(listOf(2, 9), overlong(text, includeFences = true).map { it.first })
    }

    /** The 1-based numbers and text of the prose lines of [text] wider than [width]. */
    private fun overlong(text: String, includeFences: Boolean): List<Pair<Int, String>> {
        val out = mutableListOf<Pair<Int, String>>()
        var inFence = false
        var inIndentedCode = false
        val lines = text.lines()
        lines.forEachIndexed { index, line ->
            if (line.startsWith("```")) {
                inFence = !inFence
                inIndentedCode = false
                return@forEachIndexed
            }
            // A line indented four spaces after a blank line or another such line is indented
            // code; a list item's continuation is indented two, so it is still prose.
            val previous = lines.getOrNull(index - 1).orEmpty()
            inIndentedCode = line.startsWith("    ") && (previous.isBlank() || inIndentedCode)
            val exempt =
                (inFence && !includeFences) || line.startsWith("|") || (!inFence && inIndentedCode)
            if (!exempt && line.length > width) out += (index + 1) to line
        }
        return out
    }
}
