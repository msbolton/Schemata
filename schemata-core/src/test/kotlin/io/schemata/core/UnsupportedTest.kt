package io.schemata.core

import io.schemata.lang.Parser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UnsupportedTest {
    private fun messages(source: String): List<String> {
        val parsed = Parser.parse(source, "t.schemata")
        assertEquals(emptyList(), parsed.diagnostics)
        return Unsupported.check(parsed.file!!).map { "${it.span.startLine}: ${it.message}" }
    }

    @Test
    fun `structure is no longer reported`() {
        val src =
            "namespace a\nimport b\nenum E { x }\nrecord R {\n  #1 f: list<E> = pending\n  g: b.T\n  reserved #2\n  record N { z: bool }\n}"
        assertEquals(emptyList(), messages(src))
    }

    @Test
    fun `refinements and annotations are still reported`() {
        val src =
            "@sql(schema = \"s\")\nnamespace a\nrecord R {\n  @deprecated(\"d\")\n  f: string(max = 5)\n  g: list<int32(min = 0)>\n}"
        assertEquals(
            listOf(
                "1: annotations are not supported yet (SCH-20)",
                "4: annotations are not supported yet (SCH-20)",
                "5: type refinements are not supported yet (SCH-20)",
                "6: type refinements are not supported yet (SCH-20)",
            ),
            messages(src),
        )
    }

    @Test
    fun `analyzer returns no schema when a construct is unsupported`() {
        val file = Parser.parse("namespace a\nrecord R { x: string(max = 1) }", "t.schemata").file!!
        assertNull(Analyzer.analyze(listOf(file)).schema)
    }
}
