package io.schemata.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SuggestTest {
    @Test
    fun `lower snake splits camel and squashes separators`() {
        assertEquals("order_line", Suggest.lowerSnake("OrderLine"))
        assertEquals("order_line", Suggest.lowerSnake("order-line"))
        assertEquals("placed_at", Suggest.lowerSnake("placedAt"))
        assertEquals("http_status", Suggest.lowerSnake("HTTPStatus"))
    }

    @Test
    fun `the suggestion for a loose name satisfies the rule`() {
        assertEquals("a_b", Suggest.lowerSnake("a__b"))
        assertEquals("a_b", Suggest.lowerSnake("a_b_"))
        assertEquals("a_b", Suggest.lowerSnake("__a__b__"))
        assertEquals("abc", Suggest.lowerSnake("ABC"))
    }

    @Test
    fun `a suggestion is never a reserved word and never starts with a digit`() {
        assertEquals("true_value", Suggest.lowerSnake("true_"))
        assertEquals("false_value", Suggest.lowerSnake("False"))
        assertEquals("record_value", Suggest.lowerSnake("Record"))
        assertEquals("null_value", Suggest.lowerSnake("null"))
        assertEquals("null_value", Suggest.lowerSnake("NULL"))
        assertEquals("v1x", Suggest.lowerSnake("_1x"))
        assertEquals("v2_fa", Suggest.lowerSnake("2FA"))
        assertEquals("true_value", Suggest.example("true_", Suggest.lowerSnake("true_")))
    }

    @Test
    fun `upper camel joins segments`() {
        assertEquals("True", Suggest.upperCamel("true_"))
        assertEquals("OrderLine", Suggest.upperCamel("order_line"))
        assertEquals("OrderLine", Suggest.upperCamel("orderLine"))
        assertEquals("Order", Suggest.upperCamel("order"))
    }

    @Test
    fun `example is null when the suggestion adds nothing`() {
        assertNull(Suggest.example("order", "order"))
        assertNull(Suggest.example("_", ""))
        assertEquals("order_line", Suggest.example("OrderLine", "order_line"))
    }
}
