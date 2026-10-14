package io.schemata.cli.guide

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * Every fence of every wiki page opens and closes on a line of its own: the lines that start with
 * three backticks are even in number, and no line holds three backticks after other text, which is
 * how a reflowed block loses its closing fence and swallows the page that follows.
 */
class GuideFencesTest {
    @TestFactory
    fun `fences open and close on lines of their own`(): List<DynamicTest> =
        Guide.pages.map { page ->
            DynamicTest.dynamicTest(page.name) {
                val lines = page.readLines()
                val stray =
                    lines.withIndex().filter { (_, l) ->
                        !l.trimStart().startsWith("```") && l.contains("```")
                    }
                assertTrue(
                    stray.isEmpty(),
                    "${page.name}: fence after other text on line ${stray.map { it.index + 1 }}",
                )
                val fences = lines.count { it.trimStart().startsWith("```") }
                assertEquals(0, fences % 2, "${page.name} has $fences fence lines, an odd number")
            }
        }
}
