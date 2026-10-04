package io.schemata.importer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NoteTextTest {
    @Test
    fun `a type note with a default containing the separator`() {
        val p = NoteText.parse("""string(pattern = "^a; b$"); default = "x; y"""")!!
        assertEquals(UnitType.Scalar("string", listOf("pattern" to "\"^a; b$\"")), p.type)
        assertEquals("\"x; y\"", p.default)
    }

    @Test
    fun `a nullable list note`() {
        assertEquals(
            NoteText.Parsed(
                UnitType.ListOf(UnitType.Scalar("string", emptyList()), true, emptyList()),
                true,
                null,
            ),
            NoteText.parse("list<string?>?"),
        )
    }

    @Test
    fun `a decimal note with bounds`() {
        assertEquals(
            UnitType.Scalar("decimal", listOf("p" to "5", "s" to "1", "min" to "1")),
            NoteText.parse("decimal(5, 1, min = 1); default = 2")!!.type,
        )
    }

    @Test
    fun `a default alone`() {
        assertEquals(
            NoteText.Parsed(null, false, "STATUS_PENDING"),
            NoteText.parse("default = STATUS_PENDING"),
        )
    }

    @Test
    fun `a pattern keeps its backslashes and a string default its escapes`() {
        val p = NoteText.parse("""string(pattern = "^\"[A-Z]\d$"); default = "a\nb"""")!!
        assertEquals(UnitType.Scalar("string", listOf("pattern" to """"^\"[A-Z]\d$"""")), p.type)
        assertEquals(""""a\nb"""", p.default)
    }

    @Test
    fun `a map note and a reference note`() {
        assertEquals(
            UnitType.MapOf(
                UnitType.Scalar("string", emptyList()),
                UnitType.Scalar("decimal", listOf("p" to "19", "s" to "4")),
                false,
                emptyList(),
            ),
            NoteText.parse("map<string, decimal(19, 4)>")!!.type,
        )
        assertEquals(
            NoteText.Parsed(UnitType.Ref("Order.Line"), true, null),
            NoteText.parse("Order.Line?"),
        )
    }

    @Test
    fun `garbage is null`() {
        assertNull(NoteText.parse("not a type ("))
    }

    @Test
    fun `the separator inside a pattern or a default string is not the separator`() {
        val p =
            NoteText.parse("""string(pattern = "x; default = y"); default = "a; default = b"""")!!
        assertEquals(UnitType.Scalar("string", listOf("pattern" to "\"x; default = y\"")), p.type)
        assertEquals("\"a; default = b\"", p.default)
        val alone = NoteText.parse("""string(pattern = "x\"; default = y")""")!!
        assertEquals(
            UnitType.Scalar("string", listOf("pattern" to "\"x\\\"; default = y\"")),
            alone.type,
        )
        assertNull(alone.default)
    }
}
