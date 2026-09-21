package io.schemata.target.proto

import io.schemata.core.ir.BoolValue
import io.schemata.core.ir.Builtin
import io.schemata.core.ir.EnumRef
import io.schemata.core.ir.IntValue
import io.schemata.core.ir.ListOf
import io.schemata.core.ir.MapOf
import io.schemata.core.ir.QualifiedName
import io.schemata.core.ir.RealValue
import io.schemata.core.ir.Ref
import io.schemata.core.ir.Refinements
import io.schemata.core.ir.Scalar
import io.schemata.core.ir.StringValue
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProtoTypesTest {
    private fun big(n: Long) = BigDecimal.valueOf(n)

    @Test
    fun `type text reads as a user would write it`() {
        assertEquals("uuid", ProtoTypes.text(Scalar(Builtin.UUID)))
        assertEquals("string?", ProtoTypes.text(Scalar(Builtin.STRING), nullable = true))
        assertEquals(
            "decimal(19, 4)",
            ProtoTypes.text(Scalar(Builtin.DECIMAL, Refinements(precision = 19, scale = 4))),
        )
        assertEquals(
            "decimal(19, 4, min = 0)",
            ProtoTypes.text(
                Scalar(Builtin.DECIMAL, Refinements(min = big(0), precision = 19, scale = 4))
            ),
        )
        assertEquals(
            "string(min = 2, max = 8, pattern = \"^[a-z]+$\")",
            ProtoTypes.text(
                Scalar(
                    Builtin.STRING,
                    Refinements(min = big(2), max = big(8), pattern = "^[a-z]+$"),
                )
            ),
        )
        assertEquals(
            "float64(min = -1.5)",
            ProtoTypes.text(Scalar(Builtin.FLOAT64, Refinements(min = BigDecimal("-1.5")))),
        )
        assertEquals(
            "list<string(max = 3)?>(max = 2)",
            ProtoTypes.text(
                ListOf(
                    Scalar(Builtin.STRING, Refinements(max = big(3))),
                    true,
                    Refinements(max = big(2)),
                )
            ),
        )
        assertEquals(
            "map<string, Line?>(min = 1)",
            ProtoTypes.text(
                MapOf(
                    Scalar(Builtin.STRING),
                    Ref(QualifiedName("a", listOf("Order", "Line"))),
                    true,
                    Refinements(min = big(1)),
                )
            ),
        )
    }

    @Test
    fun `value text is the literal as written`() {
        assertEquals("3", ProtoTypes.text(IntValue(3)))
        assertEquals("1.25", ProtoTypes.text(RealValue(BigDecimal("1.25"))))
        assertEquals("\"a\\\"b\"", ProtoTypes.text(StringValue("a\"b")))
        assertEquals("true", ProtoTypes.text(BoolValue(true)))
        assertEquals(
            "pending",
            ProtoTypes.text(EnumRef(QualifiedName("a", listOf("Status")), "pending")),
        )
    }

    @Test
    fun `plain scalars have a keyword and the rest do not`() {
        assertEquals("bool", ProtoTypes.keyword(Builtin.BOOL))
        assertEquals("float", ProtoTypes.keyword(Builtin.FLOAT32))
        assertEquals("double", ProtoTypes.keyword(Builtin.FLOAT64))
        assertEquals("bytes", ProtoTypes.keyword(Builtin.BYTES))
        assertNull(ProtoTypes.keyword(Builtin.UUID))
        assertNull(ProtoTypes.keyword(Builtin.INSTANT))
    }
}
