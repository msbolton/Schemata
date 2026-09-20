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
    fun `the v1 subset reports nothing`() {
        assertEquals(
            emptyList(),
            messages("namespace a\n/// doc\nrecord R {\n /// doc\n x: bool\n y: string? }"),
        )
    }

    @Test
    fun `reports each construct once at its span`() {
        val source =
            """
            @sql(schema = "s")
            namespace a
            import b
            alias A = string
            enum E { x }
            union U = R | E
            record R {
              @deprecated("d")
              #1 f: string(max = 5) = "x"
              g: list<string>
              h: b.Thing
              reserved #2
              record N { z: bool }
            }
            """
                .trimIndent()
        assertEquals(
            listOf(
                "1: annotations are not supported yet (SCH-20)",
                "3: imports are not supported yet (SCH-19)",
                "4: aliases are not supported yet (SCH-20)",
                "5: enums are not supported yet (SCH-20)",
                "6: unions are not supported yet (SCH-20)",
                "8: annotations are not supported yet (SCH-20)",
                "9: explicit ordinals are not supported yet (SCH-22)",
                "9: type refinements are not supported yet (SCH-20)",
                "9: field defaults are not supported yet (SCH-20)",
                "10: generic types are not supported yet (SCH-20)",
                "11: qualified type names are not supported yet (SCH-19)",
                "12: reserved statements are not supported yet (SCH-22)",
                "13: nested declarations are not supported yet (SCH-19)",
            ),
            messages(source),
        )
    }

    @Test
    fun `reports every reserved item`() {
        assertEquals(
            listOf(
                "3: reserved statements are not supported yet (SCH-22)",
                "3: reserved statements are not supported yet (SCH-22)",
                "4: reserved statements are not supported yet (SCH-22)",
            ),
            messages("namespace a\nrecord R {\n  reserved #2, \"old\"\n  reserved #5..#7\n}"),
        )
    }

    @Test
    fun `analyzer returns no schema when a construct is unsupported`() {
        val file = Parser.parse("namespace a\nrecord R { #1 x: bool }", "t.schemata").file!!
        val result = Analyzer.analyze(listOf(file))
        assertNull(result.schema)
        assertEquals(
            "explicit ordinals are not supported yet (SCH-22)",
            result.diagnostics.single().message,
        )
    }
}
