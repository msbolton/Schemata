package io.schemata.lang

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    @Test
    fun `a 2 file with a field or an attribute value named namespace is not legacy`() {
        listOf(
                "schema s\nmodel R { namespace string }",
                "schema s @sql(schema: \"namespace\")\nmodel R { a int32 }",
                "/// namespace s\nschema s\nmodel R { a int32 }",
                "// namespace s\nschema s\nmodel R { a int32 }",
                "schema namespace\nmodel R { a int32 }",
            )
            .forEach { text ->
                val r = Parser.parse(text, "t.schemata")
                assertNotNull(r.file, "$text: ${r.diagnostics}")
                assertTrue(r.diagnostics.none { it.code.id == "SCH0008" }, text)
            }
    }

    @Test
    fun `a 2 file with a syntax error is not reported as legacy`() {
        val r = Parser.parse("schema s\nmodel R { a int32 ", "t.schemata")
        assertNull(r.file)
        assertTrue(r.diagnostics.none { it.code.id == "SCH0008" }, "${r.diagnostics}")
    }

    @Test
    fun `an attribute left open before namespace is not mistaken for a 1x file`() {
        val r = Parser.parse("@sql(schema = \"x\"\nnamespace s", "t.schemata")
        assertTrue(r.diagnostics.none { it.code.id == "SCH0008" }, "${r.diagnostics}")
    }
}
