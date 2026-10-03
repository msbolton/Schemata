package io.schemata.importer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImportNamesTest {
    @Test
    fun `identifier checks`() {
        assertTrue(ImportNames.isLowerSnake("placed_at"))
        assertFalse(ImportNames.isLowerSnake("full-name"))
        assertFalse(ImportNames.isLowerSnake("Personal"))
        assertTrue(ImportNames.isNamespaceSegment("gpx"))
        assertFalse(ImportNames.isNamespaceSegment("GPX-1.1"))
    }

    @Test
    fun `conversions`() {
        assertEquals("full_name", ImportNames.lowerSnake("full-name"))
        assertEquals("full_name", ImportNames.lowerSnake("fullName"))
        assertEquals("personal", ImportNames.lowerSnake("Personal"))
        assertEquals("v1x", ImportNames.lowerSnake("1x"))
        assertEquals("GpxType", ImportNames.upperCamel("gpxType"))
        assertEquals("WptPoint", ImportNames.upperCamel("wpt-point"))
        assertEquals("gpx_1_1", ImportNames.namespaceStem("schemas/GPX-1.1.xsd"))
        assertEquals("orders", ImportNames.namespaceStem("shop/orders.xsd"))
    }

    @Test
    fun `keywords are never identifiers`() {
        assertFalse(ImportNames.isLowerSnake("true"))
        assertFalse(ImportNames.isLowerSnake("stream"))
        assertFalse(ImportNames.isNamespaceSegment("import"))
        assertEquals("true_value", ImportNames.lowerSnake("true"))
        assertEquals("false_value", ImportNames.lowerSnake("false"))
        assertEquals("stream_value", ImportNames.lowerSnake("stream"))
        assertEquals("record_value", ImportNames.lowerSnake("Record"))
        assertEquals("true_value", ImportNames.lowerSnake("True"))
        assertEquals("v2fa", ImportNames.lowerSnake("2fa"))
        assertTrue(ImportNames.isLowerSnake("true_value"))
        assertFalse(ImportNames.isLowerSnake("a__b"))
        assertFalse(ImportNames.isLowerSnake("a_"))
    }

    @Test
    fun `null is reserved like a keyword`() {
        assertFalse(ImportNames.isLowerSnake("null"))
        assertFalse(ImportNames.isNamespaceSegment("null"))
        assertEquals("null_value", ImportNames.lowerSnake("null"))
        assertEquals("null_value", ImportNames.lowerSnake("NULL"))
    }

    @Test
    fun `converted names never hold a doubled or trailing underscore`() {
        for (input in listOf("a--b", "a-", "-a-", "a__b")) {
            val out = ImportNames.lowerSnake(input)
            assertTrue(ImportNames.isLowerSnake(out), "$input -> $out")
            assertFalse("__" in out || out.endsWith("_"), "$input -> $out")
        }
    }

    @Test
    fun `record names never start with a digit`() {
        assertEquals("V3d", ImportNames.upperCamel("3d"))
        assertEquals("V2dPoint", ImportNames.upperCamel("2d-point"))
        assertEquals("Import", ImportNames.upperCamel("import"))
    }
}
