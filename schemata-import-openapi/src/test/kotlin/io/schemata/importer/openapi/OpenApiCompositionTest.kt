package io.schemata.importer.openapi

import io.schemata.importer.ImportInput
import io.schemata.importer.ImportResult
import kotlin.test.Test
import kotlin.test.assertEquals

/** `$ref` for the documents below, where a raw string would read it as a template. */
private const val REF = "\$ref"

class OpenApiCompositionTest {
    private fun importText(
        vararg files: Pair<String, String>,
        locate: (String) -> ImportInput? = { null },
    ): ImportResult =
        OpenApiImporter.import(
            files.map { ImportInput(it.first, it.second.trimIndent(), relative = it.first) },
            null,
            locate,
        )

    private fun text(result: ImportResult, path: String): String =
        result.files.single { it.path == path }.content

    private fun messages(result: ImportResult): List<String> =
        result.diagnostics.map { "${it.code.id} ${it.message}" }

    @Test
    fun `unions come from references the target's tagged form and anyOf with null`() {
        val r =
            importText(
                "shop.json" to
                    """
                    {
                      "openapi": "3.1.0",
                      "info": {"title": "shop", "version": "1.0.0"},
                      "components": {
                        "schemas": {
                          "Pet": {
                            "description": "Any pet.",
                            "oneOf": [
                              {"$REF": "#/components/schemas/Cat"},
                              {"description": "A good dog.", "$REF": "#/components/schemas/Dog"}
                            ],
                            "discriminator": {"propertyName": "kind"}
                          },
                          "Pick": {
                            "type": "object",
                            "oneOf": [
                              {"type": "object", "properties": {"cat": {"$REF": "#/components/schemas/Cat"}}, "required": ["cat"], "additionalProperties": false},
                              {"type": "object", "properties": {"string": {"description": "A name.", "type": "string"}}, "required": ["string"], "additionalProperties": false},
                              {"type": "object", "properties": {"hound": {"$REF": "#/components/schemas/Dog"}}, "required": ["hound"], "additionalProperties": false}
                            ]
                          },
                          "Either": {
                            "anyOf": [{"$REF": "#/components/schemas/Cat"}, {"type": "integer", "format": "int32"}, {"type": "null"}]
                          },
                          "Owner": {
                            "type": "object",
                            "properties": {
                              "cat": {"anyOf": [{"$REF": "#/components/schemas/Cat"}, {"type": "null"}]},
                              "either": {"$REF": "#/components/schemas/Either"}
                            },
                            "required": ["cat", "either"],
                            "additionalProperties": false
                          },
                          "Cat": {"type": "object", "properties": {}, "additionalProperties": false},
                          "Dog": {"type": "object", "properties": {}, "additionalProperties": false}
                        }
                      }
                    }
                    """
            )
        assertEquals(
            """
            schema shop

            /// Any pet.
            union Pet =
              Cat |
              /// A good dog.
              Dog

            union Pick =
              Cat |
              /// A name.
              string |
              Dog

            union Either = Cat | int32

            model Owner { cat Cat?  either Either? }

            model Cat {}

            model Dog {}
            """
                .trimIndent() + "\n",
            text(r, "shop.schemata"),
        )
        assertEquals(
            listOf(
                "SCH2403 shop.json: #/components/schemas/Pick/oneOf/2/properties/hound: union member tag 'hound' is not the member's own; the regenerated union tags it 'dog'"
            ),
            messages(r),
        )
    }

    @Test
    fun `a discriminator mapping Schemata cannot reproduce is approximated`() {
        val r =
            importText(
                "shop.yaml" to
                    """
                    openapi: 3.0.3
                    info: {title: shop, version: 1.0.0}
                    paths: {}
                    components:
                      schemas:
                        Kept:
                          oneOf:
                            - $REF: '#/components/schemas/Cat'
                            - $REF: '#/components/schemas/Dog'
                          discriminator:
                            propertyName: kind
                            mapping:
                              cat: '#/components/schemas/Cat'
                              dog: Dog
                        Lost:
                          oneOf:
                            - $REF: '#/components/schemas/Cat'
                            - $REF: '#/components/schemas/Dog'
                          discriminator:
                            propertyName: kind
                            mapping:
                              CAT: '#/components/schemas/Cat'
                              DOG: '#/components/schemas/Dog'
                        Cat: {type: object, properties: {}, additionalProperties: false}
                        Dog: {type: object, properties: {}, additionalProperties: false}
                    """
            )
        assertEquals(
            """
            schema shop

            union Kept = Cat | Dog

            union Lost = Cat | Dog

            model Cat {}

            model Dog {}
            """
                .trimIndent() + "\n",
            text(r, "shop.schemata"),
        )
        assertEquals(
            listOf(
                "SCH2403 shop.yaml: #/components/schemas/Lost/discriminator: discriminator mapping is not kept; the union is tagged by member name"
            ),
            messages(r),
        )
    }

