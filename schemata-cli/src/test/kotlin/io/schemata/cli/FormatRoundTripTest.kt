package io.schemata.cli

import io.schemata.lang.format.FormatResult
import io.schemata.lang.format.Formatter
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/** Formatting never changes what a schema compiles to, and formatting twice changes nothing. */
class FormatRoundTripTest {
    private val roots = listOf(File("src/test/resources/corpus"), File("../examples"))

    private fun cases(): List<File> =
        roots.flatMap { r -> r.listFiles { f -> f.isDirectory }!!.toList() }.sortedBy { it.path }

    private fun sources(dir: File) =
        dir.listFiles { f -> f.extension == "schemata" }!!
            .sortedBy { it.name }
            .map { SourceInput(it.name, it.readText()) }

    private fun formatted(s: SourceInput): SourceInput {
        val r = Formatter.format(s.content, s.path)
        assertTrue(r is FormatResult.Formatted, "${s.path}: $r")
        return SourceInput(s.path, (r as FormatResult.Formatted).text)
    }

    @TestFactory
    fun `formatting is idempotent`(): List<DynamicTest> =
        cases().map { dir ->
            DynamicTest.dynamicTest(dir.name) {
                sources(dir).forEach { s ->
                    val once = formatted(s)
                    assertEquals(once.content, formatted(once).content, s.path)
                }
            }
        }

    @TestFactory
    fun `formatting does not change compiled output or diagnostics`(): List<DynamicTest> =
        cases().map { dir ->
            DynamicTest.dynamicTest(dir.name) {
                val before = Pipeline.compile(sources(dir), Pipeline.targets)
                val after = Pipeline.compile(sources(dir).map(::formatted), Pipeline.targets)
                assertEquals(
                    before.diagnostics.map { "${it.code.id} ${it.message}" },
                    after.diagnostics.map { "${it.code.id} ${it.message}" },
                    dir.name,
                )
                assertEquals(
                    before.files.map { it.file.path to it.file.content },
                    after.files.map { it.file.path to it.file.content },
                    dir.name,
                )
            }
        }
}
