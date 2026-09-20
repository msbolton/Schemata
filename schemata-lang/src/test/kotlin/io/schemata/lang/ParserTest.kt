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
    fun `builds an AST for the fixture`() {
        val result = Parser.parse(fixture)
        assertEquals(emptyList(), result.diagnostics)
        val file = assertNotNull(result.file)
        assertEquals("shop.orders", file.namespace.name)
        val record = file.declarations.single() as RecordDecl
        assertEquals("User", record.name)
        assertEquals(listOf("id", "email", "name", "age"), record.fields.map { it.name })
        assertEquals(
            listOf("uuid", "string", "string", "int32"),
            record.fields.map { it.type.name },
        )
        assertEquals(listOf(false, true, false, false), record.fields.map { it.type.nullable })
    }

    @Test
    fun `AST nodes carry source spans`() {
        val file = Parser.parse(fixture).file!!
        val record = file.declarations.single() as RecordDecl
        assertEquals(Span(3, 1, 8, 1), record.span)
        val email = record.fields[1]
        assertEquals(5, email.span.startLine)
        assertEquals(Span(5, 10, 5, 16), email.type.span)
    }

    @Test
    fun `returns no file when there are syntax errors`() {
        val result = Parser.parse("namespace a\nrecord User { id uuid }")
        assertNull(result.file)
        assertTrue(result.diagnostics.hasErrors)
    }
}