    @Test
    fun `allOf parts flatten into one model and the first part wins a conflict`() {
        val r =
            importText(
                "shop.yaml" to
                    """
                    openapi: 3.0.3
                    info: {title: shop, version: 1.0.0}
                    paths: {}
                    components:
                      schemas:
                        Named:
                          type: object
                          required: [name]
                          properties:
                            name: {type: string}
                        Dated:
                          type: object
                          properties:
                            name: {type: string, maxLength: 10}
                            created: {type: string, format: date-time}
                        Pet:
                          description: A pet with a name.
                          allOf:
                            - $REF: '#/components/schemas/Named'
                            - $REF: '#/components/schemas/Dated'
                            - type: object
                              required: [created]
                              properties:
                                age: {type: integer, format: int32}
                          additionalProperties: false
                    """
            )
        assertEquals(
            """
            schema shop

            model Named {
              name string

              @@jsonschema(open)
            }

            model Dated {
              name    string?  { max 10 }
              created instant?

              @@jsonschema(open)
            }

            /// A pet with a name.
            model Pet { name string  created instant  age int32? }
            """
                .trimIndent() + "\n",
            text(r, "shop.schemata"),
        )
        assertEquals(
            listOf(
                "SCH2403 shop.yaml: #/components/schemas/Pet/allOf/1: allOf parts disagree on property 'name'; the first part's schema is kept"
            ),
            messages(r),
        )
    }

    @Test
    fun `inline objects enums and oneOf on a property become nested declarations`() {
        val r =
            importText(
                "shop.yaml" to
                    """
                    openapi: 3.0.3
                    info: {title: shop, version: 1.0.0}
                    paths: {}
                    components:
                      schemas:
                        Order:
                          type: object
                          required: [address, status, payment]
                          properties:
                            address:
                              type: object
                              required: [street]
                              properties:
                                street: {type: string}
                                geo:
                                  type: object
                                  properties:
                                    lat: {type: number}
                                  additionalProperties: false
                              additionalProperties: false
                            status:
                              type: string
                              enum: [placed, shipped]
                            payment:
                              oneOf:
                                - $REF: '#/components/schemas/Card'
                                - type: object
                                  properties:
                                    iban: {type: string}
                            lines:
                              type: array
                              items:
                                type: object
                                properties:
                                  sku: {type: string}
                          additionalProperties: false
                        OrderStatus:
                          type: string
                          enum: [a, b]
                        Card: {type: object, properties: {}, additionalProperties: false}
                    """
            )
        assertEquals(
            """
            schema shop

            model Order {
              address OrderAddress
              status  OrderStatus2
              payment OrderPayment
              lines   OrderLines[]?

              model OrderAddress {
                street string
                geo    OrderAddressGeo?

                model OrderAddressGeo { lat float64? }
              }

              enum OrderStatus2 { placed shipped }

              union OrderPayment = Card | OrderPaymentMember2

              model OrderPaymentMember2 {
                iban string?

                @@jsonschema(open)
              }

              model OrderLines {
                sku string?

                @@jsonschema(open)
              }
            }

            enum OrderStatus { a b }

            model Card {}
            """
                .trimIndent() + "\n",
            text(r, "shop.schemata"),
        )
        assertEquals(
            listOf(
                "SCH2402 shop.yaml: #/components/schemas/Order/properties/status: inline schema is imported as 'OrderStatus2', since 'OrderStatus' is taken"
            ),
            messages(r),
        )
    }

