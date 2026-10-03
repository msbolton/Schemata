package io.schemata.target.proto

import io.schemata.core.ir.AnnotationValue
import io.schemata.core.ir.Annotations
import io.schemata.core.ir.Namespace
import io.schemata.lang.Span
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProtoNamesTest {
    private val at = Span("t.schemata", 1, 1, 1, 1)

    private fun proto(vararg pairs: Pair<String, String>) =
        Annotations(mapOf("proto" to pairs.associate { (k, v) -> k to AnnotationValue.Str(v) }))

    @Test
    fun `snake and upper snake follow camel boundaries`() {
        assertEquals("bank_transfer", ProtoNames.snakeCase("BankTransfer"))
        assertEquals("uuid", ProtoNames.snakeCase("uuid"))
        assertEquals("order_status", ProtoNames.snakeCase("OrderStatus"))
        assertEquals("ORDER_STATUS", ProtoNames.upperSnake("OrderStatus"))
        assertEquals("STATUS", ProtoNames.upperSnake("Status"))
        assertEquals("http_status", ProtoNames.snakeCase("HTTPStatus"))
        assertEquals("io_error", ProtoNames.snakeCase("IOError"))
        assertEquals("kind2", ProtoNames.snakeCase("Kind2"))
        assertEquals("HTTP_STATUS", ProtoNames.upperSnake("HTTPStatus"))
    }

    @Test
    fun `packages honour the proto package override`() {
        val ns = Namespace("shop.orders", emptyList(), at)
        assertEquals("shop.orders", ProtoNames.packageOf(ns))
        assertEquals(
            "corp.v1",
            ProtoNames.packageOf(ns.copy(annotations = proto("package" to "corp.v1"))),
        )
    }

    @Test
    fun `enum values are prefixed with the proto enum name`() {
        assertEquals("ORDER_STATUS_PENDING", ProtoNames.valueName("OrderStatus", "pending"))
        assertEquals("STATUS_OLD_STATUS", ProtoNames.valueName("Status", "old_status"))
        assertEquals("STATUS_UNSPECIFIED", ProtoNames.zeroValue("Status"))
    }

    @Test
    fun `the JSON name drops underscores and capitalises the letter after one`() {
        assertEquals("a1", ProtoNames.jsonName("a_1"))
        assertEquals("a1", ProtoNames.jsonName("a1"))
        assertEquals("placedAt", ProtoNames.jsonName("placed_at"))
        assertEquals("placedAt", ProtoNames.jsonName("placed__at"))
        assertEquals("Name", ProtoNames.jsonName("_name"))
        assertEquals("aB", ProtoNames.jsonName("aB"))
    }

    @Test
    fun `identifiers and packages are what proto can spell`() {
        assertTrue(ProtoNames.isIdentifier("_order2"))
        assertFalse(ProtoNames.isIdentifier("1x"))
        assertTrue(ProtoNames.isPackage("shop.orders.v1"))
        assertFalse(ProtoNames.isPackage("shop..orders"))
    }
}
