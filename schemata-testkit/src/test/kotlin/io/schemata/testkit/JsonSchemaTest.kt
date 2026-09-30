package io.schemata.testkit

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JsonSchemaTest {
    private val customers =
        """
        {
          "${'$'}schema": "https://json-schema.org/draft/2020-12/schema",
          "${'$'}id": "urn:schemata:shop.customers",
          "${'$'}defs": {
            "Customer": {
              "type": "object",
              "properties": {"id": {"type": "string", "format": "uuid"}},
              "required": ["id"],
              "additionalProperties": false
            }
          }
        }
        """
            .trimIndent()
    private val orders =
        """
        {
          "${'$'}schema": "https://json-schema.org/draft/2020-12/schema",
          "${'$'}id": "urn:schemata:shop.orders",
          "${'$'}defs": {
            "Order": {
              "type": "object",
              "properties": {
                "customer": {"${'$'}ref": "urn:schemata:shop.customers#/${'$'}defs/Customer"},
                "total": {"type": "string", "pattern": "^-?[0-9]{1,15}(\\.[0-9]{1,4})?$"}
              },
              "required": ["customer", "total"],
              "additionalProperties": false
            }
          }
        }
        """
            .trimIndent()
    private val files =
        mapOf("shop/customers.schema.json" to customers, "shop/orders.schema.json" to orders)

    @Test
    fun `documents that resolve across ids validate`() {
        assertNull(JsonSchema.validate(files))
    }

    @Test
    fun `a document that breaks the meta-schema is reported with its path`() {
        val broken = orders.replace("\"type\": \"object\"", "\"type\": 17")
        val message = JsonSchema.validate(files + ("shop/orders.schema.json" to broken))
        assertNotNull(message)
        assertTrue(message.startsWith("shop/orders.schema.json: "), message)
    }

    @Test
    fun `a dangling ref is reported`() {
        val dangling = orders.replace("shop.customers#", "shop.nowhere#")
        assertNotNull(JsonSchema.validate(files + ("shop/orders.schema.json" to dangling)))
    }

    @Test
    fun `an instance is checked against one def with format assertions on`() {
        val good =
            """{"customer": {"id": "3fa85f64-5717-4562-b3fc-2c963f66afa6"}, "total": "12.50"}"""
        val badUuid = """{"customer": {"id": "not-a-uuid"}, "total": "12.50"}"""
        val extra =
            """{"customer": {"id": "3fa85f64-5717-4562-b3fc-2c963f66afa6"}, "total": "12.50", "x": 1}"""
        assertNull(JsonSchema.check(files, "urn:schemata:shop.orders", "Order", good))
        assertNotNull(JsonSchema.check(files, "urn:schemata:shop.orders", "Order", badUuid))
        assertNotNull(JsonSchema.check(files, "urn:schemata:shop.orders", "Order", extra))
    }
}
