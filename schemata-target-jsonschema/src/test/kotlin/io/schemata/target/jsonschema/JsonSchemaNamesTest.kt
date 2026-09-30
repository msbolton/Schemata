package io.schemata.target.jsonschema

import io.schemata.core.ir.AnnotationValue
import io.schemata.core.ir.Annotations
import io.schemata.core.ir.Namespace
import io.schemata.lang.Span
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JsonSchemaNamesTest {
    private val span = Span("s.schemata", 1, 1, 1, 10)

    @Test
    fun `paths and ids derive from the namespace`() {
        val ns = Namespace("shop.orders", emptyList(), span, Annotations.NONE)
        assertEquals("shop/orders.schema.json", JsonSchemaNames.pathOf(ns))
        assertEquals("urn:schemata:shop.orders", JsonSchemaNames.idOf(ns))
    }

    @Test
    fun `an id override replaces the urn`() {
        val ns =
            Namespace(
                "shop.orders",
                emptyList(),
                span,
                Annotations(
                    mapOf(
                        "jsonschema" to
                            mapOf("id" to AnnotationValue.Str("https://example.com/orders"))
                    )
                ),
            )
        assertEquals("https://example.com/orders", JsonSchemaNames.idOf(ns))
    }

    @Test
    fun `keys and tags`() {
        assertEquals("Order.Line", JsonSchemaNames.defsKey(listOf("Order", "Line")))
        assertEquals("bank_transfer", JsonSchemaNames.tag("BankTransfer"))
        assertEquals("int64", JsonSchemaNames.tag("int64"))
    }

    @Test
    fun `absolute uris have a scheme`() {
        assertTrue(JsonSchemaNames.isAbsoluteUri("urn:example:orders"))
        assertTrue(JsonSchemaNames.isAbsoluteUri("https://example.com/s"))
        assertFalse(JsonSchemaNames.isAbsoluteUri("orders"))
        assertFalse(JsonSchemaNames.isAbsoluteUri("/orders"))
    }
}
