package io.schemata.lang

import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun `catalog ids are well-formed and unique within lang`() {
        assertEquals(LangCodes.all.size, LangCodes.all.map { it.id }.toSet().size)
        LangCodes.all.forEach { assertTrue(Regex("SCH\\d{4}").matches(it.id), it.id) }
    }
}
