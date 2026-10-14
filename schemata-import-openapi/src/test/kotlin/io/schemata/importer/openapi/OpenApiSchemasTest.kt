package io.schemata.importer.openapi

import io.schemata.importer.ImportInput
import io.schemata.importer.ImportResult
import io.schemata.importer.UnitRecord
import io.schemata.importer.UnitType
import io.schemata.lang.Diagnostic
import io.swagger.v3.oas.models.media.Schema
import kotlin.test.Test
import kotlin.test.assertEquals

/** `$ref` for the documents below, where a raw string would read it as a template. */
private const val REF = "\$ref"

class OpenApiSchemasTest {
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
    fun `component names split into schemas and nested declarations`() {
        val r =
            importText(
                "shop.yaml" to
                    """
                    openapi: 3.0.3
                    info: {title: shop, version: 1.0.0}
                    paths: {}
                    components:
                      schemas:
                        shop.Pet:
                          type: object
                          required: [toy]
                          properties:
                            toy: {$REF: '#/components/schemas/shop.Pet.Toy'}
                          additionalProperties: false
                        shop.Pet.Toy:
                          type: object
                          properties:
                            name: {type: string}
                          additionalProperties: false
                        shop.orders.Order:
                          type: object
                          required: [pet, lines]
                          properties:
                            pet: {$REF: '#/components/schemas/shop.Pet'}
                            lines:
                              type: array
                              items: {$REF: '#/components/schemas/shop.orders.Order.Line'}
                          additionalProperties: false
                        shop.orders.Order.Line:
                          type: object
                          required: [toy]
                          properties:
                            toy: {$REF: '#/components/schemas/shop.Pet.Toy'}
                          additionalProperties: false
                        pet_info:
                          type: object
                          properties:
                            order: {$REF: '#/components/schemas/shop.orders.Order'}
                          additionalProperties: false
                    """
            )
        assertEquals(
            """
            schema shop

            import shop.orders

            model Pet {
              toy Toy

              model Toy { name string? }
            }

            model PetInfo {
              order shop.orders.Order?

              @@jsonschema(name: "pet_info")
            }
            """
                .trimIndent() + "\n",
            text(r, "shop.schemata"),
        )
        assertEquals(
            """
            schema shop.orders

            import shop

            model Order {
              pet   shop.Pet
              lines Line[]

              model Line { toy shop.Pet.Toy }
            }
            """
                .trimIndent() + "\n",
            text(r, "shop/orders.schemata"),
        )
        assertEquals(emptyList(), messages(r))
    }

    @Test
    fun `a name that starts with a known schema splits there whatever follows`() {
        val r =
            importText(
                "billing.yaml" to
                    """
                    openapi: 3.0.3
                    info: {title: billing, version: 1.0.0}
                    paths: {}
                    components:
                      schemas: {}
                    """,
                "shop.yaml" to
                    """
                    openapi: 3.0.3
                    info: {title: shop, version: 1.0.0}
                    paths: {}
                    components:
                      schemas:
                        shop.pet_info:
                          type: object
                          properties: {}
                          additionalProperties: false
                        shop.Order:
                          type: object
                          required: [line]
                          properties:
                            line: {$REF: '#/components/schemas/shop.Order.line_item'}
                          additionalProperties: false
                        shop.Order.line_item:
                          type: object
                          properties: {}
                          additionalProperties: false
                        billing.invoice_line:
                          type: object
                          properties: {}
                          additionalProperties: false
                    """,
            )
        assertEquals(
            """
            schema billing

            model InvoiceLine {
              @@jsonschema(name: "invoice_line")
            }
            """
                .trimIndent() + "\n",
            text(r, "billing.schemata"),
        )
        assertEquals(
            """
            schema shop

            model PetInfo {
              @@jsonschema(name: "pet_info")
            }

            model Order {
              line LineItem

              model LineItem {
                @@jsonschema(name: "line_item")
              }
            }
            """
                .trimIndent() + "\n",
            text(r, "shop.schemata"),
        )
        assertEquals(emptyList(), messages(r))
    }

