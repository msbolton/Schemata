package io.schemata.target.xsd

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class XsdNamesTest {
    @Test
    fun `type names append Type and concatenate nested paths`() {
        assertEquals("OrderType", XsdNames.typeName(listOf("Order")))
        assertEquals("OrderLineType", XsdNames.typeName(listOf("Order", "Line")))
    }

    @Test
    fun `ncname accepts letters digits underscores hyphens and dots, not a leading digit`() {
        assertTrue(XsdNames.isNCName("placed_at"))
        assertTrue(XsdNames.isNCName("_x-y.z"))
        assertFalse(XsdNames.isNCName("1bad"))
        assertFalse(XsdNames.isNCName("a b"))
        assertFalse(XsdNames.isNCName("a:b"))
    }

    @Test
    fun `absolute uris have a scheme`() {
        assertTrue(XsdNames.isAbsoluteUri("urn:schemata:shop.orders"))
        assertTrue(XsdNames.isAbsoluteUri("http://example.com/orders"))
        assertFalse(XsdNames.isAbsoluteUri("orders"))
        assertFalse(XsdNames.isAbsoluteUri("/orders"))
    }
}
