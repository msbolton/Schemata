package io.schemata.importer.openapi

import io.schemata.importer.ImportInput
import io.schemata.importer.ImportResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OperationLoweringTest {
    private fun importYaml(text: String): ImportResult =
        OpenApiImporter.import(
            listOf(ImportInput("shop.yaml", text.trimIndent(), relative = "shop.yaml"))
        )

    private fun text(result: ImportResult): String =
        result.files.single { it.path == "shop.schemata" }.content

    private fun messages(result: ImportResult): List<String> =
        result.diagnostics.map { "${it.code.id} ${it.message}" }

    @Test
    fun `operations go to the service of their first tag in tag then path then verb order`() {
        val r =
            importYaml(
                """
                openapi: 3.0.3
                info: {title: shop, version: 1.0.0}
                tags:
                  - name: Orders
                    description: Orders placed.
                  - name: Carts
                paths:
                  /orders/{id}:
                    delete:
                      tags: [Orders]
                      operationId: Orders_cancel
                      parameters:
                        - {name: id, in: path, required: true, schema: {type: string}}
                      responses: {'204': {description: No content}}
                    get:
                      tags: [Orders]
                      operationId: Orders_get
                      parameters:
                        - {name: id, in: path, required: true, schema: {type: string}}
                      responses:
                        '200':
                          description: Order
                          content:
                            application/json:
                              schema: {${'$'}ref: '#/components/schemas/Order'}
                  /carts:
                    post:
                      tags: [Carts, Orders]
                      operationId: Carts_create
                      responses:
                        '201': {description: Created}
                  /health:
                    get:
                      operationId: Shop_health
                      responses: {'204': {description: No content}}
                components:
                  schemas:
                    Order: {type: object, properties: {id: {type: string}}, required: [id], additionalProperties: false}
                """
            )
        assertEquals(
            """
            schema shop

            model Order { id string }

            model OrdersGetRequest { id string }

            model OrdersCancelRequest { id string }

            /// Orders placed.
            service Orders {
              #1 get(OrdersGetRequest): Order  get "/orders/{id}"
              #2 cancel(OrdersCancelRequest)  delete "/orders/{id}"
            }

            service Carts {
              #1 create()  post "/carts" 201
            }

            service Shop {
              #1 health()  get "/health"
            }
            """
                .trimIndent() + "\n",
            text(r),
        )
        assertEquals(emptyList(), messages(r))
    }

    @Test
    fun `a tag spelled otherwise keeps its spelling and its deprecation marker deprecates the service`() {
        val r =
            importYaml(
                """
                openapi: 3.0.3
                info: {title: shop, version: 1.0.0}
                tags:
                  - name: order-management
                    description: |-
                      Placing and following orders.

                      Deprecated.
                  - name: Customer Accounts
                paths:
                  /orders:
                    get:
                      tags: [order-management]
                      operationId: listOrders
                      deprecated: true
                      responses:
                        '200': {description: OK}
                """
            )
        assertEquals(
            """
            schema shop

            /// Placing and following orders.
            @deprecated
            @openapi(name: "order-management")
            service OrderManagement {
              @openapi(name: "listOrders") #1 list_orders()  get "/orders" 200
            }

            service CustomerAccounts {}
            """
                .trimIndent() + "\n",
            text(r),
        )
        assertEquals(
            listOf(
                "SCH2402 shop.yaml: #/tags/1: tag 'Customer Accounts' is imported as 'CustomerAccounts' with no @openapi(name), since the target cannot write it"
            ),
            messages(r),
        )
    }

    @Test
    fun `operation names come from the operation id or the verb and path`() {
        val r =
            importYaml(
                """
                openapi: 3.0.3
                info: {title: shop, version: 1.0.0}
                paths:
                  /orders:
                    get:
                      tags: [Orders]
                      operationId: Orders_list
                      responses: {'204': {description: No content}}
                    post:
                      tags: [Orders]
                      operationId: createOrder
                      responses: {'204': {description: No content}}
                  /orders/{id}/lines:
                    get:
                      tags: [Orders]
                      parameters:
                        - {name: id, in: path, required: true, schema: {type: string}}
                      responses: {'204': {description: No content}}
                  /orders/export:
                    get:
                      tags: [Orders]
                      operationId: export orders
                      responses: {'204': {description: No content}}
                    delete:
                      tags: [Orders]
                      operationId: list
                      responses: {'204': {description: No content}}
                """
            )
        assertEquals(
            """
            schema shop

            model OrdersGetOrdersIdLinesRequest { id string }

            service Orders {
              #1 list()  get "/orders"
              @openapi(name: "createOrder") #2 create_order()  post "/orders"
              #3 get_orders_id_lines(OrdersGetOrdersIdLinesRequest)  get "/orders/{id}/lines"
              #4 export_orders()  get "/orders/export"
              @openapi(name: "list") #5 list2()  delete "/orders/export"
            }
            """
                .trimIndent() + "\n",
            text(r),
        )
        assertEquals(
            listOf(
                "SCH2402 shop.yaml: #/paths/~1orders~1export/get/operationId: operationId 'export orders' is imported as 'export_orders' with no @openapi(name), since the target cannot write it",
                "SCH2402 shop.yaml: #/paths/~1orders~1export/delete/operationId: operation 'list' is imported as 'list2', since 'list' is taken",
            ),
            messages(r),
        )
    }

    @Test
    fun `a camel case path parameter keeps its wire name`() {
        val r =
            importYaml(
                """
                openapi: 3.0.3
                info: {title: shop, version: 1.0.0}
                paths:
                  /orders/{orderId}:
                    get:
                      tags: [Orders]
                      operationId: Orders_get
                      parameters:
                        - {name: orderId, in: path, required: true, schema: {type: string}}
                        - {name: pageSize, in: query, schema: {type: integer, format: int32}}
                      responses:
                        '200':
                          description: Order
                          content:
                            application/json:
                              schema: {${'$'}ref: '#/components/schemas/Order'}
                components:
                  schemas:
                    Order: {type: object, properties: {id: {type: string}}, required: [id], additionalProperties: false}
                """
            )
        assertEquals(
            """
            schema shop

            model Order { id string }

            model OrdersGetRequest {
              order_id  string @openapi(name: "orderId")
              page_size int32? @openapi(name: "pageSize")
            }

            service Orders {
              #1 get(OrdersGetRequest): Order  get "/orders/{order_id}"
            }
            """
                .trimIndent() + "\n",
            text(r),
        )
        assertEquals(emptyList(), messages(r))
    }

    @Test
    fun `path query and header parameters merge in that order with their docs defaults and deprecation`() {
        val r =
            importYaml(
                """
                openapi: 3.0.3
                info: {title: shop, version: 1.0.0}
                paths:
                  /orders/{id}/lines:
                    parameters:
                      - {name: id, in: path, required: true, description: The order., schema: {type: string}}
                    get:
                      tags: [Orders]
                      operationId: Orders_lines
                      parameters:
                        - ${'$'}ref: '#/components/parameters/Limit'
                        - {name: cursor, in: query, deprecated: true, schema: {type: string}}
                        - {name: X-Trace, in: header, required: true, schema: {type: string}}
                      responses: {'204': {description: No content}}
                components:
                  parameters:
                    Limit: {name: limit, in: query, schema: {type: integer, format: int32, default: 20}}
                """
            )
        assertEquals(
            """
            schema shop

            model OrdersLinesRequest {
              /// The order.
              id      string
              limit   int32   = 20
              cursor  string? @deprecated
              x_trace string  @openapi(header: "X-Trace")
            }

            service Orders {
              #1 lines(OrdersLinesRequest)  get "/orders/{id}/lines"
            }
            """
                .trimIndent() + "\n",
            text(r),
        )
        assertEquals(emptyList(), messages(r))
    }

    @Test
    fun `parameters and an inline body merge into one request`() {
        val r =
            importYaml(
                """
                openapi: 3.0.3
                info: {title: shop, version: 1.0.0}
                paths:
                  /orders/{orderId}/notes:
                    post:
                      tags: [Orders]
                      operationId: Orders_note
                      parameters:
                        - {name: orderId, in: path, required: true, schema: {type: string}}
                        - {name: Idempotency-Key, in: header, required: true, schema: {type: string}}
                      requestBody:
                        required: true
                        content:
                          application/json:
                            schema:
                              description: A note on an order.
                              type: object
                              properties:
                                text: {type: string}
                                pinned: {type: boolean}
                              required: [text]
                              additionalProperties: false
                      responses: {'204': {description: No content}}
                """
            )
        assertEquals(
            """
            schema shop

            /// A note on an order.
            model OrdersNoteRequest {
              order_id        string @openapi(name: "orderId")
              idempotency_key string @openapi(header: "Idempotency-Key")
              text            string
              pinned          bool?
            }

            service Orders {
              #1 note(OrdersNoteRequest)  post "/orders/{order_id}/notes"
            }
            """
                .trimIndent() + "\n",
            text(r),
        )
        assertEquals(emptyList(), messages(r))
    }

    @Test
    fun `a referenced body alone is the request and beside parameters is copied`() {
        val r =
            importYaml(
                """
                openapi: 3.0.3
                info: {title: shop, version: 1.0.0}
                paths:
                  /orders:
                    post:
                      tags: [Orders]
                      operationId: Orders_create
                      requestBody:
                        content:
                          application/json:
                            schema: {${'$'}ref: '#/components/schemas/NewOrder'}
                      responses:
                        '201':
                          description: Order
                          content:
                            application/json:
                              schema: {${'$'}ref: '#/components/schemas/Order'}
                  /orders/{id}:
                    put:
                      tags: [Orders]
                      operationId: Orders_replace
                      parameters:
                        - {name: id, in: path, required: true, schema: {type: string}}
                      requestBody:
                        content:
                          application/json:
                            schema: {${'$'}ref: '#/components/schemas/NewOrder'}
                      responses:
                        '200':
                          description: Order
                          content:
                            application/json:
                              schema: {${'$'}ref: '#/components/schemas/Order'}
                components:
                  schemas:
                    Order: {type: object, properties: {id: {type: string}}, required: [id], additionalProperties: false}
                    NewOrder: {type: object, properties: {note: {type: string}}, additionalProperties: false}
                """
            )
        assertEquals(
            """
            schema shop

            model Order { id string }

            model NewOrder { note string? }

            model OrdersReplaceRequest { id string  note string? }

            service Orders {
              #1 create(NewOrder): Order  post "/orders" 201
              #2 replace(OrdersReplaceRequest): Order  put "/orders/{id}"
            }
            """
                .trimIndent() + "\n",
            text(r),
        )
        assertEquals(
            listOf(
                "SCH2403 shop.yaml: #/paths/~1orders~1{id}/put/requestBody/content/application~1json/schema: 'NewOrder' is copied into 'OrdersReplaceRequest' to sit beside the parameters"
            ),
            messages(r),
        )
    }

    @Test
    fun `an array body or response is wrapped in a model with one field`() {
        val r =
            importYaml(
                """
                openapi: 3.0.3
                info: {title: shop, version: 1.0.0}
                paths:
                  /orders/batch:
                    post:
                      tags: [Orders]
                      operationId: Orders_batch
                      requestBody:
                        content:
                          application/json:
                            schema: {type: array, items: {${'$'}ref: '#/components/schemas/Order'}}
                      responses:
                        '200':
                          description: Orders
                          content:
                            application/json:
                              schema: {type: array, items: {${'$'}ref: '#/components/schemas/Order'}}
                components:
                  schemas:
                    Order: {type: object, properties: {id: {type: string}}, required: [id], additionalProperties: false}
                """
            )
        assertEquals(
            """
            schema shop

            model Order { id string }

            model OrdersBatchRequest { items Order[] }

            model OrdersBatchResponse { items Order[] }

            service Orders {
              #1 batch(OrdersBatchRequest): OrdersBatchResponse  post "/orders/batch"
            }
            """
                .trimIndent() + "\n",
            text(r),
        )
        assertEquals(
            listOf(
                "SCH2403 shop.yaml: #/paths/~1orders~1batch/post/requestBody/content/application~1json/schema: the request body is wrapped in 'OrdersBatchRequest' as field 'items', since it is not a model",
                "SCH2403 shop.yaml: #/paths/~1orders~1batch/post/responses/200/content/application~1json/schema: the body of response '200' is wrapped in 'OrdersBatchResponse' as field 'items', since it is not a model",
            ),
            messages(r),
        )
    }

    @Test
    fun `cookie and object parameters and bodies that are not json are dropped`() {
        val r =
            importYaml(
                """
                openapi: 3.0.3
                info: {title: shop, version: 1.0.0}
                paths:
                  /orders:
                    get:
                      tags: [Orders]
                      operationId: Orders_list
                      parameters:
                        - {name: session, in: cookie, schema: {type: string}}
                        - {name: filter, in: query, schema: {type: object, properties: {status: {type: string}}}}
                        - {name: q, in: query, schema: {type: string}}
                      responses:
                        '200':
                          description: Orders
                          content:
                            application/xml:
                              schema: {type: string}
                  /orders/upload:
                    post:
                      tags: [Orders]
                      operationId: Orders_upload
                      requestBody:
                        content:
                          multipart/form-data:
                            schema: {type: object, properties: {file: {type: string, format: binary}}}
                      responses: {'204': {description: No content}}
                """
            )
        assertEquals(
            """
            schema shop

            model OrdersListRequest { q string? }

            service Orders {
              #1 list(OrdersListRequest)  get "/orders" 200
              #2 upload()  post "/orders/upload"
            }
            """
                .trimIndent() + "\n",
            text(r),
        )
        assertEquals(
            listOf(
                "SCH2405 shop.yaml: #/paths/~1orders/get/parameters/0: cookie parameter 'session' dropped",
                "SCH2405 shop.yaml: #/paths/~1orders/get/parameters/1: parameter 'filter' dropped; a query parameter holds a scalar, an enum, or a list of them",
                "SCH2405 shop.yaml: #/paths/~1orders/get/responses/200/content/application~1xml: media type 'application/xml' dropped; only JSON bodies are read",
                "SCH2405 shop.yaml: #/paths/~1orders~1upload/post/requestBody/content/multipart~1form-data: media type 'multipart/form-data' dropped; only JSON bodies are read",
            ),
            messages(r),
        )
    }

    @Test
    fun `the success code is written only when the default rule would not give it`() {
        val r =
            importYaml(
                """
                openapi: 3.0.3
                info: {title: shop, version: 1.0.0}
                paths:
                  /a:
                    post:
                      operationId: Shop_a
                      responses:
                        '200': {description: OK}
                  /b:
                    post:
                      operationId: Shop_b
                      responses:
                        '202':
                          description: Order
                          content:
                            application/json:
                              schema: {${'$'}ref: '#/components/schemas/Order'}
                  /c:
                    get:
                      operationId: Shop_c
                      responses:
                        '200':
                          description: Order
                          content:
                            application/json:
                              schema: {${'$'}ref: '#/components/schemas/Order'}
                  /d:
                    delete:
                      operationId: Shop_d
                      responses: {'204': {description: No content}}
                components:
                  schemas:
                    Order: {type: object, properties: {id: {type: string}}, required: [id], additionalProperties: false}
                """
            )
        assertEquals(
            """
            schema shop

            model Order { id string }

            service Shop {
              #1 a()  post "/a" 200
              #2 b(): Order  post "/b" 202
              #3 c(): Order  get "/c"
              #4 d()  delete "/d"
            }
            """
                .trimIndent() + "\n",
            text(r),
        )
        assertEquals(emptyList(), messages(r))
    }

    @Test
    fun `the lowest success response is kept and the others are dropped or approximated`() {
        val r =
            importYaml(
                """
                openapi: 3.0.3
                info: {title: shop, version: 1.0.0}
                paths:
                  /a:
                    post:
                      operationId: Shop_a
                      responses:
                        '201': {description: Created}
                        '200':
                          description: Order
                          content:
                            application/json:
                              schema: {${'$'}ref: '#/components/schemas/Order'}
                        '302': {description: Found}
                  /b:
                    post:
                      operationId: Shop_b
                      responses:
                        '206':
                          description: Order
                          content:
                            application/json:
                              schema: {${'$'}ref: '#/components/schemas/Order'}
                  /c:
                    delete:
                      operationId: Shop_c
                      responses:
                        '204':
                          description: Gone
                          content:
                            application/json:
                              schema: {${'$'}ref: '#/components/schemas/Order'}
                  /d:
                    get:
                      operationId: Shop_d
                      responses:
                        default:
                          description: Problem
                          content:
                            application/json:
                              schema: {${'$'}ref: '#/components/schemas/Problem'}
                components:
                  schemas:
                    Order: {type: object, properties: {id: {type: string}}, required: [id], additionalProperties: false}
                    Problem: {type: object, properties: {title: {type: string}}, required: [title], additionalProperties: false}
                """
            )
        assertEquals(
            """
            schema shop

            model Order { id string }

            model Problem { title string }

            service Shop {
              #1 a(): Order  post "/a"
              #2 b(): Order  post "/b"
              #3 c()  delete "/c"
              #4 d()  get "/d"  errors { default Problem }
            }
            """
                .trimIndent() + "\n",
            text(r),
        )
        assertEquals(
            listOf(
                "SCH2405 shop.yaml: #/paths/~1a/post/responses/201: response '201' dropped; the success response is 200",
                "SCH2405 shop.yaml: #/paths/~1a/post/responses/302: response '302' dropped; Schemata writes no redirects",
                "SCH2403 shop.yaml: #/paths/~1b/post/responses/206: success response '206' is imported as 200",
                "SCH2405 shop.yaml: #/paths/~1c/delete/responses/204/content: content of response '204' dropped; a 204 has no body",
                "SCH2403 shop.yaml: #/paths/~1d/get/responses: the operation has no success response, so it is imported as a 204",
            ),
            messages(r),
        )
    }

    @Test
    fun `an event stream response and an ndjson request body are streams`() {
        val r =
            importYaml(
                """
                openapi: 3.0.3
                info: {title: shop, version: 1.0.0}
                paths:
                  /events:
                    get:
                      tags: [Events]
                      operationId: Events_watch
                      responses:
                        '200':
                          description: Stream of Event
                          content:
                            text/event-stream:
                              schema: {${'$'}ref: '#/components/schemas/Event'}
                  /events/batch:
                    post:
                      tags: [Events]
                      operationId: Events_publish
                      requestBody:
                        content:
                          application/x-ndjson:
                            schema: {${'$'}ref: '#/components/schemas/Event'}
                      responses: {'204': {description: No content}}
                components:
                  schemas:
                    Event: {type: object, properties: {name: {type: string}}, required: [name], additionalProperties: false}
                """
            )
        assertEquals(
            """
            schema shop

            model Event { name string }

            service Events {
              #1 watch(): stream Event  get "/events"
              #2 publish(stream Event)  post "/events/batch"
            }
            """
                .trimIndent() + "\n",
            text(r),
        )
        assertEquals(emptyList(), messages(r))
    }

    @Test
    fun `component parameters and responses resolve`() {
        val r =
            importYaml(
                """
                openapi: 3.0.3
                info: {title: shop, version: 1.0.0}
                paths:
                  /orders/{id}:
                    get:
                      tags: [Orders]
                      operationId: Orders_get
                      parameters:
                        - ${'$'}ref: '#/components/parameters/Id'
                      responses:
                        '200': {${'$'}ref: '#/components/responses/OrderFound'}
                        '404': {${'$'}ref: '#/components/responses/NotFound'}
                components:
                  parameters:
                    Id: {name: id, in: path, required: true, schema: {type: string}}
                  responses:
                    OrderFound:
                      description: Order
                      content:
                        application/json:
                          schema: {${'$'}ref: '#/components/schemas/Order'}
                    NotFound:
                      description: Not found
                      content:
                        application/problem+json:
                          schema: {${'$'}ref: '#/components/schemas/Problem'}
                  schemas:
                    Order: {type: object, properties: {id: {type: string}}, required: [id], additionalProperties: false}
                    Problem: {type: object, properties: {title: {type: string}}, required: [title], additionalProperties: false}
                """
            )
        assertEquals(
            """
            schema shop

            model Order { id string }

            model Problem { title string }

            model OrdersGetRequest { id string }

            service Orders {
              #1 get(OrdersGetRequest): Order  get "/orders/{id}"  errors { 404 Problem }
            }
            """
                .trimIndent() + "\n",
            text(r),
        )
        assertEquals(emptyList(), messages(r))
    }

    @Test
    fun `error responses become errors entries with inline bodies named after the operation`() {
        val r =
            importYaml(
                """
                openapi: 3.0.3
                info: {title: shop, version: 1.0.0}
                paths:
                  /orders:
                    post:
                      tags: [Orders]
                      operationId: createOrder
                      responses:
                        '201':
                          description: Order
                          content:
                            application/json:
                              schema: {${'$'}ref: '#/components/schemas/Order'}
                        default:
                          description: Error
                          content:
                            application/json:
                              schema: {${'$'}ref: '#/components/schemas/Problem'}
                        '422':
                          description: Invalid
                          content:
                            application/json:
                              schema:
                                type: object
                                properties: {field: {type: string}}
                                required: [field]
                                additionalProperties: false
                        '409': {description: Conflict}
                        4XX: {description: Client error}
                    get:
                      tags: [Orders]
                      operationId: listOrders
                      responses:
                        '200':
                          description: Order
                          content:
                            application/json:
                              schema: {${'$'}ref: '#/components/schemas/Order'}
                        '500': {description: Down}
                components:
                  schemas:
                    Order: {type: object, properties: {id: {type: string}}, required: [id], additionalProperties: false}
                    Problem: {type: object, properties: {title: {type: string}}, required: [title], additionalProperties: false}
                """
            )
        assertEquals(
            """
            schema shop

            model Order { id string }

            model Problem { title string }

            model CreateOrderError422 { field string }

            service Orders {
              @openapi(name: "listOrders") #1 list_orders(): Order  get "/orders"  errors { 500 }
              @openapi(name: "createOrder") #2 create_order(): Order  post "/orders" 201  errors {
                409
                422 CreateOrderError422
                default Problem
              }
            }
            """
                .trimIndent() + "\n",
            text(r),
        )
        assertEquals(
            listOf(
                "SCH2405 shop.yaml: #/paths/~1orders/post/responses/4XX: response '4XX' dropped; it is not a status code Schemata writes"
            ),
            messages(r),
        )
    }

    @Test
    fun `a code every operation of a service answers alike moves to the service clause`() {
        val r =
            importYaml(
                """
                openapi: 3.0.3
                info: {title: shop, version: 1.0.0}
                paths:
                  /orders:
                    get:
                      tags: [Orders]
                      operationId: Orders_list
                      responses:
                        '200':
                          description: Order
                          content:
                            application/json:
                              schema: {${'$'}ref: '#/components/schemas/Order'}
                        '401':
                          description: Problem
                          content:
                            application/json:
                              schema: {${'$'}ref: '#/components/schemas/Problem'}
                        '404':
                          description: Problem
                          content:
                            application/json:
                              schema: {${'$'}ref: '#/components/schemas/Problem'}
                  /orders/{id}:
                    get:
                      tags: [Orders]
                      operationId: Orders_get
                      parameters:
                        - {name: id, in: path, required: true, schema: {type: string}}
                      responses:
                        '200':
                          description: Order
                          content:
                            application/json:
                              schema: {${'$'}ref: '#/components/schemas/Order'}
                        '401':
                          description: Problem
                          content:
                            application/json:
                              schema: {${'$'}ref: '#/components/schemas/Problem'}
                        '404': {description: Not Found}
                components:
                  schemas:
                    Order: {type: object, properties: {id: {type: string}}, required: [id], additionalProperties: false}
                    Problem: {type: object, properties: {title: {type: string}}, required: [title], additionalProperties: false}
                """
            )
        assertEquals(
            """
            schema shop

            model Order { id string }

            model Problem { title string }

            model OrdersGetRequest { id string }

            service Orders {
              errors { 401 Problem }
              #1 list(): Order  get "/orders"  errors { 404 Problem }
              #2 get(OrdersGetRequest): Order  get "/orders/{id}"  errors { 404 }
            }
            """
                .trimIndent() + "\n",
            text(r),
        )
        assertEquals(emptyList(), messages(r))
    }

    @Test
    fun `response headers are header fields after the body fields`() {
        val r =
            importYaml(
                """
                openapi: 3.0.3
                info: {title: shop, version: 1.0.0}
                paths:
                  /orders:
                    post:
                      tags: [Orders]
                      operationId: Orders_place
                      responses:
                        '201':
                          description: Placed
                          headers:
                            Location:
                              description: The new order's URL.
                              required: true
                              schema: {type: string}
                            X-Rate-Limit:
                              schema: {type: integer, format: int32}
                          content:
                            application/json:
                              schema:
                                description: The order placed.
                                type: object
                                properties: {number: {type: string}}
                                required: [number]
                                additionalProperties: false
                        '429':
                          description: Too many requests
                          headers:
                            Retry-After: {required: true, schema: {type: integer, format: int32}}
                """
            )
        assertEquals(
            """
            schema shop

            /// The order placed.
            model Placed {
              number       string
              /// The new order's URL.
              location     string @openapi(header: "Location")
              x_rate_limit int32? @openapi(header: "X-Rate-Limit")
            }

            model OrdersPlaceError429 { retry_after int32 @openapi(header: "Retry-After") }

            service Orders {
              #1 place(): Placed  post "/orders" 201  errors { 429 OrdersPlaceError429 }
            }
            """
                .trimIndent() + "\n",
            text(r),
        )
        assertEquals(emptyList(), messages(r))
    }

    @Test
    fun `a referenced body beside response headers is copied`() {
        val r =
            importYaml(
                """
                openapi: 3.0.3
                info: {title: shop, version: 1.0.0}
                paths:
                  /orders/{id}:
                    get:
                      tags: [Orders]
                      operationId: Orders_get
                      parameters:
                        - {name: id, in: path, required: true, schema: {type: string}}
                      responses:
                        '200':
                          description: The order
                          headers:
                            ETag: {required: true, schema: {type: string}}
                          content:
                            application/json:
                              schema: {${'$'}ref: '#/components/schemas/Order'}
                components:
                  schemas:
                    Order: {type: object, properties: {id: {type: string}}, required: [id], additionalProperties: false}
                """
            )
        assertEquals(
            """
            schema shop

            model Order { id string }

            model OrdersGetRequest { id string }

            model OrdersGetResponse { id string  e_tag string @openapi(header: "ETag") }

            service Orders {
              #1 get(OrdersGetRequest): OrdersGetResponse  get "/orders/{id}"
            }
            """
                .trimIndent() + "\n",
            text(r),
        )
        assertEquals(
            listOf(
                "SCH2403 shop.yaml: #/paths/~1orders~1{id}/get/responses/200/content/application~1json/schema: 'Order' is copied into 'OrdersGetResponse' to sit beside the headers"
            ),
            messages(r),
        )
    }

    @Test
    fun `the summary and description are the doc and deprecated is the attribute`() {
        val r =
            importYaml(
                """
                openapi: 3.0.3
                info: {title: shop, version: 1.0.0}
                paths:
                  /orders:
                    get:
                      tags: [Orders]
                      operationId: Orders_list
                      summary: List the orders.
                      description: |
                        Newest first.

                        At most a hundred.
                      deprecated: true
                      responses: {'204': {description: No content}}
                """
            )
        assertEquals(
            """
            schema shop

            service Orders {
              /// List the orders.
              ///
              /// Newest first.
              ///
              /// At most a hundred.
              @deprecated #1 list()  get "/orders"
            }
            """
                .trimIndent() + "\n",
            text(r),
        )
        assertEquals(emptyList(), messages(r))
    }

    @Test
    fun `a get drops its body and a post sends its query parameters in the body`() {
        val r =
            importYaml(
                """
                openapi: 3.0.3
                info: {title: shop, version: 1.0.0}
                paths:
                  /search:
                    get:
                      operationId: Shop_search
                      requestBody:
                        content:
                          application/json:
                            schema: {${'$'}ref: '#/components/schemas/Order'}
                      responses: {'204': {description: No content}}
                  /orders:
                    post:
                      operationId: Shop_create
                      parameters:
                        - {name: dryRun, in: query, schema: {type: boolean}}
                      requestBody:
                        content:
                          application/json:
                            schema: {${'$'}ref: '#/components/schemas/Order'}
                      responses: {'204': {description: No content}}
                components:
                  schemas:
                    Order: {type: object, properties: {id: {type: string}}, required: [id], additionalProperties: false}
                """
            )
        assertEquals(
            """
            schema shop

            model Order { id string }

            model ShopCreateRequest { dry_run bool? @jsonschema(name: "dryRun")  id string }

            service Shop {
              #1 search()  get "/search"
              #2 create(ShopCreateRequest)  post "/orders"
            }
            """
                .trimIndent() + "\n",
            text(r),
        )
        assertEquals(
            listOf(
                "SCH2405 shop.yaml: #/paths/~1search/get/requestBody: request body dropped; get sends no body",
                "SCH2403 shop.yaml: #/paths/~1orders/post/parameters/0: query parameter 'dryRun' becomes a body field, since post sends no query parameters",
                "SCH2403 shop.yaml: #/paths/~1orders/post/requestBody/content/application~1json/schema: 'Order' is copied into 'ShopCreateRequest' to sit beside the parameters",
            ),
            messages(r),
        )
    }

    @Test
    fun `operations a binding cannot write are dropped`() {
        val r =
            importYaml(
                """
                openapi: 3.0.3
                info: {title: shop, version: 1.0.0}
                paths:
                  /orders:
                    trace:
                      operationId: Shop_trace
                      responses: {'204': {description: No content}}
                  /files/{name}.json:
                    get:
                      operationId: Shop_file
                      parameters:
                        - {name: name, in: path, required: true, schema: {type: string}}
                      responses: {'204': {description: No content}}
                  /orders/{ids}:
                    get:
                      operationId: Shop_many
                      parameters:
                        - {name: ids, in: path, required: true, schema: {type: array, items: {type: string}}}
                      responses: {'204': {description: No content}}
                  /health:
                    get:
                      operationId: Shop_health
                      responses: {'204': {description: No content}}
                """
            )
        assertEquals(
            """
            schema shop

            service Shop {
              #1 health()  get "/health"
            }
            """
                .trimIndent() + "\n",
            text(r),
        )
        assertEquals(
            listOf(
                "SCH2405 shop.yaml: #/paths/~1orders/trace: operation dropped; Schemata has no trace verb",
                "SCH2405 shop.yaml: #/paths/~1files~1{name}.json/get: operation dropped; its path is not one a binding can write",
                "SCH2405 shop.yaml: #/paths/~1orders~1{ids}/get/parameters/0: operation dropped; path parameter 'ids' is not a scalar or an enum",
            ),
            messages(r),
        )
    }

    @Test
    fun `a path parameter the operation does not declare is a string field`() {
        val r =
            importYaml(
                """
                openapi: 3.0.3
                info: {title: shop, version: 1.0.0}
                paths:
                  /orders/{id}:
                    get:
                      operationId: Shop_get
                      responses: {'204': {description: No content}}
                """
            )
        assertEquals(
            """
            schema shop

            model ShopGetRequest { id string }

            service Shop {
              #1 get(ShopGetRequest)  get "/orders/{id}"
            }
            """
                .trimIndent() + "\n",
            text(r),
        )
        assertEquals(
            listOf(
                "SCH2405 shop.yaml: paths.'/orders/{id}'. Declared path parameter id needs to be defined as a path parameter in path or operation level; ignored",
                "SCH2403 shop.yaml: #/paths/~1orders~1{id}/get: path parameter 'id' is not declared, so it is imported as a string",
            ),
            messages(r),
        )
    }

    @Test
    fun `headers openapi ignores or a header cannot hold are dropped`() {
        val r =
            importYaml(
                """
                openapi: 3.0.3
                info: {title: shop, version: 1.0.0}
                paths:
                  /orders:
                    get:
                      operationId: Shop_list
                      parameters:
                        - {name: Accept, in: header, schema: {type: string}}
                        - {name: X-Tags, in: header, schema: {type: array, items: {type: string}}}
                        - {name: X-Page, in: header, schema: {type: integer, format: int32}}
                      responses:
                        '200':
                          description: Page
                          headers:
                            Content-Type: {schema: {type: string}}
                            X-Total: {schema: {type: integer, format: int32}}
                """
            )
        assertEquals(
            """
            schema shop

            model ShopListRequest { x_page int32? @openapi(header: "X-Page") }

            model Page { x_total int32? @openapi(header: "X-Total") }

            service Shop {
              #1 list(ShopListRequest): Page  get "/orders"
            }
            """
                .trimIndent() + "\n",
            text(r),
        )
        assertEquals(
            listOf(
                "SCH2405 shop.yaml: #/paths/~1orders/get/parameters/0: header 'Accept' dropped; OpenAPI ignores a header of this name",
                "SCH2405 shop.yaml: #/paths/~1orders/get/parameters/1: header 'X-Tags' dropped; a header holds a scalar or an enum",
                "SCH2405 shop.yaml: #/paths/~1orders/get/responses/200/headers/Content-Type: header 'Content-Type' dropped; OpenAPI ignores a header of this name",
            ),
            messages(r),
        )
    }

    @Test
    fun `a service named like a model takes a suffix and keeps its tag`() {
        val r =
            importYaml(
                """
                openapi: 3.0.3
                info: {title: shop, version: 1.0.0}
                tags:
                  - name: Cart
                paths:
                  /cart:
                    get:
                      tags: [Cart]
                      operationId: Cart_get
                      responses:
                        '200':
                          description: Cart
                          content:
                            application/json:
                              schema: {${'$'}ref: '#/components/schemas/Cart'}
                components:
                  schemas:
                    Cart: {type: object, properties: {id: {type: string}}, required: [id], additionalProperties: false}
                """
            )
        assertEquals(
            """
            schema shop

            model Cart { id string }

            @openapi(name: "Cart")
            service Cart2 {
              #1 get(): Cart  get "/cart"
            }
            """
                .trimIndent() + "\n",
            text(r),
        )
        assertEquals(
            listOf(
                "SCH2402 shop.yaml: #/tags/0: tag 'Cart' is imported as 'Cart2', since 'Cart' is taken"
            ),
            messages(r),
        )
    }

    @Test
    fun `a reference to another kind of component or back to itself is an error`() {
        val r =
            importYaml(
                """
                openapi: 3.0.3
                info: {title: shop, version: 1.0.0}
                paths:
                  /orders:
                    get:
                      operationId: Shop_list
                      responses:
                        '200': {${'$'}ref: '#/components/schemas/Order'}
                        '404': {${'$'}ref: '#/components/responses/A'}
                components:
                  responses:
                    A: {${'$'}ref: '#/components/responses/B'}
                    B: {${'$'}ref: '#/components/responses/A'}
                  schemas:
                    Order: {type: object, properties: {id: {type: string}}, required: [id], additionalProperties: false}
                """
            )
        assertEquals(emptyList(), r.files)
        assertEquals(
            listOf(
                "SCH2405 shop.yaml: paths.'/orders'(get).responses.200.\$ref target #/components/schemas/Order is not of expected type Response; ignored",
                "SCH2401 shop.yaml: #/paths/~1orders/get/responses/200: reference '#/components/schemas/Order' does not name a response component",
                "SCH2401 shop.yaml: #/components/responses/B: reference '#/components/responses/A' refers back to itself",
            ),
            messages(r),
        )
    }

    @Test
    fun `callbacks links and operation servers are dropped`() {
        val r =
            importYaml(
                """
                openapi: 3.0.3
                info: {title: shop, version: 1.0.0}
                paths:
                  /orders:
                    post:
                      operationId: Shop_create
                      servers:
                        - url: https://other.example.com
                      callbacks:
                        done:
                          '{${'$'}request.body#/url}':
                            post:
                              responses: {'204': {description: No content}}
                      responses:
                        '204':
                          description: No content
                          links:
                            self: {operationId: Shop_create}
                """
            )
        assertEquals(
            """
            schema shop

            service Shop {
              #1 create()  post "/orders"
            }
            """
                .trimIndent() + "\n",
            text(r),
        )
        assertEquals(
            listOf(
                "SCH2405 shop.yaml: #/paths/~1orders/post/responses/204/links: links dropped",
                "SCH2405 shop.yaml: #/paths/~1orders/post/callbacks: callbacks dropped",
                "SCH2405 shop.yaml: #/paths/~1orders/post/servers: servers dropped",
            ),
            messages(r),
        )
    }

    private val common =
        """
        components:
          schemas:
            Money:
              type: object
              required: [amount]
              properties:
                amount: {type: integer, format: int64}
              additionalProperties: false
          parameters:
            Limit:
              name: limit
              in: query
              schema: {type: integer, format: int32, minimum: 1, maximum: 100, default: 20}
        """
            .trimIndent()

    /** Imports [text] as `shop.yaml`, with `common.yaml` beside it but not among the inputs. */
    private fun importWithCommon(text: String): ImportResult =
        OpenApiImporter.import(
            listOf(ImportInput("shop.yaml", text.trimIndent(), relative = "shop.yaml")),
            null,
        ) { path ->
            if (path == "common.yaml") ImportInput(path, common) else null
        }

    @Test
    fun `a body in a sibling file belongs to that file's schema`() {
        val r =
            importWithCommon(
                """
                openapi: 3.0.3
                info: {title: shop, version: 1.0.0}
                tags: [{name: Prices}]
                paths:
                  /prices:
                    get:
                      tags: [Prices]
                      operationId: Prices_get
                      responses:
                        '200':
                          description: Money
                          content:
                            application/json:
                              schema: {${'$'}ref: 'common.yaml#/components/schemas/Money'}
                """
            )
        assertEquals(emptyList(), messages(r).filter { it.startsWith("SCH2401") })
        val shop = text(r)
        assertTrue("import common" in shop, shop)
        assertTrue("#1 get(): common.Money  get \"/prices\"" in shop, shop)
        val common = r.files.single { it.path == "common.schemata" }.content
        assertTrue("model Money { amount int64 }" in common, common)
    }

    @Test
    fun `a parameter in a sibling file resolves from that file's tree`() {
        val r =
            importWithCommon(
                """
                openapi: 3.0.3
                info: {title: shop, version: 1.0.0}
                tags: [{name: Prices}]
                paths:
                  /prices:
                    get:
                      tags: [Prices]
                      operationId: Prices_list
                      parameters:
                        - ${'$'}ref: 'common.yaml#/components/parameters/Limit'
                      responses:
                        '204': {description: No content}
                """
            )
        // the parameter is read from common.yaml's tree: that file is no input, so its model is
        // empty
        assertEquals(emptyList(), messages(r).filter { it.startsWith("SCH2401") })
        val shop = text(r)
        assertTrue(
            Regex("""limit\s+int32\s+\{ min 1, max 100 \}\s+= 20""").containsMatchIn(shop),
            shop,
        )
        assertTrue("#1 list(PricesListRequest)  get \"/prices\"" in shop, shop)
    }

    @Test
    fun `a parameter name with a space is renamed with no openapi name`() {
        val r =
            importYaml(
                """
                openapi: 3.0.3
                info: {title: shop, version: 1.0.0}
                paths:
                  /orders:
                    get:
                      tags: [Orders]
                      operationId: Orders_list
                      parameters:
                        - {name: page size, in: query, schema: {type: integer, format: int32}}
                      responses: {'204': {description: No content}}
                """
            )
        assertEquals(
            """
            schema shop

            model OrdersListRequest { page_size int32? }

            service Orders {
              #1 list(OrdersListRequest)  get "/orders"
            }
            """
                .trimIndent() + "\n",
            text(r),
        )
        assertEquals(
            listOf(
                "SCH2402 shop.yaml: #/paths/~1orders/get/parameters/0: parameter 'page size' is imported as 'page_size' with no @openapi(name), since the target cannot write it"
            ),
            messages(r),
        )
    }

    @Test
    fun `a parameter name with unicode whitespace or a control character is renamed with no openapi name`() {
        val r =
            importYaml(
                """
                openapi: 3.0.3
                info: {title: shop, version: 1.0.0}
                paths:
                  /orders:
                    get:
                      tags: [Orders]
                      operationId: Orders_list
                      parameters:
                        - {name: "page\u2003size", in: query, schema: {type: integer, format: int32}}
                        - {name: "next\x85page", in: query, schema: {type: string}}
                      responses: {'204': {description: No content}}
                """
            )
        assertEquals(
            """
            schema shop

            model OrdersListRequest { page_size int32?  next_page string? }

            service Orders {
              #1 list(OrdersListRequest)  get "/orders"
            }
            """
                .trimIndent() + "\n",
            text(r),
        )
        assertEquals(
            listOf(
                "SCH2402 shop.yaml: #/paths/~1orders/get/parameters/0: parameter 'page\u2003size' is imported as 'page_size' with no @openapi(name), since the target cannot write it",
                "SCH2402 shop.yaml: #/paths/~1orders/get/parameters/1: parameter 'next\u0085page' is imported as 'next_page' with no @openapi(name), since the target cannot write it",
            ),
            messages(r),
        )
    }

    @Test
    fun `models of an operation without a valid id are named after the id the target writes`() {
        val r =
            importYaml(
                """
                openapi: 3.0.3
                info: {title: shop, version: 1.0.0}
                tags: [{name: order list}]
                paths:
                  /orders/{id}:
                    get:
                      tags: [order list]
                      parameters:
                        - {name: id, in: path, required: true, schema: {type: string}}
                      responses: {'204': {description: No content}}
                    delete:
                      tags: [order list]
                      operationId: cancel order
                      parameters:
                        - {name: id, in: path, required: true, schema: {type: string}}
                      responses: {'204': {description: No content}}
                """
            )
        val shop = text(r)
        assertTrue("model OrderListGetOrdersIdRequest { id string }" in shop, shop)
        assertTrue("model OrderListCancelOrderRequest { id string }" in shop, shop)
    }

    @Test
    fun `an alias component as a response body is wrapped`() {
        val r =
            importYaml(
                """
                openapi: 3.0.3
                info: {title: shop, version: 1.0.0}
                paths:
                  /orders:
                    get:
                      operationId: Shop_get
                      responses:
                        '200':
                          description: Order
                          content:
                            application/json:
                              schema: {${'$'}ref: '#/components/schemas/Alias'}
                components:
                  schemas:
                    Alias: {${'$'}ref: '#/components/schemas/Order'}
                    Order: {type: object, properties: {id: {type: string}}, required: [id], additionalProperties: false}
                """
            )
        assertEquals(
            """
            schema shop

            model Order { id string }

            model ShopGetResponse { items Order }

            service Shop {
              #1 get(): ShopGetResponse  get "/orders"
            }
            """
                .trimIndent() + "\n",
            text(r),
        )
        assertEquals(
            listOf(
                "SCH2403 shop.yaml: #/components/schemas/Alias: component 'Alias' is not a model, enum or union; each use takes its type",
                "SCH2403 shop.yaml: #/paths/~1orders/get/responses/200/content/application~1json/schema: the body of response '200' is wrapped in 'ShopGetResponse' as field 'items', since it is not a model",
            ),
            messages(r),
        )
    }

    @Test
    fun `a request body naming a schema inside a component is wrapped`() {
        val r =
            importYaml(
                """
                openapi: 3.0.3
                info: {title: shop, version: 1.0.0}
                paths:
                  /addresses:
                    post:
                      operationId: Shop_create
                      requestBody:
                        content:
                          application/json:
                            schema: {${'$'}ref: '#/components/schemas/Order/properties/address'}
                      responses: {'204': {description: No content}}
                components:
                  schemas:
                    Order:
                      type: object
                      properties:
                        address:
                          type: object
                          properties: {city: {type: string}}
                          required: [city]
                          additionalProperties: false
                      required: [address]
                      additionalProperties: false
                """
            )
        val shop = text(r)
        assertTrue("#1 create(ShopCreateRequest)  post \"/addresses\"" in shop, shop)
        assertEquals(
            listOf(
                "SCH2403 shop.yaml: #/paths/~1addresses/post/requestBody/content/application~1json/schema: the request body is wrapped in 'ShopCreateRequest' as field 'items', since it is not a model"
            ),
            messages(r),
        )
    }

    @Test
    fun `a closed object component without properties is a body by name`() {
        val r =
            importYaml(
                """
                openapi: 3.0.3
                info: {title: shop, version: 1.0.0}
                paths:
                  /ping:
                    get:
                      operationId: Shop_ping
                      responses:
                        '200':
                          description: Empty
                          content:
                            application/json:
                              schema: {${'$'}ref: '#/components/schemas/Empty'}
                components:
                  schemas:
                    Empty: {type: object, additionalProperties: false}
                """
            )
        assertEquals(
            """
            schema shop

            model Empty {}

            service Shop {
              #1 ping(): Empty  get "/ping"
            }
            """
                .trimIndent() + "\n",
            text(r),
        )
        assertEquals(emptyList(), messages(r))
    }

    @Test
    fun `a model made for an operation does not take a service's name`() {
        val r =
            importYaml(
                """
                openapi: 3.0.3
                info: {title: shop, version: 1.0.0}
                tags: [{name: Orders}]
                paths:
                  /orders:
                    get:
                      tags: [Orders]
                      operationId: Orders_list
                      responses:
                        '200':
                          description: Orders
                          headers:
                            X-Total: {required: true, schema: {type: integer, format: int32}}
                """
            )
        assertEquals(
            """
            schema shop

            model Orders2 { x_total int32 @openapi(header: "X-Total") }

            service Orders {
              #1 list(): Orders2  get "/orders"
            }
            """
                .trimIndent() + "\n",
            text(r),
        )
        assertEquals(
            listOf(
                "SCH2402 shop.yaml: #/paths/~1orders/get/responses/200: model 'Orders' is imported as 'Orders2', since 'Orders' is taken"
            ),
            messages(r),
        )
    }
}
