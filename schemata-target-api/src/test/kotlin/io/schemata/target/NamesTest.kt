package io.schemata.target

import io.schemata.core.ir.Builtin
import io.schemata.core.ir.ListOf
import io.schemata.core.ir.MapOf
import io.schemata.core.ir.Refinements
import io.schemata.core.ir.Scalar
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals

class NamesTest {
    @Test
    fun `snake case splits camel and acronym boundaries`() {
        assertEquals("order_line", Names.snakeCase("OrderLine"))
        assertEquals("http_status", Names.snakeCase("HTTPStatus"))
        assertEquals("io_error", Names.snakeCase("IOError"))
        assertEquals("http2_server", Names.snakeCase("Http2Server"))
        assertEquals("kind2", Names.snakeCase("Kind2"))
        assertEquals("uuid", Names.snakeCase("uuid"))
    }

    @Test
    fun `upper camel joins the underscore parts`() {
        assertEquals("ListOrders", Names.upperCamel("list_orders"))
        assertEquals("GetV2", Names.upperCamel("get_v2"))
        assertEquals("Get", Names.upperCamel("get"))
        assertEquals("A", Names.upperCamel("a"))
        assertEquals("AB", Names.upperCamel("_a__b_"))
    }

    @Test
    fun `type text reads as a user would write it`() {
        assertEquals(
            "string? { max 5 }",
            TypeText.of(
                Scalar(Builtin.STRING, Refinements(max = BigDecimal.valueOf(5))),
                nullable = true,
            ),
        )
        assertEquals("uuid[]", TypeText.of(ListOf(Scalar(Builtin.UUID), false)))
        assertEquals(
            "decimal(19, 4)",
            TypeText.of(Scalar(Builtin.DECIMAL, Refinements(precision = 19, scale = 4))),
        )
    }

    @Test
    fun `type text writes bounds as the options of the slot`() {
        val code =
            Scalar(Builtin.STRING, Refinements(max = BigDecimal.valueOf(3), pattern = "^[A-Z]+$"))
        assertEquals(
            "string?[]? { minItems 1, maxItems 4, max 3, match \"^[A-Z]+$\" }",
            TypeText.of(
                ListOf(code, true, Refinements(min = BigDecimal.ONE, max = BigDecimal.valueOf(4))),
                nullable = true,
            ),
        )
        assertEquals(
            "list<int32[] { maxItems 2 }> { minItems 1 }",
            TypeText.of(
                ListOf(
                    ListOf(Scalar(Builtin.INT32), false, Refinements(max = BigDecimal.valueOf(2))),
                    false,
                    Refinements(min = BigDecimal.ONE),
                )
            ),
        )
        assertEquals(
            "map<string { max 3, match \"^[A-Z]+$\" }, int32? { min 0 }> { maxItems 9 }",
            TypeText.of(
                MapOf(
                    code,
                    Scalar(Builtin.INT32, Refinements(min = BigDecimal.ZERO)),
                    true,
                    Refinements(max = BigDecimal.valueOf(9)),
                )
            ),
        )
    }
}
