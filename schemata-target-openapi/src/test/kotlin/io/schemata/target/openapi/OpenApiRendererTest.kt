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
import kotlin.test.assertTrue

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

    @Test
    fun `renders every scheme kind`() {
        val document =
            OpenApiDocument(
                path = "t.openapi.json",
                title = "t",
                version = "1.0.0",
                description = null,
                server = null,
                tags = emptyList(),
                paths = emptyList(),
                components = emptyList(),
                securitySchemes =
                    listOf(
                        SecurityScheme.Http("bearer", "bearer", "JWT"),
                        SecurityScheme.Http("basic", "basic", null),
                        SecurityScheme.ApiKey("api_key", "cookie", "session"),
                        SecurityScheme.OAuth2(
                            "oauth2",
                            "https://id.example.com/token",
                            "https://id.example.com/authorize",
                            listOf("a:read"),
                        ),
                        SecurityScheme.OpenIdConnect(
                            "openid",
                            "https://id.example.com/.well-known/openid-configuration",
                        ),
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
              "tags": [],
              "paths": {},
              "components": {
                "schemas": {},
                "securitySchemes": {
                  "bearer": {
                    "type": "http",
                    "scheme": "bearer",
                    "bearerFormat": "JWT"
                  },
                  "basic": {
                    "type": "http",
                    "scheme": "basic"
                  },
                  "api_key": {
                    "type": "apiKey",
                    "name": "session",
                    "in": "cookie"
                  },
                  "oauth2": {
                    "type": "oauth2",
                    "flows": {
                      "authorizationCode": {
                        "authorizationUrl": "https://id.example.com/authorize",
                        "tokenUrl": "https://id.example.com/token",
                        "scopes": {
                          "a:read": ""
                        }
                      }
                    }
                  },
                  "openid": {
                    "type": "openIdConnect",
                    "openIdConnectUrl": "https://id.example.com/.well-known/openid-configuration"
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
    fun `renders the document requirement operation requirements and a client credentials flow`() {
        val lowered =
            OpenApiLowering.lower(
                compile(
                    """
                    schema t @openapi(bearer, oauth2_token_url: "https://id.example.com/token", auth: bearer)

                    model R { #1 ok bool }

                    service S {
                      @openapi(auth: oauth2, scopes: "orders:write")
                      #1 write(): R  post "/w"
                      @openapi(auth: none)
                      #2 health(): R  get "/health"
                    }
                    """
                )
            )
        assertEquals(emptyList(), lowered.diagnostics)
        val text = OpenApiRenderer.render(lowered.model).single().content
        val top = "  \"security\": [\n    {\n      \"bearer\": []\n    }\n  ],\n  \"tags\": ["
        assertTrue(top in text, text)
        val write =
            "        \"security\": [\n" +
                "          {\n" +
                "            \"oauth2\": [\n" +
                "              \"orders:write\"\n" +
                "            ]\n" +
                "          }\n" +
                "        ]\n"
        assertTrue(write in text, text)
        assertTrue("        \"security\": []\n" in text, text)
        val schemes =
            "    \"securitySchemes\": {\n" +
                "      \"bearer\": {\n" +
                "        \"type\": \"http\",\n" +
                "        \"scheme\": \"bearer\"\n" +
                "      },\n" +
                "      \"oauth2\": {\n" +
                "        \"type\": \"oauth2\",\n" +
                "        \"flows\": {\n" +
                "          \"clientCredentials\": {\n" +
                "            \"tokenUrl\": \"https://id.example.com/token\",\n" +
                "            \"scopes\": {\n" +
                "              \"orders:write\": \"\"\n" +
                "            }\n" +
                "          }\n" +
                "        }\n" +
                "      }\n" +
                "    }\n"
        assertTrue(schemes in text, text)
    }
}
