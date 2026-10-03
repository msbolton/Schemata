package io.schemata.lang

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiagnosticTest {
    @Test
    fun `severity and category come from the code`() {
        val d = Diagnostic(LangCodes.SYNTAX, "x", Span("f", 1, 1, 1, 1))
        assertEquals(Severity.ERROR, d.severity)
        assertEquals(Category.SYNTAX, d.category)
        assertEquals("SCH0001", d.code.id)
    }

    @Test
    fun `parser diagnostics carry lang codes`() {
        val syntax = Parser.parse("namespace a\nrecord R { x uuid }", "t").diagnostics.single()
        assertEquals(LangCodes.SYNTAX, syntax.code)
        val reserved = Parser.parse("namespace a\nstream S {}", "t").diagnostics.single()
        assertEquals(LangCodes.RESERVED_KEYWORD, reserved.code)
    }

    @Test
    fun `out-of-range numeric literals are diagnostics`() {
        val big =
            Parser.parse("namespace a\nrecord R { x: int64(max = 99999999999999999999) }", "t")
        assertNull(big.file)
        assertEquals(LangCodes.NUMERIC_LITERAL_RANGE, big.diagnostics.single().code)
        assertEquals(
            "number '99999999999999999999' is out of range",
            big.diagnostics.single().message,
        )
        val ord = Parser.parse("namespace a\nrecord R { #99999999999 x: bool }", "t")
        assertEquals("ordinal '#99999999999' is out of range", ord.diagnostics.single().message)
    }

    @Test
    fun `catalog ids are well-formed and unique within lang`() {
        assertEquals(LangCodes.all.size, LangCodes.all.map { it.id }.toSet().size)
        LangCodes.all.forEach { assertTrue(Regex("SCH\\d{4}").matches(it.id), it.id) }
    }

    @Test
    fun `help defaults to null and is carried when given`() {
        val span = Span("a.schemata", 1, 1, 1, 2)
        assertNull(Diagnostic(LangCodes.SYNTAX, "m", span).help)
        assertEquals("fix it", Diagnostic(LangCodes.SYNTAX, "m", span, help = "fix it").help)
    }

    @Test
    fun `an error at the end of input has a span of width one`() {
        val result = Parser.parse("namespace a\nrecord R {", "a.schemata")
        val error = result.diagnostics.single { it.code.id == "SCH0001" }
        assertEquals(error.span.startLine, error.span.endLine)
        assertEquals(error.span.startColumn, error.span.endColumn)
        assertEquals(11, error.span.startColumn)
    }

    @Test
    fun `an error at the end of a file that ends in a newline sits on the line after it`() {
        val result = Parser.parse("namespace a\nrecord R {\n", "a.schemata")
        val error = result.diagnostics.single { it.code.id == "SCH0001" }
        assertEquals(Span("a.schemata", 3, 1, 3, 1), error.span)
    }
}
