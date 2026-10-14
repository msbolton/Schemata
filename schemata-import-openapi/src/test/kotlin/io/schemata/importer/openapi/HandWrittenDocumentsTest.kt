package io.schemata.importer.openapi

import io.schemata.importer.ImportInput
import io.schemata.importer.ImportResult
import io.schemata.lang.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `$ref` for the documents below, where a raw string would read it as a template. */
private const val REF = "\$ref"

/** Shapes hand-written documents use that no document the openapi target writes holds. */
class HandWrittenDocumentsTest {
    private fun importText(
        vararg files: Pair<String, String>,
        locate: (String) -> ImportInput? = { null },
    ): ImportResult =
        OpenApiImporter.import(
            files.map { ImportInput(it.first, it.second.trimIndent(), relative = it.first) },
            null,
            locate,
        )

    private fun text(result: ImportResult, path: String = "shop.schemata"): String =
        result.files.single { it.path == path }.content

    private fun messages(result: ImportResult): List<String> =
        result.diagnostics.map { "${it.code.id} ${it.message}" }

    private fun assertHas(text: String, vararg parts: String) {
        parts.forEach { assertTrue(it in text, "expected <$it> in:\n$text") }
    }

    private fun assertSays(result: ImportResult, vararg lines: String) {
        val said = messages(result)
        lines.forEach {
            assertTrue(it in said, "expected <$it> among:\n${said.joinToString("\n")}")
        }
    }

    // ---- parameters beside a body ----

    @Test
    fun `a path parameter named like a body property takes the suffix and keeps its name`() {
        val r =
            importText(
                "shop.yaml" to
                    """
                    openapi: 3.0.3
                    info: {title: shop, version: 1.0.0}
                    paths:
                      /orders/{id}:
                        put:
                          operationId: updateOrder
                          parameters:
                            - {name: id, in: path, required: true, schema: {type: string}}
                          requestBody:
                            content:
                              application/json:
                                schema: {$REF: '#/components/schemas/Order'}
                          responses:
                            '204': {description: No content}
                    components:
                      schemas:
                        Order:
                          type: object
                          properties: {id: {type: string}, total: {type: integer, format: int64}}
                          additionalProperties: false
                    """
            )
        assertHas(
            text(r),
            "model UpdateOrderRequest { id2 string @openapi(name: \"id\")  id string?  total int64? }",
            "put \"/orders/{id2}\"",
        )
        assertFalse("@jsonschema(name: \"id\")" in text(r), text(r))
        assertSays(
            r,
            "SCH2402 shop.yaml: #/paths/~1orders~1{id}/put/parameters/0: parameter 'id' is imported as 'id2', since 'id' is taken",
        )
    }

    @Test
    fun `a query parameter sent in the body is dropped when the body has its property`() {
        val r =
            importText(
                "shop.yaml" to
                    """
                    openapi: 3.0.3
                    info: {title: shop, version: 1.0.0}
                    paths:
                      /orders:
                        post:
                          operationId: createOrder
                          parameters:
                            - {name: note, in: query, schema: {type: string}}
                            - {name: dryRun, in: query, schema: {type: boolean}}
                          requestBody:
                            content:
                              application/json:
                                schema: {$REF: '#/components/schemas/Order'}
                          responses:
                            '204': {description: No content}
                    components:
                      schemas:
                        Order:
                          type: object
                          properties: {note: {type: string}, dry_run: {type: string}}
                          additionalProperties: false
                    """
            )
        assertHas(
            text(r),
            "dry_run2 bool?   @jsonschema(name: \"dryRun\")",
            "note     string?",
            "dry_run  string?",
        )
        assertFalse("note2" in text(r), text(r))
        assertSays(
            r,
            "SCH2405 shop.yaml: #/paths/~1orders/post/parameters/0: query parameter 'note' dropped; the body has a property of that name",
            "SCH2402 shop.yaml: #/paths/~1orders/post/parameters/1: parameter 'dryRun' is imported as 'dry_run2', since 'dry_run' is taken",
        )
    }

    // ---- integer bounds and defaults ----

