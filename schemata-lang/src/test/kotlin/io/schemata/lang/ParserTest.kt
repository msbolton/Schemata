package io.schemata.lang

import io.schemata.lang.ast.RecordDecl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParserTest {
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
    fun `builds an AST for the fixture and records the path`() {
        val result = Parser.parse(fixture, "src/orders.schemata")
        assertEquals(emptyList(), result.diagnostics)
        val file = assertNotNull(result.file)
        assertEquals("src/orders.schemata", file.path)
        assertEquals("shop.orders", file.namespace.name)
        val record = file.declarations.single() as RecordDecl
        assertEquals(listOf("id", "email", "name", "age"), record.fields.map { it.name })
        assertEquals(listOf(false, true, false, false), record.fields.map { it.type.nullable })
    }

    @Test
    fun `every AST span names the file`() {
        val file = Parser.parse(fixture, "src/orders.schemata").file!!
        val record = file.declarations.single() as RecordDecl
        assertEquals(Span("src/orders.schemata", 3, 1, 8, 1), record.span)
        assertEquals(Span("src/orders.schemata", 5, 10, 5, 16), record.fields[1].type.span)
        assertEquals("src/orders.schemata", file.namespace.span.file)
    }

    @Test
    fun `returns no file when there are syntax errors`() {
        val result = Parser.parse("namespace a\nrecord User { id uuid }", "bad.schemata")
        assertNull(result.file)
        assertTrue(result.diagnostics.hasErrors)
        assertEquals("bad.schemata", result.diagnostics.single().span.file)
    }
}