    @Test
    fun `names that are not Schemata names keep their wire names and collisions take a suffix`() {
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
                          required: [firstName, first_name]
                          properties:
                            firstName: {type: string}
                            first_name: {type: string}
                            Kind: {$REF: '#/components/schemas/pet'}
                          additionalProperties: false
                        pet:
                          type: string
                          enum: [in_stock, IN_STOCK, SOLD-OUT]
                    """
            )
        assertEquals(
            """
            schema shop

            model Pet {
              first_name  string @jsonschema(name: "firstName")
              first_name2 string @jsonschema(name: "first_name")
              kind        Pet2?  @jsonschema(name: "Kind")
            }

            @jsonschema(name: "pet")
            enum Pet2 {
              in_stock
              @jsonschema(name: "IN_STOCK") in_stock2
              @jsonschema(name: "SOLD-OUT") sold_out
            }
            """
                .trimIndent() + "\n",
            text(r, "shop.schemata"),
        )
        assertEquals(
            listOf(
                "SCH2402 shop.yaml: #/components/schemas/pet: component 'pet' is imported as 'Pet2', since 'Pet' is taken",
                "SCH2402 shop.yaml: #/components/schemas/Pet/properties/first_name: property 'first_name' is imported as 'first_name2', since 'first_name' is taken",
                "SCH2402 shop.yaml: #/components/schemas/pet/enum/1: enum value 'IN_STOCK' is imported as 'in_stock2', since 'in_stock' is taken",
            ),
            messages(r),
        )
    }

    @Test
    fun `scalars and formats map to builtins`() {
        val r =
            importText(
                "shop.yaml" to
                    """
                    openapi: 3.0.3
                    info: {title: shop, version: 1.0.0}
                    paths: {}
                    components:
                      schemas:
                        Scalars:
                          type: object
                          required: [a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q]
                          properties:
                            a: {type: string}
                            b: {type: string, format: uuid}
                            c: {type: string, format: date}
                            d: {type: string, format: time}
                            e: {type: string, format: date-time}
                            f: {type: string, format: duration}
                            g: {type: string, format: byte}
                            h: {type: string, format: binary}
                            i: {type: integer, format: int32}
                            j: {type: integer, format: int64}
                            k: {type: integer}
                            l: {type: integer, minimum: 0, maximum: 100}
                            m: {type: integer, minimum: 0}
                            n: {type: number, format: float}
                            o: {type: number, format: double}
                            p: {type: number}
                            q: {type: boolean}
                          additionalProperties: false
                    """
            )
        assertEquals(
            """
            schema shop

            model Scalars {
              a string
              b uuid
              c date
              d time
              e instant
              f duration
              g bytes
              h bytes
              i int32
              j int64
              k int64
              l int32    { min 0, max 100 }
              m int64    { min 0 }
              n float32
              o float64
              p float64
              q bool
            }
            """
                .trimIndent() + "\n",
            text(r, "shop.schemata"),
        )
        assertEquals(emptyList(), messages(r))
    }

    @Test
    fun `the target's decimal time and uuid patterns import as those types`() {
        val r =
            importText(
                "shop.json" to
                    """
                    {
                      "openapi": "3.1.0",
                      "info": {"title": "shop", "version": "1.0.0"},
                      "components": {
                        "schemas": {
                          "Amounts": {
                            "type": "object",
                            "properties": {
                              "price": {"type": "string", "pattern": "^-?[0-9]{1,8}(\\.[0-9]{1,2})?$"},
                              "whole": {"type": "string", "pattern": "^-?[0-9]{1,38}$"},
                              "rate": {"type": "string", "pattern": "^-?0(\\.[0-9]{1,5})?$"},
                              "money": {"type": "string", "pattern": "^-?[0-9]{1,15}(\\.[0-9]{1,4})?$"},
                              "digit": {"type": "string", "pattern": "^-?[0-9]{1,1}$"},
                              "at": {"type": "string", "pattern": "^[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]+)?$"},
                              "id": {"type": "string", "format": "uuid", "pattern": "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"},
                              "code": {"type": "string", "pattern": "^-?[0-9]{1,8}(\\.[0-9]{1,2})$"}
                            },
                            "required": ["price", "whole", "rate", "money", "digit", "at", "id", "code"],
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

            model Amounts {
              price decimal(10, 2)
              whole decimal(38, 0)
              rate  decimal(5, 5)
              money decimal(19, 4)
              digit decimal(1, 0)
              at    time
              id    uuid
              code  string         { match "^-?[0-9]{1,8}(\.[0-9]{1,2})$" }
            }
            """
                .trimIndent() + "\n",
            text(r, "shop.schemata"),
        )
        assertEquals(emptyList(), messages(r))
    }

    @Test
    fun `bytes lengths are read as base64 lengths`() {
        val r =
            importText(
                "shop.json" to
                    """
                    {
                      "openapi": "3.1.0",
                      "info": {"title": "shop", "version": "1.0.0"},
                      "components": {
                        "schemas": {
                          "Blobs": {
                            "type": "object",
                            "properties": {
                              "exact": {"type": "string", "minLength": 4, "maxLength": 8, "contentEncoding": "base64"},
                              "rounded": {"type": "string", "format": "byte", "maxLength": 10},
                              "raw": {"type": "string", "format": "binary", "maxLength": 10}
                            },
                            "required": ["exact", "rounded", "raw"],
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

            model Blobs { exact bytes { min 3, max 6 }  rounded bytes { max 6 }  raw bytes { max 10 } }
            """
                .trimIndent() + "\n",
            text(r, "shop.schemata"),
        )
        assertEquals(
            listOf(
                "SCH2403 shop.json: #/components/schemas/Blobs/properties/rounded: base64 maxLength 10 is imported as max 6"
            ),
            messages(r),
        )
    }

    @Test
    fun `required nullable and default decide whether a field is optional`() {
        val r =
            importText(
                "shop.yaml" to
                    """
                    openapi: 3.0.3
                    info: {title: shop, version: 1.0.0}
                    paths: {}
                    components:
                      schemas:
                        Settings:
                          type: object
                          required: [name, nick, retries]
                          properties:
                            name: {type: string}
                            nick: {type: string, nullable: true}
                            note: {type: string}
                            retries: {type: integer, format: int32, default: 3}
                            ratio: {type: number, default: 0.5}
                            price: {type: string, pattern: '^-?[0-9]{1,8}(\.[0-9]{1,2})?$', default: '9.50'}
                            enabled: {type: boolean, default: true}
                            label: {type: string, nullable: true, default: none}
                            status: {$REF: '#/components/schemas/Status'}
                            fallback:
                              allOf: [{$REF: '#/components/schemas/Status'}]
                              default: SOLD-OUT
                            since: {type: string, format: date, default: '2024-01-01'}
                            tags: {type: array, items: {type: string}, default: []}
                          additionalProperties: false
                        Status:
                          type: string
                          enum: [in_stock, SOLD-OUT]
                          default: in_stock
                    """
            )
        assertEquals(
            """
            schema shop

            model Settings {
              name     string
              nick     string?
              note     string?
              retries  int32          = 3
              ratio    float64        = 0.5
              price    decimal(10, 2) = 9.50
              enabled  bool           = true
              label    string?        = "none"
              status   Status?
              fallback Status         = sold_out
              since    date?
              tags     string[]?
            }

            enum Status { in_stock @jsonschema(name: "SOLD-OUT") sold_out }
            """
                .trimIndent() + "\n",
            text(r, "shop.schemata"),
        )
        assertEquals(
            listOf(
                "SCH2405 shop.yaml: #/components/schemas/Settings/properties/since: default \"2024-01-01\" is dropped, since the field's type cannot hold it",
                "SCH2405 shop.yaml: #/components/schemas/Settings/properties/tags: default [] is dropped, since the field's type cannot hold it",
            ),
            messages(r),
        )
    }

    @Test
    fun `a defaulted field the target left out of required keeps its default`() {
        val r =
            importText(
                "shop.json" to
                    """
                    {
                      "openapi": "3.1.0",
                      "info": {"title": "shop", "version": "1.0.0"},
                      "components": {
                        "schemas": {
                          "shop.Page": {
                            "type": "object",
                            "properties": {
                              "limit": {"type": "integer", "minimum": -2147483648, "maximum": 2147483647, "default": 50},
                              "cursor": {"type": ["string", "null"]}
                            },
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

            model Page { limit int32 = 50  cursor string? }
            """
                .trimIndent() + "\n",
            text(r, "shop.schemata"),
        )
        assertEquals(emptyList(), messages(r))
    }

    @Test
    fun `constraints become refinements`() {
        val r =
            importText(
                "shop.yaml" to
                    """
                    openapi: 3.0.3
                    info: {title: shop, version: 1.0.0}
                    paths: {}
                    components:
                      schemas:
                        Limits:
                          type: object
                          required: [code, count, ratio, tags, scores]
                          properties:
                            code: {type: string, minLength: 2, maxLength: 8, pattern: '^[A-Z]+$'}
                            count: {type: integer, format: int32, minimum: 1, maximum: 10}
                            ratio: {type: number, minimum: 0, maximum: 1.5}
                            tags:
                              type: array
                              items: {type: string, maxLength: 20}
                              minItems: 1
                              maxItems: 5
                            scores:
                              type: object
                              additionalProperties: {type: integer, format: int32}
                              minProperties: 1
                              maxProperties: 9
                          additionalProperties: false
                    """
            )
        assertEquals(
            """
            schema shop

            model Limits {
              code   string             { min 2, max 8, match "^[A-Z]+$" }
              count  int32              { min 1, max 10 }
              ratio  float64            { min 0, max 1.5 }
              tags   string[]           { minItems 1, maxItems 5, max 20 }
              scores map<string, int32> { minItems 1, maxItems 9 }
            }
            """
                .trimIndent() + "\n",
            text(r, "shop.schemata"),
        )
        assertEquals(emptyList(), messages(r))
    }

    @Test
    fun `descriptions become docs and deprecated becomes the attribute`() {
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
                          description: |-
                            A pet in the shop.

                            Every pet has a name.
                          type: object
                          required: [name]
                          properties:
                            name:
                              description: The pet's name.
                              type: string
                            legacy_id:
                              description: The id from the old system.
                              deprecated: true
                              type: integer
                              format: int64
                          additionalProperties: false
                        OldPet:
                          deprecated: true
                          type: object
                          properties: {}
                          additionalProperties: false
                        Mood:
                          description: How a pet feels.
                          deprecated: true
                          type: string
                          enum: [calm, playful]
                    """
            )
        assertEquals(
            """
            schema shop

            /// A pet in the shop.
            ///
            /// Every pet has a name.
            model Pet {
              /// The pet's name.
              name      string
              /// The id from the old system.
              legacy_id int64? @deprecated
            }

            model OldPet {
              @@deprecated
            }

            /// How a pet feels.
            @deprecated
            enum Mood { calm playful }
            """
                .trimIndent() + "\n",
            text(r, "shop.schemata"),
        )
        assertEquals(emptyList(), messages(r))
    }

    @Test
    fun `lists and maps keep their element and key types`() {
        val r =
            importText(
                "shop.json" to
                    """
                    {
                      "openapi": "3.1.0",
                      "info": {"title": "shop", "version": "1.0.0"},
                      "components": {
                        "schemas": {
                          "Basket": {
                            "type": "object",
                            "properties": {
                              "skus": {"type": "array", "items": {"type": "string"}},
                              "maybe": {"type": "array", "items": {"type": ["string", "null"]}},
                              "grid": {"type": "array", "items": {"type": "array", "items": {"type": "integer", "format": "int32"}}},
                              "counts": {"type": "object", "additionalProperties": {"type": "integer", "format": "int64"}},
                              "by_id": {"type": "object", "propertyNames": {"type": "string", "pattern": "^(0|-?[1-9][0-9]*)$"}, "additionalProperties": {"type": "string"}},
                              "by_code": {"type": "object", "propertyNames": {"type": "string", "minLength": 2}, "additionalProperties": {"$REF": "#/components/schemas/Size"}},
                              "notes": {"type": "object", "additionalProperties": {"type": ["string", "null"]}},
                              "sizes": {"type": "array", "items": {"$REF": "#/components/schemas/Size"}}
                            },
                            "required": ["skus", "maybe", "grid", "counts", "by_id", "by_code", "notes", "sizes"],
                            "additionalProperties": false
                          },
                          "Size": {"type": "string", "enum": ["small", "large"]}
                        }
                      }
                    }
                    """
            )
        assertEquals(
            """
            schema shop

            model Basket {
              skus    string[]
              maybe   string?[]
              grid    list<int32[]>
              counts  map<string, int64>
              by_id   map<int64, string>
              by_code map<string { min 2 }, Size>
              notes   map<string, string?>
              sizes   Size[]
            }

            enum Size { small large }
            """
                .trimIndent() + "\n",
            text(r, "shop.schemata"),
        )
        assertEquals(emptyList(), messages(r))
    }

    @Test
    fun `a ref field takes its sibling description`() {
        val r =
            importText(
                "shop.json" to
                    """
                    {
                      "openapi": "3.1.0",
                      "info": {"title": "shop", "version": "1.0.0"},
                      "components": {
                        "schemas": {
                          "Order": {
                            "type": "object",
                            "properties": {
                              "buyer": {"description": "Who placed the order.", "$REF": "#/components/schemas/Customer"},
                              "seller": {"$REF": "#/components/schemas/Customer"},
                              "line": {"$REF": "#/components/schemas/Order.Line"},
                              "first": {"$REF": "#/components/schemas/Order/properties/seller"}
                            },
                            "required": ["buyer", "seller", "line", "first"],
                            "additionalProperties": false
                          },
                          "Order.Line": {
                            "type": "object",
                            "properties": {"qty": {"type": "integer", "format": "int32"}},
                            "required": ["qty"],
                            "additionalProperties": false
                          },
                          "Customer": {
                            "type": "object",
                            "properties": {"name": {"type": "string"}},
                            "required": ["name"],
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

            model Order {
              /// Who placed the order.
              buyer  Customer
              seller Customer
              line   OrderLine
              first  Customer
            }

            model OrderLine {
              qty int32

              @@jsonschema(name: "Order.Line")
            }

            model Customer { name string }
            """
                .trimIndent() + "\n",
            text(r, "shop.schemata"),
        )
        assertEquals(emptyList(), messages(r))
    }

    @Test
    fun `every spelling of null imports as a nullable field`() {
        val r =
            importText(
                "new.json" to
                    """
                    {
                      "openapi": "3.1.0",
                      "info": {"title": "new", "version": "1.0.0"},
                      "components": {
                        "schemas": {
                          "Pet": {
                            "type": "object",
                            "properties": {
                              "name": {"type": ["string", "null"]},
                              "owner": {"anyOf": [{"$REF": "#/components/schemas/Owner"}, {"type": "null"}]},
                              "tags": {"description": "Free tags.", "anyOf": [{"type": "array", "items": {"type": "string"}}, {"type": "null"}]}
                            },
                            "required": ["name", "owner", "mood", "tags"],
                            "additionalProperties": false
                          },
                          "Owner": {"type": "object", "properties": {}, "additionalProperties": false}
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
                        Pet:
                          type: object
                          required: [name, owner, home]
                          properties:
                            name: {type: string, nullable: true}
                            owner:
                              allOf: [{$REF: '#/components/schemas/Owner'}]
                              nullable: true
                            home: {$REF: '#/components/schemas/Owner', nullable: true}
                          additionalProperties: false
                        Owner:
                          type: object
                          properties: {}
                          additionalProperties: false
                    """,
            )
        assertEquals(
            """
            schema new

            model Pet {
              name  string?
              owner Owner?
              /// Free tags.
              tags  string[]?
            }

            model Owner {}
            """
                .trimIndent() + "\n",
            text(r, "new.schemata"),
        )
        assertEquals(
            """
            schema old

            model Pet { name string?  owner Owner?  home Owner? }

            model Owner {}
            """
                .trimIndent() + "\n",
            text(r, "old.schemata"),
        )
        assertEquals(emptyList(), messages(r))
    }

    @Test
    fun `a model is open unless additionalProperties is false`() {
        val r =
            importText(
                "shop.yaml" to
                    """
                    openapi: 3.0.3
                    info: {title: shop, version: 1.0.0}
                    paths: {}
                    components:
                      schemas:
                        Unsaid:
                          type: object
                          properties:
                            a: {type: string}
                        Open:
                          type: object
                          properties:
                            a: {type: string}
                          additionalProperties: true
                        Empty:
                          type: object
                          properties:
                            a: {type: string}
                          additionalProperties: {}
                        Closed:
                          type: object
                          properties:
                            a: {type: string}
                          additionalProperties: false
                    """
            )
        assertEquals(
            """
            schema shop

            model Unsaid {
              a string?

              @@jsonschema(open)
            }

            model Open {
              a string?

              @@jsonschema(open)
            }

            model Empty {
              a string?

              @@jsonschema(open)
            }

            model Closed { a string? }
            """
                .trimIndent() + "\n",
            text(r, "shop.schemata"),
        )
        assertEquals(emptyList(), messages(r))
    }

    @Test
    fun `a component repeated across documents is one declaration`() {
        val r =
            importText(
                "orders.yaml" to
                    """
                    openapi: 3.0.3
                    info: {title: orders, version: 1.0.0}
                    paths: {}
                    components:
                      schemas:
                        Order:
                          type: object
                          properties:
                            problem: {$REF: '#/components/schemas/shared.common.Problem'}
                          additionalProperties: false
                        shared.common.Problem:
                          type: object
                          required: [title]
                          properties:
                            title: {type: string}
                          additionalProperties: false
                    """,
                "users.json" to
                    """
                    {
                      "openapi": "3.1.0",
                      "info": {"title": "users", "version": "1.0.0"},
                      "components": {
                        "schemas": {
                          "shared.common.Problem": {
                            "type": "object",
                            "properties": {"title": {"type": "string"}},
                            "required": ["title"],
                            "additionalProperties": false
                          },
                          "User": {
                            "type": "object",
                            "properties": {"problem": {"$REF": "#/components/schemas/shared.common.Problem"}},
                            "additionalProperties": false
                          }
                        }
                      }
                    }
                    """,
            )
        assertEquals(
            """
            schema orders

            import shared.common

            model Order { problem shared.common.Problem? }
            """
                .trimIndent() + "\n",
            text(r, "orders.schemata"),
        )
        assertEquals(
            """
            schema users

            import shared.common

            model User { problem shared.common.Problem? }
            """
                .trimIndent() + "\n",
            text(r, "users.schemata"),
        )
        assertEquals(
            """
            schema shared.common

            model Problem { title string }
            """
                .trimIndent() + "\n",
            text(r, "shared/common.schemata"),
        )
        assertEquals(emptyList(), messages(r))
    }

    @Test
    fun `a component that differs across documents is an error`() {
        val r =
            importText(
                "orders.yaml" to
                    """
                    openapi: 3.0.3
                    info: {title: orders, version: 1.0.0}
                    paths: {}
                    components:
                      schemas:
                        Order:
                          type: object
                          properties:
                            problem: {$REF: '#/components/schemas/shared.common.Problem'}
                          additionalProperties: false
                        shared.common.Problem:
                          type: object
                          required: [title]
                          properties:
                            title: {type: string}
                          additionalProperties: false
                    """,
                "users.json" to
                    """
                    {
                      "openapi": "3.1.0",
                      "info": {"title": "users", "version": "1.0.0"},
                      "components": {
                        "schemas": {
                          "shared.common.Problem": {
                            "type": "object",
                            "properties": {"title": {"type": "string"}, "detail": {"type": "string"}},
                            "required": ["title"],
                            "additionalProperties": false
                          },
                          "User": {
                            "type": "object",
                            "properties": {"problem": {"$REF": "#/components/schemas/shared.common.Problem"}},
                            "additionalProperties": false
                          }
                        }
                      }
                    }
                    """,
            )
        assertEquals(emptyList(), r.files)
        assertEquals(
            listOf(
                "SCH2401 users.json: #/components/schemas/shared.common.Problem: component 'shared.common.Problem' differs from the one in orders.yaml"
            ),
            messages(r),
        )
    }

    @Test
    fun `a free form object imports as a map of strings`() {
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
                          properties:
                            extra: {type: object}
                          additionalProperties: false
                    """
            )
        assertEquals(
            """
            schema shop

            model Pet { extra map<string, string>? }
            """
                .trimIndent() + "\n",
            text(r, "shop.schemata"),
        )
        assertEquals(
            listOf(
                "SCH2403 shop.yaml: #/components/schemas/Pet/properties/extra: free-form object is imported as map<string, string>"
            ),
            messages(r),
        )
    }

    @Test
    fun `a schema with no type imports as string`() {
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
                          properties:
                            anything: {}
                          additionalProperties: false
                    """
            )
        assertEquals(
            """
            schema shop

            model Pet { anything string? }
            """
                .trimIndent() + "\n",
            text(r, "shop.schemata"),
        )
        assertEquals(
            listOf(
                "SCH2403 shop.yaml: #/components/schemas/Pet/properties/anything: schema is imported as string, since Schemata has no type for it"
            ),
            messages(r),
        )
    }

    @Test
    fun `a component that is not a model enum or union is taken at each use`() {
        val r =
            importText(
                "shop.yaml" to
                    """
                    openapi: 3.0.3
                    info: {title: shop, version: 1.0.0}
                    paths: {}
                    components:
                      schemas:
                        Email:
                          type: string
                          maxLength: 254
                    """
            )
        assertEquals(
            """
            schema shop
            """
                .trimIndent() + "\n",
            text(r, "shop.schemata"),
        )
        assertEquals(
            listOf(
                "SCH2403 shop.yaml: #/components/schemas/Email: component 'Email' is not a model, enum or union; each use takes its type"
            ),
            messages(r),
        )
    }

    @Test
    fun `a pattern beside a format is dropped`() {
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
                          properties:
                            id: {type: string, format: uuid, pattern: "^[0-9]+$"}
                          additionalProperties: false
                    """
            )
        assertEquals(
            """
            schema shop

            model Pet { id uuid? }
            """
                .trimIndent() + "\n",
            text(r, "shop.schemata"),
        )
        assertEquals(
            listOf(
                "SCH2405 shop.yaml: #/components/schemas/Pet/properties/id: pattern '^[0-9]+\$' dropped; uuid takes none"
            ),
            messages(r),
        )
    }

    @Test
    fun `a nested component without its parent is held by an empty model`() {
        val r =
            importText(
                "shop.yaml" to
                    """
                    openapi: 3.0.3
                    info: {title: shop, version: 1.0.0}
                    paths: {}
                    components:
                      schemas:
                        shop.Cart.Line:
                          type: object
                          properties: {}
                          additionalProperties: false
                    """
            )
        assertEquals(
            """
            schema shop

            model Cart {
              model Line {}
            }
            """
                .trimIndent() + "\n",
            text(r, "shop.schemata"),
        )
        assertEquals(
            listOf(
                "SCH2403 shop.yaml: #/components/schemas/shop.Cart.Line: component 'shop.Cart.Line' is nested in 'Cart', which no component declares; an empty model holds it"
            ),
            messages(r),
        )
    }

    @Test
    fun `a component nested in an enum is an error`() {
        val r =
            importText(
                "shop.yaml" to
                    """
                    openapi: 3.0.3
                    info: {title: shop, version: 1.0.0}
                    paths: {}
                    components:
                      schemas:
                        shop.Status:
                          type: string
                          enum: [open]
                        shop.Status.Detail:
                          type: object
                          properties: {}
                          additionalProperties: false
                    """
            )
        assertEquals(emptyList(), r.files)
        assertEquals(
            listOf(
                "SCH2401 shop.yaml: #/components/schemas/shop.Status.Detail: component 'shop.Status.Detail' is nested in 'Status', which is not a model"
            ),
            messages(r),
        )
    }

    @Test
    fun `a component that reaches itself without a model is an error`() {
        val r =
            importText(
                "shop.yaml" to
                    """
                    openapi: 3.0.3
                    info: {title: shop, version: 1.0.0}
                    paths: {}
                    components:
                      schemas:
                        Tree:
                          type: array
                          items: {$REF: "#/components/schemas/Tree"}
                        Forest:
                          type: object
                          properties:
                            trees: {$REF: "#/components/schemas/Tree"}
                          additionalProperties: false
                    """
            )
        assertEquals(emptyList(), r.files)
        assertEquals(
            listOf(
                "SCH2403 shop.yaml: #/components/schemas/Tree: component 'Tree' is not a model, enum or union; each use takes its type",
                "SCH2401 shop.yaml: #/components/schemas/Tree: component 'Tree' refers to itself and is not a model, enum or union",
            ),
            messages(r),
        )
    }

    @Test
    fun `a component of a referenced file joins the schema of its file`() {
        val r =
            importText(
                "orders.yaml" to
                    """
                    openapi: 3.0.3
                    info: {title: orders, version: 1.0.0}
                    paths: {}
                    components:
                      schemas:
                        Order:
                          type: object
                          required: [total]
                          properties:
                            total: {$REF: 'lib/common.yaml#/components/schemas/Money'}
                          additionalProperties: false
                    """,
                locate = { path -> ImportInput(path, COMMON).takeIf { path == "lib/common.yaml" } },
            )
        assertEquals(
            """
            schema orders

            import common

            model Order { total common.Money }
            """
                .trimIndent() + "\n",
            text(r, "orders.schemata"),
        )
        assertEquals(
            """
            schema common

            model Money { amount decimal(19, 2) }
            """
                .trimIndent() + "\n",
            text(r, "common.schemata"),
        )
        assertEquals(emptyList(), messages(r))
    }

    @Test
    fun `a document Schemata wrote imports back to its source`() {
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
                              "price": {
                                "description": "The price.",
                                "type": "string",
                                "pattern": "^-?[0-9]{1,8}(\\.[0-9]{1,2})?$",
                                "default": "9.50"
                              },
                              "rate": {"type": "string", "pattern": "^-?0(\\.[0-9]{1,5})?$"},
                              "at": {"type": "string", "pattern": "^[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]+)?$"},
                              "blob": {"type": "string", "minLength": 4, "maxLength": 8, "contentEncoding": "base64"},
                              "count": {"type": "integer", "minimum": 0, "maximum": 2147483647},
                              "big": {
                                "type": ["integer", "null"],
                                "minimum": -9223372036854775808,
                                "maximum": 9223372036854775807
                              },
                              "ratio": {"type": "number"},
                              "name": {"type": "string", "pattern": "^[a-z]+$", "minLength": 1, "maxLength": 20},
                              "tags": {"type": "array", "items": {"type": "string"}, "minItems": 1, "maxItems": 3},
                              "attrs": {
                                "type": "object",
                                "additionalProperties": {
                                  "type": ["integer", "null"],
                                  "minimum": -9223372036854775808,
                                  "maximum": 9223372036854775807
                                }
                              },
                              "ids": {
                                "type": "object",
                                "propertyNames": {"type": "string", "pattern": "^(0|-?[1-9][0-9]*)$"},
                                "additionalProperties": {"type": "string"}
                              },
                              "status": {"$REF": "#/components/schemas/shop.Status", "default": "in_stock"},
                              "old": {"type": "boolean", "default": true, "deprecated": true},
                              "wireName": {"type": "string"},
                              "line": {"anyOf": [{"$REF": "#/components/schemas/shop.Item.Line"}, {"type": "null"}]}
                            },
                            "required": ["id", "rate", "at", "blob", "count", "ratio", "name", "tags", "attrs", "ids", "wireName"]
                          },
                          "shop.Item.Line": {
                            "type": "object",
                            "properties": {"qty": {"type": "integer", "minimum": -2147483648, "maximum": 2147483647}},
                            "required": ["qty"],
                            "additionalProperties": false
                          },
                          "shop.Status": {"description": "Stock state.", "type": "string", "enum": ["in_stock", "SOLD-OUT"]}
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
              id        uuid
              /// The price.
              price     decimal(10, 2)      = 9.50
              rate      decimal(5, 5)
              at        time
              blob      bytes               { min 3, max 6 }
              count     int32               { min 0 }
              big       int64?
              ratio     float64
              name      string              { min 1, max 20, match "^[a-z]+$" }
              tags      string[]            { minItems 1, maxItems 3 }
              attrs     map<string, int64?>
              ids       map<int64, string>
              status    Status              = in_stock
              old       bool                @deprecated = true
              wire_name string              @jsonschema(name: "wireName")
              line      Line?

              model Line { qty int32 }

              @@jsonschema(open)
            }

            /// Stock state.
            enum Status { in_stock @jsonschema(name: "SOLD-OUT") sold_out }
            """
                .trimIndent() + "\n",
            text(r, "shop.schemata"),
        )
        assertEquals(emptyList(), messages(r))
    }

    @Test
    fun `an operation's schema lowers to a declaration the operation claims`() {
        val text =
            """
            openapi: 3.0.3
            info: {title: shop, version: 1.0.0}
            paths:
              /orders:
                post:
                  parameters:
                    - name: status
                      in: query
                      schema: {type: string}
                  requestBody:
                    content:
                      application/json:
                        schema:
                          type: object
                          required: [sku]
                          properties:
                            sku: {type: string}
                            note: {type: string}
                          additionalProperties: false
                  responses:
                    '204': {description: No content}
            components:
              schemas:
                Order:
                  type: object
                  properties:
                    id: {type: string, format: uuid}
                  additionalProperties: false
            """
                .trimIndent()
        val doc =
            (OpenApiReader.read(ImportInput("shop.yaml", text, "shop.yaml")) as ReadOutcome.Read)
                .doc
        val diagnostics = mutableListOf<Diagnostic>()
        val report = Report(diagnostics)
        val schemas =
            SchemaLowering(listOf(doc), Refs(mapOf(doc.path to doc), { null }, report), report) {
                "shop"
            }
        val post = Pointer.ROOT / "paths" / "/orders" / "post"
        val status =
            schemas.fieldType(
                doc,
                post / "parameters" / 0 / "schema",
                Schema<Any>(),
                "PlaceRequest",
                "status",
            )
        assertEquals(UnitType.Scalar("string", emptyList()), status.type)
        // a scalar nests nothing, and taking the nested shapes clears them
        assertEquals(emptyList(), schemas.takeNested("PlaceRequest"))
        val body =
            schemas.declaration(
                doc,
                post / "requestBody" / "content" / "application/json" / "schema",
                Schema<Any>(),
                "PlaceRequest",
            )
        val record = body as UnitRecord
        assertEquals("PlaceRequest", record.name)
        assertEquals(
            listOf("sku" to false, "note" to true),
            record.fields.map { it.name to it.nullable },
        )
        // a name already taken at the top level takes a suffix, and the declaration is renamed
        assertEquals("Order2", schemas.synthesize("shop", "Order", body))
        assertEquals("PlaceRequest", schemas.synthesize("shop", "PlaceRequest", body))
        assertEquals(
            listOf("Order", "Order2", "PlaceRequest"),
            schemas.declarations().getValue("shop").map { it.name },
        )
        assertEquals(emptyList(), diagnostics)
    }

    private companion object {
        /** A file the documents reference that is not itself an input. */
        val COMMON =
            """
                openapi: 3.0.3
                info: {title: common, version: 1.0.0}
                paths: {}
                components:
                  schemas:
                    Money:
                      type: object
                      required: [amount]
                      properties:
                        amount: {type: string, pattern: '^-?[0-9]{1,17}(\.[0-9]{1,2})?$'}
                      additionalProperties: false
                    Unused:
                      type: object
                      properties: {}
            """
                .trimIndent()
    }
}