    @Test
    fun `integer bounds outside int64 are dropped and a default outside the type too`() {
        val r =
            importText(
                "shop.yaml" to
                    """
                    openapi: 3.0.3
                    info: {title: shop, version: 1.0.0}
                    paths: {}
                    components:
                      schemas:
                        Counter:
                          type: object
                          required: [big, small]
                          properties:
                            big: {type: integer, minimum: 0, maximum: 18446744073709551615}
                            small: {type: integer, format: int32, default: 3000000000}
                            huge: {type: integer, default: 99999999999999999999}
                          additionalProperties: false
                    """
            )
        assertHas(text(r), "model Counter { big int64 { min 0 }  small int32  huge int64? }")
        assertSays(
            r,
            "SCH2405 shop.yaml: #/components/schemas/Counter/properties/big: maximum 18446744073709551615 dropped; it lies outside int64",
            "SCH2405 shop.yaml: #/components/schemas/Counter/properties/small: default 3000000000 is dropped, since the field's type cannot hold it",
            "SCH2405 shop.yaml: #/components/schemas/Counter/properties/huge: default 99999999999999999999 is dropped, since the field's type cannot hold it",
        )
    }

    @Test
    fun `a fractional integer bound rounds inward and an exclusive one is exact`() {
        val r =
            importText(
                "shop.yaml" to
                    """
                    openapi: 3.0.3
                    info: {title: shop, version: 1.0.0}
                    paths: {}
                    components:
                      schemas:
                        Range:
                          type: object
                          required: [a, b, c]
                          properties:
                            a: {type: integer, minimum: 1.5, maximum: 9.5}
                            b: {type: integer, format: int32, minimum: 0, exclusiveMinimum: true, maximum: 10, exclusiveMaximum: true}
                            c: {type: integer, format: int64, minimum: 2.5, exclusiveMinimum: true}
                          additionalProperties: false
                    """
            )
        assertHas(
            text(r),
            "a int32 { min 2, max 9 }",
            "b int32 { min 1, max 9 }",
            "c int64 { min 3 }",
        )
        assertEquals(
            listOf(
                "SCH2403 shop.yaml: #/components/schemas/Range/properties/a: minimum 1.5 is imported as min 2",
                "SCH2403 shop.yaml: #/components/schemas/Range/properties/a: maximum 9.5 is imported as max 9",
            ),
            messages(r),
        )
    }

    @Test
    fun `a 3_1 exclusive integer bound is exact`() {
        val r =
            importText(
                "shop.json" to
                    """
                    {
                      "openapi": "3.1.0",
                      "info": {"title": "shop", "version": "1.0.0"},
                      "components": {
                        "schemas": {
                          "Range": {
                            "type": "object",
                            "properties": {"n": {"type": "integer", "format": "int32", "exclusiveMinimum": 0}},
                            "required": ["n"],
                            "additionalProperties": false
                          }
                        }
                      }
                    }
                    """
            )
        assertHas(text(r), "model Range { n int32 { min 1 } }")
        assertEquals(emptyList(), messages(r))
    }

    @Test
    fun `text the formatter refuses is an error, not a crash`() {
        val r =
            importText(
                "shop.yaml" to
                    """
                    openapi: 3.0.3
                    info: {title: shop, version: 1.0.0}
                    paths: {}
                    components:
                      schemas:
                        Note:
                          type: object
                          description: "a\0b"
                          properties: {text: {type: string}}
                    """
            )
        assertEquals(emptyList(), r.files)
        val errors = r.diagnostics.filter { it.severity == Severity.ERROR }
        assertEquals(listOf("SCH2401"), errors.map { it.code.id }, messages(r).toString())
        assertTrue(errors.single().message.startsWith("shop.yaml: "), errors.single().message)
    }

    // ---- bodies that are not components ----

    @Test
    fun `a body that is an alias of a model is that model`() {
        val r =
            importText(
                "shop.yaml" to
                    """
                    openapi: 3.0.3
                    info: {title: shop, version: 1.0.0}
                    paths:
                      /orders:
                        get:
                          operationId: getOrder
                          responses:
                            '200':
                              description: OK
                              content:
                                application/json:
                                  schema: {$REF: '#/components/schemas/OrderResponse'}
                    components:
                      schemas:
                        OrderResponse: {$REF: '#/components/schemas/Order'}
                        Order:
                          type: object
                          properties: {id: {type: string}}
                          additionalProperties: false
                    """
            )
        assertHas(text(r), "#1 get_order(): Order")
        assertFalse("items" in text(r), text(r))
        assertFalse(messages(r).any { "wrapped" in it }, messages(r).toString())
    }

