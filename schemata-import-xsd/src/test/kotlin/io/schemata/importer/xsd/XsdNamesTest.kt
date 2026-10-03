package io.schemata.importer.xsd

import kotlin.test.Test
import kotlin.test.assertEquals

class XsdNamesTest {
    @Test
    fun `type override`() {
        assertEquals("Order" to null, XsdNames.typeOverride("OrderType"))
        assertEquals("Gpx" to "gpx", XsdNames.typeOverride("gpxType"))
        assertEquals("Address" to null, XsdNames.typeOverride("Address"))
    }
}