    @Test
    fun `the target's enum with value docs imports as an enum`() {
        val r =
            importText(
                "shop.json" to
                    """
                    {
                      "openapi": "3.1.0",
                      "info": {"title": "shop", "version": "1.0.0"},
                      "components": {
                        "schemas": {
                          "Kind": {
                            "description": "What kind of pet.",
                            "type": "string",
                            "oneOf": [
                              {"const": "small", "description": "A small one."},
                              {"const": "Big"}
                            ]
                          },
                          "Pet": {
                            "type": "object",
                            "properties": {
                              "kind": {"$REF": "#/components/schemas/Kind", "default": "Big"},
                              "size": {"type": "string", "oneOf": [{"const": "s", "description": "Small."}, {"const": "l"}]}
                            },
                            "required": ["size"],
                            "additionalProperties": false
                          }
                        }
                      }
                    }
                    """
            )
        assertEquals(
            """
            schema shop

            /// What kind of pet.
            enum Kind {
              /// A small one.
              small
              @jsonschema(name: "Big") big
            }

            model Pet {
              kind Kind    = big
              size PetSize

              enum PetSize {
                /// Small.
                s
                l
              }
            }
            """
                .trimIndent() + "\n",
            text(r, "shop.schemata"),
        )
        assertEquals(emptyList(), messages(r))
    }

    @Test
    fun `an enum of numbers is widened to its type`() {
        val r =
            importText(
                "shop.yaml" to
                    """
                    openapi: 3.0.3
                    info: {title: shop, version: 1.0.0}
                    paths: {}
                    components:
                      schemas:
                        Pet:
                          type: object
                          required: [level, weight]
                          properties:
                            level: {type: integer, format: int32, enum: [1, 2, 3]}
                            weight: {type: number, enum: [0.5, 1.5]}
                          additionalProperties: false
                        Priority:
                          type: integer
                          enum: [1, 2]
                    """
            )
        assertEquals(
            """
            schema shop

            model Pet { level int32  weight float64 }
            """
                .trimIndent() + "\n",
            text(r, "shop.schemata"),
        )
        assertEquals(
            listOf(
                "SCH2403 shop.yaml: #/components/schemas/Priority: component 'Priority' is not a model, enum or union; each use takes its type",
                "SCH2404 shop.yaml: #/components/schemas/Pet/properties/level: integer enum imported as int32; its values are not kept",
                "SCH2404 shop.yaml: #/components/schemas/Pet/properties/weight: number enum imported as float64; its values are not kept",
            ),
            messages(r),
        )
    }

    @Test
    fun `an unknown format is approximated by its type`() {
        val r =
            importText(
                "shop.yaml" to
                    """
                    openapi: 3.0.3
                    info: {title: shop, version: 1.0.0}
                    paths: {}
                    components:
                      schemas:
                        Contact:
                          type: object
                          required: [email, site, count, share]
                          properties:
                            email: {type: string, format: email, maxLength: 254}
                            site: {type: string, format: uri}
                            count: {type: integer, format: uint32, minimum: 0, maximum: 4294967295}
                            share: {type: number, format: decimal}
                          additionalProperties: false
                    """
            )
        assertEquals(
            """
            schema shop

            model Contact {
              email string  { max 254 }
              site  string
              count int64   { min 0, max 4294967295 }
              share float64
            }
            """
                .trimIndent() + "\n",
            text(r, "shop.schemata"),
        )
        assertEquals(
            listOf(
                "SCH2403 shop.yaml: #/components/schemas/Contact/properties/email: format 'email' has no Schemata type; imported as string",
                "SCH2403 shop.yaml: #/components/schemas/Contact/properties/site: format 'uri' has no Schemata type; imported as string",
                "SCH2403 shop.yaml: #/components/schemas/Contact/properties/count: format 'uint32' has no Schemata type; imported as int64",
                "SCH2403 shop.yaml: #/components/schemas/Contact/properties/share: format 'decimal' has no Schemata type; imported as float64",
            ),
            messages(r),
        )
    }

