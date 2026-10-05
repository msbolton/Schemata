package io.schemata.cli

import io.schemata.core.ir.QualifiedName
import io.schemata.core.ir.services
import io.schemata.target.jsonschema.JsonSchemaTarget
import io.schemata.target.sql.SqlTarget
import io.schemata.target.xsd.XsdTarget
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The SQL, XSD, and JSON Schema targets read no service: deleting one changes nothing. */
class ServicesIgnoredTest {
    private val with = TestSources.of(File("src/test/resources/corpus/services"))

    @Test
    fun `the three targets produce the same output with and without services`() {
        val without =
            with.map {
                SourceInput(
                    it.path,
                    it.content.replace(
                        Regex("(?s)\n/// Place and read orders\\.\nservice Orders \\{.*?\n\\}\n"),
                        "\n",
                    ),
                )
            }
        assertTrue(without.single().content != with.single().content)
        assertFalse("service Orders" in without.single().content)
        assertEquals(1, Pipeline.analyze(with).schema!!.services().size)
        assertEquals(0, Pipeline.analyze(without).schema!!.services().size)
        listOf(SqlTarget, XsdTarget, JsonSchemaTarget).forEach { target ->
            val a = Pipeline.compile(with, listOf(target))
            val b = Pipeline.compile(without, listOf(target))
            assertFalse(a.hasErrors, a.diagnostics.joinToString("\n") { it.message })
            assertTrue(a.files.isNotEmpty(), target.name)
            assertEquals(
                b.files.associate { it.file.path to it.file.content },
                a.files.associate { it.file.path to it.file.content },
                target.name,
            )
            assertEquals(
                b.diagnostics.map { it.message },
                a.diagnostics.map { it.message },
                target.name,
            )
        }
    }

    @Test
    fun `a service with implicit ordinals is named for the diff report`() {
        val implicit =
            SourceInput(
                "s.schemata",
                "namespace s\nrecord A { #1 x: int32 }\nservice Implicit { a(A)  b(A) }\nservice Explicit { #1 a(A) }",
            )
        val analyzed = Pipeline.analyze(listOf(implicit))
        assertEquals(emptyList(), analyzed.diagnostics.map { it.message })
        assertEquals(setOf(QualifiedName("s", listOf("Implicit"))), analyzed.implicitOrdinals)
    }
}
