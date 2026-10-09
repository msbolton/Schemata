package io.schemata.importer.xsd

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class XsdNamesTest {
    @Test
    fun `type override`() {
        assertEquals("Order" to null, XsdNames.typeOverride("OrderType"))
        assertEquals("Gpx" to "gpx", XsdNames.typeOverride("gpxType"))
        assertEquals("Address" to null, XsdNames.typeOverride("Address"))
    }

    @Test
    fun `an override is accepted when it is a valid XML name`() {
        listOf("Order", "_x", "a-b", "a.b", "a1").forEach {
            assertTrue(XsdNames.isValidOverride(it), it)
        }
    }

    @Test
    fun `an override is rejected when the xsd target would reject the name`() {
        listOf("", "1a", "-a", ".a", "a b", "a:b", "a/b").forEach {
            assertFalse(XsdNames.isValidOverride(it), it)
        }
    }
}
