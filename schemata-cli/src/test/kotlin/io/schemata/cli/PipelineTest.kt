package io.schemata.cli

import io.schemata.lang.Category
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PipelineTest {
    private val fixture =
        """
        namespace shop.orders

        record User {
          id:    uuid
          email: string?
          name:  string
          age:   int32
        }
        """
            .trimIndent()

    @Test
    fun `produces one file per target and collects lossy diagnostics`() {
        val result = Pipeline.compile(fixture, Pipeline.targets)
        assertFalse(result.hasErrors)
        assertEquals(
            listOf("proto" to "shop/orders.proto", "sql" to "orders.sql"),
            result.files.map { it.target to it.file.path },
        )
        assertEquals(1, result.diagnostics.count { it.category == Category.LOSSY })
    }

    @Test
    fun `stops at the first failing stage`() {
        val syntax = Pipeline.compile("namespace a\nrecord R { x uuid }", Pipeline.targets)
        assertTrue(syntax.hasErrors)
        assertEquals(emptyList(), syntax.files)

        val semantic = Pipeline.compile("namespace a\nrecord R { x: money }", Pipeline.targets)
        assertTrue(semantic.hasErrors)
        assertEquals(emptyList(), semantic.files)
    }

    @Test
    fun `looks targets up by name`() {
        assertEquals("proto", Pipeline.targetNamed("proto")?.name)
        assertEquals(null, Pipeline.targetNamed("avro"))
    }
}
