package io.schemata.importer

import io.schemata.importer.NoteText.OperationNote
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NoteTextTest {
    @Test
    fun `a type note with a default containing the separator`() {
        val p = NoteText.parse("""string { match "^a; b$" }; default = "x; y"""")!!
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
            NoteText.parse("string?[]?"),
        )
    }

    @Test
    fun `a decimal note with bounds`() {
        assertEquals(
            UnitType.Scalar("decimal", listOf("p" to "5", "s" to "1", "min" to "1")),
            NoteText.parse("decimal(5, 1) { min 1 }; default = 2")!!.type,
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
        val p = NoteText.parse("""string { match "^\"[A-Z]\d$" }; default = "a\nb"""")!!
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
    fun `a 1x note does not read`() {
        assertNull(NoteText.parse("string(max = 5)"))
        assertNull(NoteText.parse("list<string>(min = 1)"))
    }

    @Test
    fun `list options split between the list and its element`() {
        assertEquals(
            NoteText.Parsed(
                UnitType.ListOf(
                    UnitType.Scalar("string", listOf("max" to "3", "pattern" to "\"^[a-z]+$\"")),
                    true,
                    listOf("min" to "1", "max" to "4"),
                ),
                false,
                null,
            ),
            NoteText.parse("string?[] { minItems 1, max 3, maxItems 4, match \"^[a-z]+$\" }"),
        )
        assertEquals(
            UnitType.ListOf(
                UnitType.ListOf(UnitType.Scalar("int32", emptyList()), false, listOf("max" to "2")),
                true,
                listOf("min" to "1"),
            ),
            NoteText.parse("list<int32[]? { maxItems 2 }> { minItems 1 }")!!.type,
        )
    }

    @Test
    fun `map options sit on the key the value and the map`() {
        assertEquals(
            UnitType.MapOf(
                UnitType.Scalar("string", listOf("max" to "5")),
                UnitType.Scalar("int32", listOf("min" to "0")),
                true,
                listOf("max" to "9"),
            ),
            NoteText.parse("map<string { max 5 }, int32? { min 0 }> { maxItems 9 }")!!.type,
        )
    }

    @Test
    fun `an option that bounds nothing there does not read`() {
        assertNull(NoteText.parse("string { id }"))
        assertNull(NoteText.parse("string { minItems 1 }"))
        assertNull(NoteText.parse("Order { max 1 }"))
        assertNull(NoteText.parse("map<string, int32> { max 1 }"))
        assertNull(NoteText.parse("string @sql(type: \"text\")"))
    }

    @Test
    fun `the separator inside a pattern or a default string is not the separator`() {
        val p =
            NoteText.parse("""string { match "x; default = y" }; default = "a; default = b"""")!!
        assertEquals(UnitType.Scalar("string", listOf("pattern" to "\"x; default = y\"")), p.type)
        assertEquals("\"a; default = b\"", p.default)
        val alone = NoteText.parse("""string { match "x\"; default = y" }""")!!
        assertEquals(
            UnitType.Scalar("string", listOf("pattern" to "\"x\\\"; default = y\"")),
            alone.type,
        )
        assertNull(alone.default)
    }

    @Test
    fun `operation notes read ordinals and bindings`() {
        assertEquals(OperationNote(4, "get \"/x\""), NoteText.parseOperationNote("#4; get \"/x\""))
        assertEquals(
            OperationNote(null, "post \"/orders\""),
            NoteText.parseOperationNote("post \"/orders\""),
        )
        assertEquals(OperationNote(7, null), NoteText.parseOperationNote("#7"))
        assertNull(NoteText.parseOperationNote("fetch \"/x\""))
        assertNull(NoteText.parseOperationNote("get /x"))
        assertNull(NoteText.parseOperationNote("#x; get \"/x\""))
    }

    @Test
    fun `an operation note spells its binding as the formatter does`() {
        assertEquals(OperationNote(null, "get \"/x\""), NoteText.parseOperationNote("get   \"/x\""))
    }

    @Test
    fun `an operation note holds one binding and nothing else`() {
        assertNull(NoteText.parseOperationNote("get \"/x\" } model R {"))
        assertNull(NoteText.parseOperationNote("get \"/x\" reserved #1"))
        assertNull(NoteText.parseOperationNote("get \"/x\" other()"))
        assertNull(NoteText.parseOperationNote("#0"))
        assertNull(NoteText.parseOperationNote("#4;"))
    }

    @Test
    fun `a reserved note reads ordinals ranges and names`() {
        assertEquals(
            listOf(
                UnitReserved.Ordinals(6, 6),
                UnitReserved.Ordinals(8, 9),
                UnitReserved.Name("archive"),
            ),
            NoteText.parseReservedNote("reserved #6, #8..#9, \"archive\""),
        )
        assertNull(NoteText.parseReservedNote("reserved"))
        assertNull(NoteText.parseReservedNote("reserved #6 get()"))
        assertNull(NoteText.parseReservedNote("get()"))
    }
}
