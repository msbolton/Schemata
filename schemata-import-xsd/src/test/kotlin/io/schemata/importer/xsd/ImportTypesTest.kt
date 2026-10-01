package io.schemata.importer.xsd

import io.schemata.lang.format.FormatResult
import io.schemata.lang.format.Formatter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImportTypesTest {
    private fun s(b: String, vararg r: Pair<String, String>) = UnitType.Scalar(b, r.toList())

    @Test
    fun `builtins map per the table`() {
        assertEquals(s("bool"), ImportTypes.builtin("boolean")!!.type)
        assertEquals(s("int32"), ImportTypes.builtin("int")!!.type)
        assertEquals(s("int32"), ImportTypes.builtin("unsignedShort")!!.type)
        assertEquals(s("int64"), ImportTypes.builtin("long")!!.type)
        assertEquals(s("int64", "min" to "0"), ImportTypes.builtin("nonNegativeInteger")!!.type)
        assertEquals(s("int64", "min" to "1"), ImportTypes.builtin("positiveInteger")!!.type)
        assertEquals(s("int64", "max" to "-1"), ImportTypes.builtin("negativeInteger")!!.type)
        assertEquals(s("float32"), ImportTypes.builtin("float")!!.type)
        assertEquals(s("float64"), ImportTypes.builtin("double")!!.type)
        assertEquals(s("string"), ImportTypes.builtin("anyURI")!!.type)
        assertEquals(s("bytes"), ImportTypes.builtin("base64Binary")!!.type)
        assertEquals(s("instant"), ImportTypes.builtin("dateTime")!!.type)
        assertEquals(s("duration"), ImportTypes.builtin("duration")!!.type)
        assertEquals(emptyList(), ImportTypes.builtin("long")!!.notes)
        assertEquals(
            listOf("xs:hexBinary imported as bytes"),
            ImportTypes.builtin("hexBinary")!!.notes,
        )
        assertEquals(listOf("xs:gYear imported as string"), ImportTypes.builtin("gYear")!!.notes)
        assertNull(ImportTypes.builtin("CustomerType"))
    }

    @Test
    fun `decimal without both digit facets is approximated`() {
        val (t, notes) = ImportTypes.facets(s("decimal"), emptyList())
        assertEquals(s("decimal", "p" to "38", "s" to "9"), t)
        assertEquals(
            listOf(
                Note(
                    "SCH2403",
                    "decimal without totalDigits and fractionDigits imported as decimal(38, 9)",
                    0,
                )
            ),
            notes,
        )
        val (t2, notes2) =
            ImportTypes.facets(
                s("decimal"),
                listOf(
                    XFacet("totalDigits", "19", null, 3),
                    XFacet("fractionDigits", "4", null, 4),
                    XFacet("minInclusive", "0", null, 5),
                ),
            )
        assertEquals(s("decimal", "p" to "19", "s" to "4", "min" to "0"), t2)
        assertEquals(emptyList(), notes2)
    }

    @Test
    fun `lengths bounds patterns and exclusive bounds`() {
        val (str, n1) =
            ImportTypes.facets(
                s("string"),
                listOf(
                    XFacet("minLength", "2", null, 1),
                    XFacet("maxLength", "200", null, 2),
                    XFacet("pattern", "[^@]+@[^@]+", null, 3),
                ),
            )
        assertEquals(
            s("string", "min" to "2", "max" to "200", "pattern" to "\"^[^@]+@[^@]+$\""),
            str,
        )
        assertEquals(emptyList(), n1)
        val (len, _) = ImportTypes.facets(s("bytes"), listOf(XFacet("length", "16", null, 1)))
        assertEquals(s("bytes", "min" to "16", "max" to "16"), len)
        val (int, n2) =
            ImportTypes.facets(
                s("int32"),
                listOf(XFacet("minExclusive", "0", null, 1), XFacet("maxExclusive", "10", null, 2)),
            )
        assertEquals(s("int32", "min" to "1", "max" to "9"), int)
        assertEquals(emptyList(), n2)
        val (dbl, n3) =
            ImportTypes.facets(s("float64"), listOf(XFacet("maxExclusive", "180", null, 7)))
        assertEquals(s("float64"), dbl)
        assertEquals(listOf(Note("SCH2404", "facet maxExclusive dropped", 7)), n3)
        val (ws, n4) =
            ImportTypes.facets(s("string"), listOf(XFacet("whiteSpace", "collapse", null, 9)))
        assertEquals(s("string"), ws)
        assertEquals(listOf(Note("SCH2404", "facet whiteSpace dropped", 9)), n4)
    }

    @Test
    fun `uuid pattern and un-anchoring`() {
        assertTrue(
            ImportTypes.isUuidPattern(
                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
            )
        )
        val (u, _) =
            ImportTypes.facets(
                s("string"),
                listOf(
                    XFacet(
                        "pattern",
                        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",
                        null,
                        1,
                    )
                ),
            )
        assertEquals(s("uuid"), u)
        assertEquals("[a-z]+", ImportTypes.unanchor(".*([a-z]+).*"))
        assertEquals("^abc", ImportTypes.unanchor("(abc).*"))
        assertEquals("abc$", ImportTypes.unanchor(".*(abc)"))
        assertEquals("^abc$", ImportTypes.unanchor("abc"))
        assertEquals("^a\\\$b$", ImportTypes.unanchor("a\$b"))
    }

    @Test
    fun `default literals`() {
        assertEquals("\"a\\\"b\"", ImportTypes.defaultLiteral("string", "a\"b"))
        assertEquals("\"a\\\\b\"", ImportTypes.defaultLiteral("string", "a\\b"))
        assertEquals("\"a\\nb\\tc\\r\"", ImportTypes.defaultLiteral("string", "a\nb\tc\r"))
        assertEquals("3", ImportTypes.defaultLiteral("int32", "3"))
        assertEquals("1.5", ImportTypes.defaultLiteral("float64", "1.5"))
        assertEquals("true", ImportTypes.defaultLiteral("bool", "true"))
    }

    @Test
    fun `numeric defaults print as plain decimals or not at all`() {
        assertEquals("0.5", ImportTypes.defaultLiteral("float64", ".5"))
        assertEquals("100000", ImportTypes.defaultLiteral("float32", "1e5"))
        assertEquals("-2", ImportTypes.defaultLiteral("int64", "-2"))
        assertEquals("7", ImportTypes.defaultLiteral("int32", "+7"))
        assertEquals("12.50", ImportTypes.defaultLiteral("decimal", "12.50"))
        assertNull(ImportTypes.defaultLiteral("float64", "INF"))
        assertNull(ImportTypes.defaultLiteral("float32", "NaN"))
        assertNull(ImportTypes.defaultLiteral("int32", "ten"))
    }

    @Test
    fun `boolean defaults accept the four xsd spellings`() {
        assertEquals("true", ImportTypes.defaultLiteral("bool", "1"))
        assertEquals("false", ImportTypes.defaultLiteral("bool", "0"))
        assertEquals("false", ImportTypes.defaultLiteral("bool", "false"))
        assertNull(ImportTypes.defaultLiteral("bool", "yes"))
    }

    @Test
    fun `types with no literal form have no default`() {
        listOf("uuid", "date", "time", "instant", "duration", "bytes").forEach {
            assertNull(ImportTypes.defaultLiteral(it, "2024-01-01"), it)
        }
    }

    @Test
    fun `pattern literals escape backslashes and quotes`() {
        val (backslash, _) =
            ImportTypes.facets(s("string"), listOf(XFacet("pattern", "a\\\\b", null, 1)))
        assertEquals(s("string", "pattern" to "\"^a\\\\\\\\b$\""), backslash)
        val (quote, _) =
            ImportTypes.facets(s("string"), listOf(XFacet("pattern", "[^\"]*", null, 1)))
        assertEquals(s("string", "pattern" to "\"^[^\\\"]*$\""), quote)
    }

    @Test
    fun `an escaped pattern literal formats as Schemata source`() {
        val (email, _) =
            ImportTypes.facets(s("string"), listOf(XFacet("pattern", "[^\"]+@[^\"]+", null, 1)))
        val literal = email.refinements.single().second
        val source = "namespace s\n\nalias Email = string(pattern = $literal)\n"
        val formatted = Formatter.format(source, "s.schemata") as FormatResult.Formatted
        assertTrue(formatted.text.contains("\"^[^\\\"]+@[^\\\"]+$\""), formatted.text)
    }
}
