package io.schemata.target.proto

import io.schemata.target.ProtoFileOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProtoNamesTest {
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

    @Test
    fun `option values are checked by their shape`() {
        fun fits(shape: ProtoFileOptions.Shape, value: String) = ProtoNames.fitsOption(shape, value)
        assertTrue(fits(ProtoFileOptions.Shape.DOTTED, "com.example.orders"))
        assertTrue(fits(ProtoFileOptions.Shape.BACKSLASHED, "Example\\Orders"))
        assertTrue(fits(ProtoFileOptions.Shape.DOUBLE_COLON, "Example::Orders"))
        assertTrue(fits(ProtoFileOptions.Shape.IDENTIFIER, "OrdersProto"))
        assertTrue(fits(ProtoFileOptions.Shape.NON_EMPTY, "example.com/orders;orders"))
        assertTrue(fits(ProtoFileOptions.Shape.BOOL, "anything"))
        assertTrue(fits(ProtoFileOptions.Shape.DOTTED_OR_EMPTY, ""))
        assertTrue(fits(ProtoFileOptions.Shape.DOTTED_OR_EMPTY, "Example.Orders"))
        assertTrue(fits(ProtoFileOptions.Shape.BACKSLASHED_OR_EMPTY, ""))
        assertTrue(fits(ProtoFileOptions.Shape.BACKSLASHED_OR_EMPTY, "Example\\Orders"))
        assertTrue(fits(ProtoFileOptions.Shape.IDENTIFIER_OR_EMPTY, ""))
        assertTrue(fits(ProtoFileOptions.Shape.IDENTIFIER_OR_EMPTY, "EXO"))
        assertFalse(fits(ProtoFileOptions.Shape.DOTTED_OR_EMPTY, "com..x"))
        assertFalse(fits(ProtoFileOptions.Shape.BACKSLASHED_OR_EMPTY, "Example/Orders"))
        assertFalse(fits(ProtoFileOptions.Shape.IDENTIFIER_OR_EMPTY, "Orders Proto"))
        for (key in listOf("java_package", "java_outer_classname", "ruby_package", "go_package")) {
            assertFalse(fits(ProtoFileOptions.key(key)!!.shape, ""), key)
        }
        for (key in
            listOf(
                "csharp_namespace",
                "php_namespace",
                "php_metadata_namespace",
                "objc_class_prefix",
                "php_class_prefix",
                "swift_prefix",
            )) {
            assertTrue(fits(ProtoFileOptions.key(key)!!.shape, ""), key)
        }
        assertFalse(fits(ProtoFileOptions.Shape.DOTTED, "com..x"))
        assertFalse(fits(ProtoFileOptions.Shape.BACKSLASHED, "Example/Orders"))
        assertFalse(fits(ProtoFileOptions.Shape.DOUBLE_COLON, "Example:Orders"))
        assertFalse(fits(ProtoFileOptions.Shape.IDENTIFIER, "Orders Proto"))
        assertFalse(fits(ProtoFileOptions.Shape.NON_EMPTY, ""))
        assertFalse(fits(ProtoFileOptions.Shape.NON_EMPTY, "example.com/\u0001orders"))
    }
}
