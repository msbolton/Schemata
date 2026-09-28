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
    fun `patterns xsd cannot express are named`() {
        assertEquals("(?", XsdTypes.pattern("^(?=a).*$").unsupported)
        assertEquals("\\b", XsdTypes.pattern("\\bfoo").unsupported)
        assertEquals("\\1", XsdTypes.pattern("(a)\\1").unsupported)
        assertEquals("*+", XsdTypes.pattern("a*+").unsupported)
        assertNull(XsdTypes.pattern("^[^@]+@[^@]+$").unsupported)
        assertEquals("[^@]+@[^@]+", XsdTypes.pattern("^[^@]+@[^@]+$").value)
    }

    @Test
    fun `default text is the literal without quoting`() {
        assertEquals("a \"b\"", XsdTypes.text(StringValue("a \"b\"")))
    }
}
