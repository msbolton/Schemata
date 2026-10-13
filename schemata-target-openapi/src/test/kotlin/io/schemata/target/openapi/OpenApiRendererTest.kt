package io.schemata.target.openapi

import io.schemata.core.ir.Verb
import io.schemata.target.jsonschema.Common
import io.schemata.target.jsonschema.JsonDef
import io.schemata.target.jsonschema.ObjectSchema
import io.schemata.target.jsonschema.Property
import io.schemata.target.jsonschema.RefSchema
import io.schemata.target.jsonschema.ScalarSchema
import io.schemata.testkit.Golden
import kotlin.test.Test
import kotlin.test.assertEquals

class OpenApiRendererTest {
    @Test
    fun `renders the orders golden`() {
        val lowered = OpenApiLowering.lower(compile(ORDERS))
        assertEquals(emptyList(), lowered.diagnostics)
        val file = OpenApiRenderer.render(lowered.model).single()
        assertEquals("shop/orders.openapi.json", file.path)
        Golden.assertMatches("orders.openapi.json", file.content)
    }

    @Test
    fun `renders servers descriptions deprecation and parameter docs`() {
        val document =
            OpenApiDocument(
                path = "t.openapi.json",
                title = "t",
                version = "2.0.0",
                description = null,
                server = "https://api.example.com",
                tags = listOf(Tag("S", "Deprecated.")),
                paths =
                    listOf(
                        PathItem(
                            "/r/{id}",
                            listOf(
                                OpenApiOperation(
                                    Verb.GET,
                                    "S_get",
                                    "S",
                                    "Get one.",
                                    "At length.",
                                    deprecated = true,
                                    parameters =
                                        listOf(
                                            Parameter(
                                                "id",
                                                "path",
                                                true,
                                                ScalarSchema("string"),
                                                "The id.",
                                                deprecated = true,
                                                style = false,
                                            ),
                                            Parameter(
                                                "q",
                                                "query",
                                                false,
                                                ScalarSchema(
                                                    "string",
                                                    common = Common(nullable = true),
                                                ),
                                                null,
                                                deprecated = false,
                                                style = true,
                                            ),
                                        ),
                                    requestBody = null,
                                    responses = mapOf("204" to Response("No content")),
                                )
                            ),
                        )
                    ),
                components =
                    listOf(
                        JsonDef(
                            "t.R",
                            ObjectSchema(
                                listOf(Property("ok", ScalarSchema("boolean"), true)),
                                closed = true,
                            ),
                        )
                    ),
            )
        val text = OpenApiRenderer.render(OpenApiModel(listOf(document))).single().content
        assertEquals(
            """
            {
              "openapi": "3.1.0",
              "info": {
                "title": "t",
                "version": "2.0.0"
              },
              "servers": [
                {
                  "url": "https://api.example.com"
                }
              ],
              "tags": [
                {
                  "name": "S",
                  "description": "Deprecated."
                }
              ],
              "paths": {
                "/r/{id}": {
                  "get": {
                    "operationId": "S_get",
                    "tags": [
                      "S"
                    ],
                    "summary": "Get one.",
                    "description": "At length.",
                    "deprecated": true,
                    "parameters": [
                      {
                        "name": "id",
                        "in": "path",
                        "required": true,
                        "description": "The id.",
                        "deprecated": true,
                        "schema": {
                          "type": "string"
                        }
                      },
                      {
                        "name": "q",
                        "in": "query",
                        "required": false,
                        "style": "form",
                        "explode": true,
                        "schema": {
                          "type": [
                            "string",
                            "null"
                          ]
                        }
                      }
                    ],
                    "responses": {
                      "204": {
                        "description": "No content"
                      }
                    }
                  }
                }
              },
              "components": {
                "schemas": {
                  "t.R": {
                    "type": "object",
                    "properties": {
                      "ok": {
                        "type": "boolean"
                      }
                    },
                    "required": [
                      "ok"
                    ],
                    "additionalProperties": false
                  }
                }
              }
            }
            """
                .trimIndent() + "\n",
            text,
        )
    }

    @Test
    fun `renders every response in order with headers before content`() {
        val document =
            OpenApiDocument(
                path = "t.openapi.json",
                title = "t",
                version = "1.0.0",
                description = null,
                server = null,
                tags = listOf(Tag("S", null)),
                paths =
                    listOf(
                        PathItem(
                            "/r",
                            listOf(
                                OpenApiOperation(
                                    Verb.POST,
                                    "S_make",
                                    "S",
                                    null,
                                    null,
                                    deprecated = false,
                                    parameters = emptyList(),
                                    requestBody = null,
                                    responses =
                                        linkedMapOf(
                                            "201" to
                                                Response(
                                                    "Created",
                                                    Body(
                                                        "application/json",
                                                        RefSchema("#/components/schemas/t.R"),
                                                    ),
                                                    linkedMapOf(
                                                        "Location" to
                                                            Header(
                                                                ScalarSchema("string"),
                                                                "Where it lives.",
                                                                required = true,
                                                                deprecated = false,
                                                            ),
                                                        "X-Old" to
                                                            Header(
                                                                ScalarSchema("string"),
                                                                null,
                                                                required = false,
                                                                deprecated = true,
                                                            ),
                                                    ),
                                                ),
                                            "404" to Response("Not Found"),
                                            "default" to Response("Error"),
                                        ),
                                )
                            ),
                        )
                    ),
                components =
                    listOf(
                        JsonDef(
                            "t.R",
                            ObjectSchema(
                                listOf(Property("ok", ScalarSchema("boolean"), true)),
                                closed = true,
                            ),
                        )
                    ),
            )
        val text = OpenApiRenderer.render(OpenApiModel(listOf(document))).single().content
        assertEquals(
            """
            {
              "openapi": "3.1.0",
              "info": {
                "title": "t",
                "version": "1.0.0"
              },
              "tags": [
                {
                  "name": "S"
                }
              ],
              "paths": {
                "/r": {
                  "post": {
                    "operationId": "S_make",
                    "tags": [
                      "S"
                    ],
                    "responses": {
                      "201": {
                        "description": "Created",
                        "headers": {
                          "Location": {
                            "description": "Where it lives.",
                            "required": true,
                            "schema": {
                              "type": "string"
                            }
                          },
                          "X-Old": {
                            "required": false,
                            "deprecated": true,
                            "schema": {
                              "type": "string"
                            }
                          }
                        },
                        "content": {
                          "application/json": {
                            "schema": {
                              "${'$'}ref": "#/components/schemas/t.R"
                            }
                          }
                        }
                      },
                      "404": {
                        "description": "Not Found"
                      },
                      "default": {
                        "description": "Error"
                      }
                    }
                  }
                }
              },
              "components": {
                "schemas": {
                  "t.R": {
                    "type": "object",
                    "properties": {
                      "ok": {
                        "type": "boolean"
                      }
                    },
                    "required": [
                      "ok"
                    ],
                    "additionalProperties": false
                  }
                }
              }
            }
            """
                .trimIndent() + "\n",
            text,
        )
    }
}
