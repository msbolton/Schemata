package io.schemata.importer.openapi

import io.schemata.importer.ImportInput
import io.schemata.importer.ImportResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Shapes the openapi target writes that a hand-written document seldom holds, imported back the way
 * the target wrote them, so a document Schemata wrote compiles back from its import byte for byte.
 */
class TargetShapesTest {
    private fun importJson(path: String, json: String): ImportResult =
        OpenApiImporter.import(listOf(ImportInput(path, json.trimIndent(), relative = path)))

    private fun messages(result: ImportResult) =
        result.diagnostics.map { "${it.code.id} ${it.message}" }

    private fun document(parameters: String) =
        """
        {
          "openapi": "3.1.0",
          "info": { "title": "shop", "version": "1.0.0" },
          "tags": [ { "name": "Orders" } ],
          "paths": {
            "/Orders/upload": {
              "post": {
                "operationId": "Orders_upload",
                "tags": [ "Orders" ],
                $parameters
                "requestBody": {
                  "required": true,
                  "content": {
                    "application/x-ndjson": {
                      "schema": { "${'$'}ref": "#/components/schemas/shop.Chunk" }
                    }
                  }
                },
                "responses": {
                  "200": {
                    "description": "Receipt",
                    "content": {
                      "application/json": {
                        "schema": { "${'$'}ref": "#/components/schemas/shop.Receipt" }
                      }
                    }
                  }
                }
              }
            }
          },
          "components": {
            "schemas": {
              "shop.Chunk": {
                "type": "object",
                "properties": { "data": { "type": "string", "contentEncoding": "base64" } },
                "required": [ "data" ],
                "additionalProperties": false
              },
              "shop.Receipt": {
                "type": "object",
                "properties": {
                  "count": {
                    "type": "integer",
                    "minimum": -9223372036854775808,
                    "maximum": 9223372036854775807
                  }
                },
                "required": [ "count" ],
                "additionalProperties": false
              }
            }
          }
        }
        """

    @Test
    fun `an ndjson request body is a streamed request`() {
        val result = importJson("shop.openapi.json", document(parameters = ""))
        assertEquals(emptyList(), messages(result))
        val text = result.files.single { it.path == "shop.schemata" }.content
        assertTrue("upload(stream Chunk): Receipt" in text, text)
    }

    @Test
    fun `an ndjson body beside a parameter is not json and is dropped`() {
        val parameter =
            """
            "parameters": [
              { "name": "trace", "in": "header", "required": true, "schema": { "type": "string" } }
            ],
            """
        val result = importJson("shop.openapi.json", document(parameters = parameter))
        assertTrue(messages(result).any { it.startsWith("SCH2405 ") }, messages(result).toString())
        val text = result.files.single { it.path == "shop.schemata" }.content
        assertTrue("stream Chunk" !in text, text)
    }

    private val defaults =
        """
        {
          "openapi": "3.1.0",
          "info": { "title": "shop", "version": "1.0.0" },
          "tags": [ { "name": "Orders" } ],
          "paths": {
            "/orders": {
              "get": {
                "operationId": "Orders_list",
                "tags": [ "Orders" ],
                "parameters": [
                  {
                    "name": "limit",
                    "in": "query",
                    "required": false,
                    "style": "form",
                    "explode": true,
                    "schema": { "type": "integer", "minimum": 1, "maximum": 200, "default": 50 }
                  }
                ],
                "responses": {
                  "200": {
                    "description": "Page",
                    "content": {
                      "application/json": {
                        "schema": { "${'$'}ref": "#/components/schemas/shop.Page" }
                      }
                    }
                  }
                }
              }
            }
          },
          "components": {
            "schemas": {
              "shop.Page": {
                "type": "object",
                "properties": {
                  "size": {
                    "type": "integer",
                    "minimum": -2147483648,
                    "maximum": 2147483647,
                    "default": 20
                  },
                  "note": { "type": [ "string", "null" ], "default": "none" },
                  "cursor": { "type": [ "string", "null" ] }
                },
                "additionalProperties": false
              }
            }
          }
        }
        """

    @Test
    fun `a default outside required with no null in its type is a plain field`() {
        val result = importJson("shop.openapi.json", defaults)
        assertEquals(emptyList(), messages(result))
        val text = result.files.single { it.path == "shop.schemata" }.content
        assertTrue(
            Regex("""limit\s+int32\s+\{ min 1, max 200 \}\s+= 50""").containsMatchIn(text),
            text,
        )
        assertTrue(Regex("""size\s+int32\s+= 20""").containsMatchIn(text), text)
        assertTrue(Regex("""note\s+string\?\s+= "none"""").containsMatchIn(text), text)
        assertTrue(Regex("""cursor\s+string\?""").containsMatchIn(text), text)
    }
}