    @Test
    fun `a body inside a component or in a file of its own is declared for the operation`() {
        val files =
            mapOf(
                "order.yaml" to
                    """
                    type: object
                    properties: {id: {type: string}}
                    additionalProperties: false
                    """
                        .trimIndent()
            )
        val r =
            importText(
                "shop.yaml" to
                    """
                    openapi: 3.0.3
                    info: {title: shop, version: 1.0.0}
                    paths:
                      /orders:
                        get:
                          operationId: getOrder
                          responses:
                            '200':
                              description: OK
                              content:
                                application/json:
                                  schema: {$REF: 'order.yaml'}
                        post:
                          operationId: createOrder
                          requestBody:
                            content:
                              application/json:
                                schema: {$REF: '#/components/schemas/Envelope/properties/order'}
                          responses:
                            '204': {description: No content}
                    components:
                      schemas:
                        Envelope:
                          type: object
                          properties:
                            order:
                              type: object
                              properties: {total: {type: integer, format: int64}}
                              additionalProperties: false
                          additionalProperties: false
                    """,
                locate = { path -> files[path]?.let { ImportInput(path, it) } },
            )
        assertHas(
            text(r),
            "model GetOrderResponse { id string? }",
            "model CreateOrderRequest { total int64? }",
            "#1 get_order(): GetOrderResponse",
            "#2 create_order(CreateOrderRequest)",
        )
        assertFalse("items" in text(r), text(r))
    }

    @Test
    fun `a path item reference is read from the file it names`() {
        val files =
            mapOf(
                "paths/orders.yaml" to
                    """
                    get:
                      operationId: listOrders
                      parameters:
                        - {name: page, in: query, schema: {type: integer, format: int32}}
                      responses:
                        '200':
                          description: OK
                          content:
                            application/json:
                              schema: {$REF: '../shop.yaml#/components/schemas/Order'}
                    """
                        .trimIndent()
            )
        val r =
            importText(
                "shop.yaml" to
                    """
                    openapi: 3.0.3
                    info: {title: shop, version: 1.0.0}
                    paths:
                      /orders: {$REF: 'paths/orders.yaml'}
                    components:
                      schemas:
                        Order:
                          type: object
                          properties: {id: {type: string}}
                          additionalProperties: false
                    """,
                locate = { path -> files[path]?.let { ImportInput(path, it) } },
            )
        assertHas(
            text(r),
            "model ListOrdersRequest { page int32? }",
            "#1 list_orders(ListOrdersRequest): Order  get \"/orders\"",
        )
        assertFalse(messages(r).any { "path item" in it }, messages(r).toString())
    }

    // ---- an allOf that annotates an enum or union reference ----

    @Test
    fun `an allOf part beside an enum reference annotates it`() {
        val r =
            importText(
                "shop.yaml" to
                    """
                    openapi: 3.0.3
                    info: {title: shop, version: 1.0.0}
                    paths: {}
                    components:
                      schemas:
                        Status: {type: string, enum: [PENDING, PAID]}
                        Order:
                          type: object
                          required: [a, c, d, e]
                          properties:
                            a: {allOf: [{$REF: '#/components/schemas/Status'}, {nullable: true}]}
                            b: {allOf: [{$REF: '#/components/schemas/Status'}, {default: PENDING}]}
                            c: {allOf: [{$REF: '#/components/schemas/Status'}, {deprecated: true}]}
                            d: {allOf: [{$REF: '#/components/schemas/Status'}, {readOnly: true}]}
                            e: {allOf: [{$REF: '#/components/schemas/Status'}, {example: PAID}]}
                          additionalProperties: false
                    """
            )
        assertHas(
            text(r),
            "model Order { a Status?  b Status = pending  c Status @deprecated  d Status  e Status }",
        )
        assertFalse("model OrderA" in text(r) || "jsonschema(open)" in text(r), text(r))
        assertSays(
            r,
            "SCH2405 shop.yaml: #/components/schemas/Order/properties/d/allOf/1: keyword 'readOnly' dropped",
            "SCH2405 shop.yaml: #/components/schemas/Order/properties/e/allOf/1: keyword 'example' dropped",
        )
    }

    // ---- a response named by its description ----

    @Test
    fun `a header response described by a reason phrase is named after its operation`() {
        val r =
            importText(
                "shop.yaml" to
                    """
                    openapi: 3.0.3
                    info: {title: shop, version: 1.0.0}
                    paths:
                      /orders:
                        post:
                          operationId: createOrder
                          responses:
                            '201':
                              description: Created
                              headers: {Location: {schema: {type: string}}}
                            '401':
                              description: Unauthorized
                              headers: {WWW-Authenticate: {schema: {type: string}}}
                      /things:
                        post:
                          operationId: createThing
                          responses:
                            '201':
                              description: Created
                              headers: {Location: {schema: {type: string}}}
                      /carts:
                        post:
                          operationId: createCart
                          responses:
                            '201':
                              description: CartCreated
                              headers: {Location: {schema: {type: string}}}
                    """
            )
        assertHas(
            text(r),
            "model CreateOrderResponse",
            "model CreateOrderError401",
            "model CreateThingResponse",
            "model CartCreated",
        )
        assertFalse("Created2" in text(r) || "model Created " in text(r), text(r))
    }

