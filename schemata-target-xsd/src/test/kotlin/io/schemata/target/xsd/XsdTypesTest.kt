package io.schemata.target.xsd

import io.schemata.core.ir.Builtin
import io.schemata.core.ir.Refinements
import io.schemata.core.ir.StringValue
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class XsdTypesTest {
    @Test
    fun `builtins map to xs names`() {
        assertEquals("xs:boolean", XsdTypes.xsName(Builtin.BOOL))
        assertEquals("xs:int", XsdTypes.xsName(Builtin.INT32))
        assertEquals("xs:long", XsdTypes.xsName(Builtin.INT64))
        assertEquals("xs:float", XsdTypes.xsName(Builtin.FLOAT32))
        assertEquals("xs:double", XsdTypes.xsName(Builtin.FLOAT64))
        assertEquals("xs:decimal", XsdTypes.xsName(Builtin.DECIMAL))
        assertEquals("xs:string", XsdTypes.xsName(Builtin.STRING))
        assertEquals("xs:base64Binary", XsdTypes.xsName(Builtin.BYTES))
        assertEquals("xs:string", XsdTypes.xsName(Builtin.UUID))
        assertEquals("xs:date", XsdTypes.xsName(Builtin.DATE))
        assertEquals("xs:time", XsdTypes.xsName(Builtin.TIME))
        assertEquals("xs:dateTime", XsdTypes.xsName(Builtin.INSTANT))
        assertEquals("xs:duration", XsdTypes.xsName(Builtin.DURATION))
    }

    @Test
    fun `string refinements become length and pattern facets with anchors stripped`() {
        val facets =
            XsdTypes.facets(
                Builtin.STRING,
                Refinements(min = BigDecimal(2), max = BigDecimal(8), pattern = "^[a-z]+$"),
            )
        assertEquals(
            listOf(
                XsdFacet("minLength", "2"),
                XsdFacet("maxLength", "8"),
                XsdFacet("pattern", "[a-z]+"),
            ),
            facets,
        )
    }

    @Test
    fun `numeric refinements become inclusive bounds and decimal digits come first`() {
        assertEquals(
            listOf(
                XsdFacet("totalDigits", "10"),
                XsdFacet("fractionDigits", "2"),
                XsdFacet("minInclusive", "0"),
            ),
            XsdTypes.facets(
                Builtin.DECIMAL,
                Refinements(min = BigDecimal.ZERO, precision = 10, scale = 2),
            ),
        )
        assertEquals(
            listOf(XsdFacet("maxInclusive", "150")),
            XsdTypes.facets(Builtin.INT32, Refinements(max = BigDecimal(150))),
        )
    }

    @Test
    fun `uuid always carries its pattern`() {
        assertEquals(
            listOf(XsdFacet("pattern", XsdTypes.UUID_PATTERN)),
            XsdTypes.facets(Builtin.UUID, Refinements()),
        )
    }

    @Test
    fun `a pattern anchored at both ends is emitted bare`() {
        assertEquals(XsdTypes.Pattern("[^@]+@[^@]+", null), XsdTypes.pattern("^[^@]+@[^@]+$"))
    }

    @Test
    fun `an unanchored pattern matches anywhere in the string`() {
        assertEquals(".*(abc).*", XsdTypes.pattern("abc").value)
    }

    @Test
    fun `a pattern anchored only at the start may be followed by anything`() {
        assertEquals("(abc).*", XsdTypes.pattern("^abc").value)
    }

    @Test
    fun `a pattern anchored only at the end may be preceded by anything`() {
        assertEquals(".*(abc)", XsdTypes.pattern("abc$").value)
    }

    @Test
    fun `an unanchored alternation is parenthesised as a whole`() {
        assertEquals(".*(a|b).*", XsdTypes.pattern("a|b").value)
    }

    @Test
    fun `an escaped dollar at the end is a literal and leaves the end unanchored`() {
        assertEquals("(a$).*", XsdTypes.pattern("^a\\$").value)
        assertEquals("a\\\\", XsdTypes.pattern("^a\\\\$").value)
    }

    @Test
    fun `an escaped dollar anywhere is printed bare`() {
        assertEquals("a\$b[$]", XsdTypes.pattern("^a\\\$b[\\$]$").value)
    }

    @Test
    fun `an unescaped anchor inside the pattern is reported`() {
        assertEquals(XsdTypes.Pattern(null, "^"), XsdTypes.pattern("a|^b"))
        assertEquals(XsdTypes.Pattern(null, "$"), XsdTypes.pattern("a$|b"))
        assertEquals(XsdTypes.Pattern(null, "^"), XsdTypes.pattern("^a|^b$"))
    }

    @Test
    fun `a caret or dollar inside a character class stays literal`() {
        assertEquals(".*(a[b^$]).*", XsdTypes.pattern("a[b^$]").value)
    }

    @Test
    fun `constructs xsd regexes lack are named exactly`() {
        val cases =
            listOf(
                "^(?=a).*$" to "(?",
                "(?!a)b" to "(?",
                "(?<=a)b" to "(?",
                "(?<!a)b" to "(?",
                "(?:ab)+" to "(?",
                "\\bfoo" to "\\b",
                "\\Bfoo" to "\\B",
                "\\Afoo" to "\\A",
                "foo\\z" to "\\z",
                "foo\\Z" to "\\Z",
                "\\Gfoo" to "\\G",
                "(a)\\1" to "\\1",
                "(a)\\9" to "\\9",
                "a*+" to "*+",
                "a++" to "++",
                "a?+" to "?+",
                "a{2}+" to "{2}+",
                "a*?" to "*?",
                "a+?" to "+?",
                "a??" to "??",
                "a{2,3}?" to "{2,3}?",
                "\\u0041" to "\\u0041",
                "\\x41" to "\\x41",
                "\\f" to "\\f",
                "\\e" to "\\e",
                "\\a" to "\\a",
                "\\0" to "\\0",
                "[a-z&&[^aeiou]]" to "&&",
                "\\p{Alpha}" to "\\p{Alpha}",
                "\\p{IsLatin}" to "\\p{IsLatin}",
                "\\P{IsAlphabetic}" to "\\P{IsAlphabetic}",
            )
        cases.forEach { (pattern, construct) ->
            assertEquals(XsdTypes.Pattern(null, construct), XsdTypes.pattern(pattern), pattern)
        }
    }

    @Test
    fun `constructs xsd regexes share are kept`() {
        listOf(
                "\\\\b",
                "[(?]",
                "\\p{Lu}+",
                "\\P{IsBasicLatin}",
                "\\p{IsLatin-1Supplement}",
                "[a-z-[aeiou]]",
                "[\\p{L}\\-]+",
                "\\d{3}-\\d{4}",
                "a{2,}b{1,3}",
                "(ab)?[^\\]]*",
                "\\n\\r\\t\\|\\.\\-\\^\\?\\*\\+\\{\\}\\(\\)\\[\\]",
                "\\s\\S\\i\\I\\c\\C\\d\\D\\w\\W",
            )
            .forEach { assertNull(XsdTypes.pattern(it).unsupported, it) }
    }

    @Test
    fun `default text is the literal without quoting`() {
        assertEquals("a \"b\"", XsdTypes.text(StringValue("a \"b\"")))
    }
}
