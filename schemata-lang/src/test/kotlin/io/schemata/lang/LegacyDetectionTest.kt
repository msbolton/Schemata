package io.schemata.lang

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LegacyDetectionTest {
    @Test
    fun `a 1x file is one SCH0008 and nothing else`() {
        val r = Parser.parse("namespace s\nrecord R { #1 x: int32 }", "t.schemata")
        assertNull(r.file)
        assertEquals(listOf("SCH0008"), r.diagnostics.map { it.code.id })
        assertEquals("run schemata upgrade on this file", r.diagnostics[0].help)
        assertEquals(1, r.diagnostics[0].span.startLine)
    }

    @Test
    fun `a 1x file behind a doc comment is one SCH0008`() {
        val r =
            Parser.parse(
                "/// doc\n@sql(schema = \"x\")\nnamespace s\nrecord R { #1 x: int32 }",
                "t.schemata",
            )
        assertEquals(listOf("SCH0008"), r.diagnostics.map { it.code.id })
        assertEquals(3, r.diagnostics[0].span.startLine)
    }

    @Test
    fun `a 2 file whose model is named namespace is not legacy`() {
        val r = Parser.parse("schema s\nmodel namespace_log { a int32 }", "t.schemata")
        assertNotNull(r.file)
    }

    @Test
    fun `a 1x file behind a bare annotation and a comment is one SCH0008 for the formatter too`() {
        val r = Parser.parseForFormat("// c\n@deprecated\nnamespace s\nrecord R {}", "t.schemata")
        assertNull(r.file)
        assertEquals(listOf("SCH0008"), r.diagnostics.map { it.code.id })
        assertEquals(3, r.diagnostics[0].span.startLine)
    }
}
