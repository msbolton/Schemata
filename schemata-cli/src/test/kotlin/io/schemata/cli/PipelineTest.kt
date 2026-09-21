package io.schemata.cli

import io.schemata.core.AnalysisOptions
import io.schemata.lang.Category
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PipelineTest {
    private val orders =
        SourceInput(
            "src/orders.schemata",
            """
            namespace shop.orders

            record User {
              id:    uuid
              email: string?
              name:  string
              age:   int32
            }

            record Session {
              token:   string
              user_id: uuid
              active:  bool
            }
            """
                .trimIndent(),
        )

    private val customers =
        SourceInput(
            "src/customers.schemata",
            """
            namespace shop.customers

            record Customer {
              id:   uuid
              name: string
            }
            """
                .trimIndent(),
        )

    @Test
    fun `produces one file per namespace per target and collects lossy diagnostics`() {
        val result = Pipeline.compile(listOf(orders, customers), Pipeline.targets)
        assertFalse(result.hasErrors)
        assertEquals(
            listOf(
                "proto" to "shop/customers.proto",
                "proto" to "shop/orders.proto",
                "sql" to "shop/customers.sql",
                "sql" to "shop/orders.sql",
            ),
            result.files.map { it.target to it.file.path },
        )
        val lossy = result.diagnostics.filter { it.category == Category.LOSSY }
        assertEquals(3, lossy.size)
        assertTrue(lossy.all { it.span.file.endsWith(".schemata") })
    }

    @Test
    fun `stops at the first failing stage and reports every file's syntax errors`() {
        val bad1 = SourceInput("a.schemata", "namespace a\nrecord R { x uuid }")
        val bad2 = SourceInput("b.schemata", "namespace b\nrecord S { y uuid }")
        val syntax = Pipeline.compile(listOf(bad1, bad2), Pipeline.targets)
        assertTrue(syntax.hasErrors)
        assertEquals(emptyList(), syntax.files)
        assertEquals(listOf("a.schemata", "b.schemata"), syntax.diagnostics.map { it.span.file })

        val semantic =
            Pipeline.compile(
                listOf(SourceInput("c.schemata", "namespace c\nrecord R { x: money }")),
                Pipeline.targets,
            )
        assertTrue(semantic.hasErrors)
        assertEquals(emptyList(), semantic.files)
    }

    @Test
    fun `strict mode is passed to the analyzer`() {
        val src = SourceInput("s.schemata", "namespace s\nrecord R { x: bool }")
        val lax = Pipeline.compile(listOf(src), Pipeline.targets)
        assertFalse(lax.hasErrors)
        val strict =
            Pipeline.compile(listOf(src), Pipeline.targets, AnalysisOptions(strictOrdinals = true))
        assertTrue(strict.hasErrors)
        assertEquals("SCH1014", strict.diagnostics.single().code.id)
    }

    @Test
    fun `looks targets up by name`() {
        assertEquals("proto", Pipeline.targetNamed("proto")?.name)
        assertEquals(null, Pipeline.targetNamed("avro"))
    }
}