    @Test
    fun `exclusive bounds in both versions become inclusive bounds`() {
        val r =
            importText(
                "new.json" to
                    """
                    {
                      "openapi": "3.1.0",
                      "info": {"title": "new", "version": "1.0.0"},
                      "components": {
                        "schemas": {
                          "Range": {
                            "type": "object",
                            "properties": {
                              "above": {"type": "number", "exclusiveMinimum": 0},
                              "below": {"type": "integer", "format": "int32", "exclusiveMaximum": 100, "minimum": 0}
                            },
                            "required": ["above", "below"],
                            "additionalProperties": false
                          }
                        }
                      }
                    }
                    """,
                "old.yaml" to
                    """
                    openapi: 3.0.3
                    info: {title: old, version: 1.0.0}
                    paths: {}
                    components:
                      schemas:
                        Range:
                          type: object
                          required: [above, below]
                          properties:
                            above: {type: number, minimum: 0, exclusiveMinimum: true}
                            below: {type: integer, format: int32, maximum: 100, exclusiveMaximum: true, minimum: 0, exclusiveMinimum: false}
                          additionalProperties: false
                    """,
            )
        assertEquals(
            """
            schema new

            model Range { above float64 { min 0 }  below int32 { min 0, max 100 } }
            """
                .trimIndent() + "\n",
            text(r, "new.schemata"),
        )
        assertEquals(
            """
            schema old

            model Range { above float64 { min 0 }  below int32 { min 0, max 100 } }
            """
                .trimIndent() + "\n",
            text(r, "old.schemata"),
        )
        assertEquals(
            listOf(
                "SCH2403 new.json: #/components/schemas/Range/properties/above: exclusiveMinimum 0 is imported as min 0",
                "SCH2403 new.json: #/components/schemas/Range/properties/below: exclusiveMaximum 100 is imported as max 100",
                "SCH2403 old.yaml: #/components/schemas/Range/properties/above: exclusiveMinimum 0 is imported as min 0",
                "SCH2403 old.yaml: #/components/schemas/Range/properties/below: exclusiveMaximum 100 is imported as max 100",
            ),
            messages(r),
        )
    }

    @Test
    fun `keywords Schemata has no place for are dropped once per place`() {
        val r =
            importText(
                "shop.json" to
                    """
                    {
                      "openapi": "3.1.0",
                      "info": {"title": "shop", "version": "1.0.0"},
                      "components": {
                        "schemas": {
                          "Pet": {
                            "type": "object",
                            "externalDocs": {"url": "https://example.com/pets"},
                            "xml": {"name": "pet"},
                            "properties": {
                              "id": {"type": "integer", "format": "int64", "readOnly": true, "example": 7},
                              "secret": {"type": "string", "writeOnly": true, "examples": ["x"]},
                              "tags": {"type": "array", "items": {"type": "string"}, "uniqueItems": true},
                              "code": {"type": "string", "not": {"const": "x"}},
                              "rule": {"type": "object", "if": {"required": ["a"]}, "then": {"required": ["b"]}, "patternProperties": {"^x-": {"type": "string"}}, "additionalProperties": {"type": "string"}},
                              "step": {"type": "integer", "format": "int32", "multipleOf": 5}
                            },
                            "required": ["id", "secret", "tags", "code", "rule", "step"],
                            "additionalProperties": false
                          }
                        }
                      }
                    }
                    """
            )
        assertEquals(
            """
            schema shop

            model Pet {
              id     int64
              secret string
              tags   string[]
              code   string
              rule   map<string, string>
              step   int32
            }
            """
                .trimIndent() + "\n",
            text(r, "shop.schemata"),
        )
        assertEquals(
            listOf(
                "SCH2405 shop.json: #/components/schemas/Pet: keywords 'xml', 'externalDocs' dropped",
                "SCH2405 shop.json: #/components/schemas/Pet/properties/id: keywords 'readOnly', 'example' dropped",
                "SCH2405 shop.json: #/components/schemas/Pet/properties/secret: keywords 'writeOnly', 'examples' dropped",
                "SCH2405 shop.json: #/components/schemas/Pet/properties/tags: keyword 'uniqueItems' dropped",
                "SCH2405 shop.json: #/components/schemas/Pet/properties/code: keyword 'not' dropped",
                "SCH2405 shop.json: #/components/schemas/Pet/properties/rule: keywords 'if', 'then', 'patternProperties' dropped",
                "SCH2405 shop.json: #/components/schemas/Pet/properties/step: keyword 'multipleOf' dropped",
            ),
            messages(r),
        )
    }

    @Test
    fun `additionalProperties beside properties opens the model`() {
        val r =
            importText(
                "shop.yaml" to
                    """
                    openapi: 3.0.3
                    info: {title: shop, version: 1.0.0}
                    paths: {}
                    components:
                      schemas:
                        Pet:
                          type: object
                          required: [name]
                          properties:
                            name: {type: string}
                          additionalProperties:
                            type: string
                    """
            )
        assertEquals(
            """
            schema shop

            model Pet {
              name string

              @@jsonschema(open)
            }
            """
                .trimIndent() + "\n",
            text(r, "shop.schemata"),
        )
        assertEquals(
            listOf(
                "SCH2403 shop.yaml: #/components/schemas/Pet/additionalProperties: additionalProperties beside properties is imported as an open model; its schema is not kept"
            ),
            messages(r),
        )
    }