    // ---- unions in a form the target does not write ----

    @Test
    fun `a union that is not in the target's tagged form is noted`() {
        val r =
            importText(
                "shop.yaml" to
                    """
                    openapi: 3.0.3
                    info: {title: shop, version: 1.0.0}
                    paths: {}
                    components:
                      schemas:
                        Cat: {type: object, properties: {name: {type: string}}}
                        Dog: {type: object, properties: {name: {type: string}}}
                        AnyPet:
                          oneOf: [{$REF: '#/components/schemas/Cat'}, {$REF: '#/components/schemas/Dog'}]
                        Pet:
                          oneOf: [{$REF: '#/components/schemas/Cat'}, {$REF: '#/components/schemas/Dog'}]
                          discriminator: {propertyName: kind}
                        Animal:
                          type: object
                          properties: {kind: {type: string}}
                          discriminator: {propertyName: kind}
                    """
            )
        assertSays(
            r,
            "SCH2403 shop.yaml: #/components/schemas/AnyPet: union is imported in the externally tagged form, each member under its name; the document's untagged or in-band form is not kept",
            "SCH2403 shop.yaml: #/components/schemas/Pet: union is imported in the externally tagged form, each member under its name; the document's untagged or in-band form is not kept",
            "SCH2405 shop.yaml: #/components/schemas/Animal: keyword 'discriminator' dropped",
        )
    }

    // ---- webhooks and externalDocs ----

    @Test
    fun `webhooks and document and tag externalDocs are reported dropped`() {
        val r =
            importText(
                "shop.json" to
                    """
                    {
                      "openapi": "3.1.0",
                      "info": {"title": "shop", "version": "1.0.0"},
                      "externalDocs": {"url": "https://docs.example.com"},
                      "tags": [{"name": "Orders", "externalDocs": {"url": "https://docs.example.com/orders"}}],
                      "paths": {},
                      "webhooks": {
                        "orderCreated": {"post": {"responses": {"200": {"description": "OK"}}}}
                      }
                    }
                    """
            )
        assertSays(
            r,
            "SCH2405 shop.json: #/webhooks/orderCreated: webhook 'orderCreated' dropped; Schemata has no webhooks",
            "SCH2405 shop.json: #/externalDocs: externalDocs dropped",
            "SCH2405 shop.json: #/tags/0/externalDocs: externalDocs dropped",
        )
    }

    // ---- enum values ----

    @Test
    fun `an empty enum value is dropped`() {
        val r =
            importText(
                "shop.yaml" to
                    """
                    openapi: 3.0.3
                    info: {title: shop, version: 1.0.0}
                    paths: {}
                    components:
                      schemas:
                        Size: {type: string, enum: [small, large, '']}
                    """
            )
        assertHas(text(r), "enum Size { small large }")
        assertSays(
            r,
            "SCH2405 shop.yaml: #/components/schemas/Size/enum/2: enum value '' dropped; an enum value cannot be empty",
        )
    }

    // ---- nesting checked once every document is lowered ----

    @Test
    fun `a nested component an earlier document reaches waits for a later one to declare its parent`() {
        val common =
            """
            openapi: 3.0.3
            info: {title: common, version: 1.0.0}
            paths: {}
            components:
              schemas:
                Cart:
                  type: object
                  properties: {id: {type: string}}
                  additionalProperties: false
                common.Cart.Line:
                  type: object
                  properties: {sku: {type: string}}
                  additionalProperties: false
            """
                .trimIndent()
        fun doc(name: String, target: String) =
            """
            openapi: 3.0.3
            info: {title: $name, version: 1.0.0}
            paths:
              /$name:
                get:
                  operationId: get_$name
                  responses:
                    '200':
                      description: OK
                      content:
                        application/json:
                          schema: {$REF: 'common.yaml#/components/schemas/$target'}
            """
        val r =
            importText(
                "a.yaml" to doc("a", "common.Cart.Line"),
                "b.yaml" to doc("b", "Cart"),
                locate = { path -> if (path == "common.yaml") ImportInput(path, common) else null },
            )
        assertFalse(messages(r).any { "no component declares" in it }, messages(r).toString())
    }
}
