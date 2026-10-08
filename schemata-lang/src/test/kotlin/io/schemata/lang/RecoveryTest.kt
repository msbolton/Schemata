package io.schemata.lang

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RecoveryTest {
    @Test
    fun `a syntax error span covers the offending token`() {
        val result = Parser.parse("schema a\nmodel User { id uuid true }", "t.schemata")
        assertEquals(Span("t.schemata", 2, 22, 2, 25), result.diagnostics.single().span)
    }

    @Test
    fun `independent errors in different declarations are all reported`() {
        val source = "schema a\nmodel A { x: uuid }\nmodel B { y }\nmodel C { z bool }"
        val result = Parser.parse(source, "t.schemata")
        assertNull(result.file)
        assertEquals(listOf(2, 3), result.diagnostics.map { it.span.startLine })
    }

    @Test
    fun `an error at end of input has a span of width one at EOF`() {
        val result = Parser.parse("schema a\nmodel A {", "t.schemata")
        val span = result.diagnostics.single().span
        assertEquals(2, span.startLine)
        assertEquals(span.startColumn, span.endColumn)
    }

    @Test
    fun `a lexer error has a one-character span`() {
        val d = Parser.parse("schema a\nmodel R { x bool } %", "t").diagnostics.first()
        assertEquals(Span("t", 2, 20, 2, 20), d.span)
    }

    @Test
    fun `a trailing backslash does not swallow following lines`() {
        val result =
            Parser.parse(
                "schema a\nmodel R { s string = \"oops\\\n}\nmodel Q { t string = \"x\" }",
                "t",
            )
        assertNull(result.file)
        assertEquals(2, result.diagnostics.first().span.startLine)
    }
}