    @Test
    fun `a document Schemata wrote with unions and inline shapes imports back to its source`() {
        val r =
            importText(
                "shop.json" to
                    """
                    {
                      "openapi": "3.1.0",
                      "info": {"title": "shop", "version": "1.0.0"},
                      "components": {
                        "schemas": {
                          "shop.Item": {
                            "description": "A thing to buy.",
                            "type": "object",
                            "properties": {
                              "id": {
                                "type": "string",
                                "format": "uuid",
                                "pattern": "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
                              },
                              "status": {"$REF": "#/components/schemas/shop.Status", "default": "in_stock"},
                              "kind": {"anyOf": [{"$REF": "#/components/schemas/shop.Kind"}, {"type": "null"}]},
                              "pick": {"$REF": "#/components/schemas/shop.Pick"},
                              "addr": {"$REF": "#/components/schemas/shop.Item.ItemAddr"},
                              "mode": {"$REF": "#/components/schemas/shop.Item.ItemMode"}
                            },
                            "required": ["id", "pick", "addr", "mode"],
                            "additionalProperties": false
                          },
                          "shop.Item.ItemAddr": {
                            "type": "object",
                            "properties": {
                              "street": {"type": "string"},
                              "geo": {
                                "anyOf": [{"$REF": "#/components/schemas/shop.Item.ItemAddr.ItemAddrGeo"}, {"type": "null"}]
                              }
                            },
                            "required": ["street"],
                            "additionalProperties": false
                          },
                          "shop.Item.ItemAddr.ItemAddrGeo": {
                            "type": "object",
                            "properties": {"lat": {"type": "number"}},
                            "required": ["lat"],
                            "additionalProperties": false
                          },
                          "shop.Item.ItemMode": {"type": "string", "enum": ["fast", "slow"]},
                          "shop.Status": {"description": "Stock state.", "type": "string", "enum": ["in_stock", "SOLD-OUT"]},
                          "shop.Kind": {
                            "type": "string",
                            "oneOf": [{"const": "small", "description": "A small one."}, {"const": "big"}]
                          },
                          "shop.Pick": {
                            "description": "What to pick.",
                            "type": "object",
                            "oneOf": [
                              {
                                "type": "object",
                                "properties": {"status": {"description": "The status.", "$REF": "#/components/schemas/shop.Status"}},
                                "required": ["status"],
                                "additionalProperties": false
                              },
                              {
                                "type": "object",
                                "properties": {"item": {"$REF": "#/components/schemas/shop.Item"}},
                                "required": ["item"],
                                "additionalProperties": false
                              },
                              {
                                "type": "object",
                                "properties": {"string": {"type": "string"}},
                                "required": ["string"],
                                "additionalProperties": false
                              }
                            ]
                          }
                        }
                      }
                    }
                    """
            )
        assertEquals(
            """
            schema shop

            /// A thing to buy.
            model Item {
              id     uuid
              status Status   = in_stock
              kind   Kind?
              pick   Pick
              addr   ItemAddr
              mode   ItemMode

              model ItemAddr {
                street string
                geo    ItemAddrGeo?

                model ItemAddrGeo { lat float64 }
              }

              enum ItemMode { fast slow }
            }

            /// Stock state.
            enum Status { in_stock @jsonschema(name: "SOLD-OUT") sold_out }

            enum Kind {
              /// A small one.
              small
              big
            }

            /// What to pick.
            union Pick =
              /// The status.
              Status |
              Item |
              string
            """
                .trimIndent() + "\n",
            text(r, "shop.schemata"),
        )
        assertEquals(emptyList(), messages(r))
    }

    @Test
    fun `an allOf that includes itself is an error`() {
        val r =
            importText(
                "shop.yaml" to
                    """
                    openapi: 3.0.3
                    info: {title: shop, version: 1.0.0}
                    paths: {}
                    components:
                      schemas:
                        Pet:
                          allOf:
                            - $REF: "#/components/schemas/Animal"
                        Animal:
                          allOf:
                            - $REF: "#/components/schemas/Pet"
                    """
            )
        assertEquals(emptyList(), r.files)
        assertEquals(
            listOf(
                "SCH2401 shop.yaml: #/components/schemas/Pet/allOf/0: allOf part '#/components/schemas/Animal' includes itself",
                "SCH2401 shop.yaml: #/components/schemas/Animal/allOf/0: allOf part '#/components/schemas/Pet' includes itself",
            ),
            messages(r),
        )
    }
}
