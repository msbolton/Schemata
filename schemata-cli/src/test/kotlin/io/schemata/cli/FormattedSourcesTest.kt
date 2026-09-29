package io.schemata.cli

import io.schemata.lang.format.FormatResult
import io.schemata.lang.format.Formatter
import java.io.File
import kotlin.test.assertEquals
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * The examples and the corpus are kept in the canonical layout; run `schemata fmt` on them if this
 * fails.
 */
class FormattedSourcesTest {
    private val roots = listOf(File("src/test/resources/corpus"), File("../examples"))

    @TestFactory
    fun `checked-in schemas are formatted`(): List<DynamicTest> =
        roots
            .flatMap { r -> r.walkTopDown().filter { it.extension == "schemata" }.toList() }
            .sortedBy { it.path }
            .map { f ->
                DynamicTest.dynamicTest(f.path) {
                    val r = Formatter.format(f.readText(), f.path) as FormatResult.Formatted
                    assertEquals(r.text, f.readText(), "run: schemata fmt ${f.path}")
                }
            }
}
