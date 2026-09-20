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
            "numeric literal '99999999999999999999' is out of range",
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
}
