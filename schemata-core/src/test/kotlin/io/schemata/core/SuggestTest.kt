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
    fun `upper camel joins segments`() {
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
