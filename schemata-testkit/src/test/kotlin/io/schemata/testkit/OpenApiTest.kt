package io.schemata.testkit

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenApiTest {
    private val minimal =
        """
        {
          "openapi": "3.1.0",
          "info": {"title": "t", "version": "1.0.0"},
          "paths": {
            "/items/{id}": {
              "get": {
                "operationId": "S_get",
                "parameters": [
                  {"name": "id", "in": "path", "required": true, "schema": {"type": "string"}}
                ],
                "responses": {
                  "200": {
                    "description": "Item",
                    "content": {
                      "application/json": {"schema": {"${'$'}ref": "#/components/schemas/t.Item"}}
                    }
                  }
                }
              }
            }
          },
          "components": {
            "schemas": {
              "t.Item": {
                "type": "object",
                "properties": {"tags": {"type": "array", "items": {"${'$'}ref": "#/components/schemas/t.Tag"}}},
                "additionalProperties": false
              },
              "t.Tag": {"type": "string", "enum": ["a", "b"]}
            }
          }
        }
        """
            .trimIndent()

    @Test
    fun `a minimal document validates`() {
        assertNull(OpenApi.validate(minimal))
    }

    @Test
    fun `a document that is not OpenAPI 3_1 is refused`() {
        val message = OpenApi.validate(minimal.replace("\"3.1.0\"", "\"2.0\""))
        assertNotNull(message)
        assertTrue(message.contains("openapi"), message)
    }

    @Test
    fun `a response without a description is refused`() {
        assertNotNull(OpenApi.validate(minimal.replace("\"description\": \"Item\",", "")))
    }

    @Test
    fun `a schema object that breaks draft 2020-12 is refused with its location`() {
        val message = OpenApi.validate(minimal.replace("\"type\": \"object\"", "\"type\": 17"))
        assertNotNull(message)
        assertTrue(message.startsWith("components.schemas.t.Item: "), message)
    }

    @Test
    fun `a dangling component reference is refused`() {
        val message =
            OpenApi.validate(
                minimal.replace("#/components/schemas/t.Tag", "#/components/schemas/t.Gone")
            )
        assertNotNull(message)
        assertTrue(message.contains("t.Gone"), message)
    }

    @Test
    fun `text that is not JSON is refused`() {
        assertNotNull(OpenApi.validate("{"))
    }
}
