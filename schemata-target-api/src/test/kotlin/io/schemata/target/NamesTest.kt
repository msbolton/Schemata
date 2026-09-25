package io.schemata.target

import io.schemata.core.ir.Builtin
import io.schemata.core.ir.ListOf
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
    fun `type text reads as a user would write it`() {
        assertEquals(
            "string(max = 5)?",
            TypeText.of(
                Scalar(Builtin.STRING, Refinements(max = BigDecimal.valueOf(5))),
                nullable = true,
            ),
        )
        assertEquals("list<uuid>", TypeText.of(ListOf(Scalar(Builtin.UUID), false)))
        assertEquals(
            "decimal(19, 4)",
            TypeText.of(Scalar(Builtin.DECIMAL, Refinements(precision = 19, scale = 4))),
        )
    }
}
