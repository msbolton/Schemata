package io.schemata.lang

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RecoveryTest {
    @Test
    fun `a syntax error span covers the offending token`() {
        val result = Parser.parse("namespace a\nrecord User { id uuid }", "t.schemata")
        assertEquals(Span("t.schemata", 2, 18, 2, 21), result.diagnostics.single().span)
    }

    @Test
    fun `independent errors in different declarations are all reported`() {
        val source = "namespace a\nrecord A { x uuid }\nrecord B { y: }\nrecord C { z: bool }"
        val result = Parser.parse(source, "t.schemata")
        assertNull(result.file)
        assertEquals(listOf(2, 3), result.diagnostics.map { it.span.startLine })
    }

    @Test
    fun `an error at end of input has a zero-width span at EOF`() {
        val result = Parser.parse("namespace a\nrecord A {", "t.schemata")
        val span = result.diagnostics.single().span
        assertEquals(2, span.startLine)
        assertEquals(span.startColumn, span.endColumn + 1)
    }

    @Test
    fun `a lexer error has a one-character span`() {
        val d = Parser.parse("namespace a\nrecord R { x: bool } %", "t").diagnostics.first()
        assertEquals(Span("t", 2, 22, 2, 22), d.span)
    }

    @Test
    fun `a trailing backslash does not swallow following lines`() {
        val result =
            Parser.parse(
                "namespace a\nrecord R { s: string = \"oops\\\n}\nrecord Q { t: string = \"x\" }",
                "t",
            )
        assertNull(result.file)
        assertEquals(2, result.diagnostics.first().span.startLine)
    }
}
