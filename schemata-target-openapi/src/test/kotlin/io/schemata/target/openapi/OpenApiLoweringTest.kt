package io.schemata.target.openapi

import io.schemata.core.AnalysisOptions
import io.schemata.core.Analyzer
import io.schemata.core.annotations.AnnotationRegistry
import io.schemata.core.annotations.CoreAnnotations
import io.schemata.core.ir.Schema
import io.schemata.core.ir.Verb
import io.schemata.lang.Parser
import io.schemata.target.Lowered
import io.schemata.target.json.JsonNumber
import io.schemata.target.jsonschema.ArraySchema
import io.schemata.target.jsonschema.Common
import io.schemata.target.jsonschema.EnumSchema
import io.schemata.target.jsonschema.JsonSchema
import io.schemata.target.jsonschema.JsonSchemaAnnotations
import io.schemata.target.jsonschema.JsonSchemaTypes
import io.schemata.target.jsonschema.MapSchema
import io.schemata.target.jsonschema.ObjectSchema
import io.schemata.target.jsonschema.RefSchema
import io.schemata.target.jsonschema.ScalarSchema
import io.schemata.target.jsonschema.TaggedUnionSchema
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal const val ORDERS =
    """
/// Orders and their lines.
schema shop.orders

model OrderId { #1 id uuid }

model ListOrders { #1 status Status?  #2 limit int32 { min 1, max 200 } = 50 }

model PlaceOrder { #1 customer_id uuid  #2 lines Order.Line[] { minItems 1 } }

model Order {
  #1 id     uuid
  #2 status Status
  #3 lines  Line[]
  #4 total  Money

  model Line { #1 sku string { max 64 }  #2 quantity int32 { min 1 } }
}

model Money { #1 amount decimal(19, 4) }

enum Status { pending paid shipped cancelled }

model Chunk { #1 bytes bytes }

model Receipt { #1 count int64 }

/// Place and read orders.
service Orders {
  /// Fetch one order.
  #1 get(OrderId): Order  get "/orders/{id}"
  /// Orders matching a filter, newest first.
  #2 list(ListOrders): stream Order  get "/orders"
  #3 place(PlaceOrder): Order  post "/orders"
  #4 cancel(OrderId)  delete "/orders/{id}"
  #5 upload(stream Chunk): Receipt
  reserved #6, "archive"
}
"""

/** Analyses [sources], one file each, with every annotation the OpenAPI lowering reads. */
internal fun compile(vararg sources: String): Schema {
    val files =
        sources.mapIndexed { i, text -> Parser.parse(text.trimIndent(), "f$i.schemata").file!! }
    val analysis =
        Analyzer.analyze(
            files,
            AnalysisOptions(
                annotations =
                    AnnotationRegistry(
                        CoreAnnotations.specs +
                            JsonSchemaAnnotations.specs +
                            OpenApiAnnotations.specs
                    )
            ),
        )
    assertEquals(emptyList(), analysis.diagnostics.map { "${it.code.id} ${it.message}" })
    return analysis.schema!!
}

private const val RELATIONS =
    """
schema shop

model Customer { #1 id uuid { id }  #2 name string }

model Tag { #1 code string { id, max 16 } }

model Pair { #1 a int32 { id }  #2 b int32 { id } }

model Order {
  #1 id       uuid     { id }
  #2 customer Customer
  #3 billing  Customer { embed }
  #4 tags     Tag[]
  #5 pair     Pair
  #6 pairs    Pair[]
  #7 backup   Customer?
}

model ByCustomer { #1 customer Customer }

service Orders {
  #1 get(Order): Order  post "/orders"
  #2 list(ByCustomer): Order  get "/orders"
}
"""

private const val MEMBERS =
    """
schema shop

model Customer { #1 id uuid { id } }

model Pair { #1 a int32 { id }  #2 b int32 { id } }

union Party = Customer | Pair

model Book {
  #1 id    uuid { id }
  #2 by    map<string, Customer>
  #3 pairs map<string, Pair>
  #4 party Party
}

service Books { #1 get(Book): Book  post "/books" }
"""

class OpenApiLoweringTest {
    private fun lower(vararg sources: String): Lowered<OpenApiModel> =
        OpenApiLowering.lower(compile(*sources))

    private fun messages(lowered: Lowered<OpenApiModel>): List<String> =
        lowered.diagnostics.map { "${it.code.id} ${it.message}" }

    private val uuid =
        ScalarSchema("string", format = "uuid", pattern = JsonSchemaTypes.UUID_PATTERN)

    @Test
    fun `paths parameters bodies and responses follow the verb`() {
        val lowered = lower(ORDERS)
        val doc = lowered.model.documents.single()
        assertEquals("shop/orders.openapi.json", doc.path)
        assertEquals("shop.orders", doc.title)
        assertEquals("1.0.0", doc.version)
        assertEquals("Orders and their lines.", doc.description)
        assertNull(doc.server)
        assertEquals(listOf(Tag("Orders", "Place and read orders.")), doc.tags)
        assertEquals(listOf("/orders/{id}", "/orders", "/Orders/upload"), doc.paths.map { it.path })

        val get = doc.paths[0].operations.single { it.verb == Verb.GET }
        assertEquals("Orders_get", get.operationId)
        assertEquals("Orders", get.tag)
        assertEquals("Fetch one order.", get.summary)
        assertNull(get.description)
        assertFalse(get.deprecated)
        assertEquals(
            listOf(Parameter("id", "path", true, uuid, null, false, style = false)),
            get.parameters,
        )
        assertNull(get.requestBody)
        assertEquals(
            Response.Content(
                200,
                "Order",
                "application/json",
                RefSchema("#/components/schemas/shop.orders.Order", Common()),
            ),
            get.response,
        )

        val delete = doc.paths[0].operations.single { it.verb == Verb.DELETE }
        assertEquals("Orders_cancel", delete.operationId)
        assertEquals(Response.Empty("No content"), delete.response)
        assertEquals(listOf(Verb.GET, Verb.DELETE), doc.paths[0].operations.map { it.verb })

        val list = doc.paths[1].operations.single { it.verb == Verb.GET }
        assertEquals(listOf("status", "limit"), list.parameters.map { it.name })
        assertEquals(listOf("query", "query"), list.parameters.map { it.location })
        assertEquals(listOf(false, false), list.parameters.map { it.required })
        assertEquals(listOf(true, true), list.parameters.map { it.style })
        assertEquals(
            RefSchema("#/components/schemas/shop.orders.Status", Common(nullable = true)),
            list.parameters[0].schema,
        )
        assertEquals(
            ScalarSchema(
                "integer",
                minimum = BigDecimal(1),
                maximum = BigDecimal(200),
                common = Common(default = JsonNumber("50")),
            ),
            list.parameters[1].schema,
        )
        assertEquals(
            Response.Content(
                200,
                "Stream of Order",
                "text/event-stream",
                RefSchema("#/components/schemas/shop.orders.Order", Common()),
            ),
            list.response,
        )

        val place = doc.paths[1].operations.single { it.verb == Verb.POST }
        assertEquals(
            Body(
                "application/json",
                RefSchema("#/components/schemas/shop.orders.PlaceOrder", Common()),
            ),
            place.requestBody,
        )
        assertTrue(place.parameters.isEmpty())

        val upload = doc.paths[2].operations.single()
        assertEquals(Verb.POST, upload.verb)
        assertEquals("Orders_upload", upload.operationId)
        assertEquals(
            Body(
                "application/x-ndjson",
                RefSchema("#/components/schemas/shop.orders.Chunk", Common()),
            ),
            upload.requestBody,
        )
        assertEquals(
            listOf(
                "shop.orders.Order",
                "shop.orders.Order.Line",
                "shop.orders.Status",
                "shop.orders.Money",
                "shop.orders.PlaceOrder",
                "shop.orders.Chunk",
                "shop.orders.Receipt",
            ),
            doc.components.map { it.key },
        )
        assertEquals(emptyList(), messages(lowered))
    }

    @Test
    fun `a body with path parameters is the remaining fields`() {
        val l =
            lower(
                "schema t\n" +
                    "\n" +
                    "model Up { #1 id uuid  #2 name string  #3 note string? }\n" +
                    "\n" +
                    "model R { #1 ok bool }\n" +
                    "\n" +
                    "service S {\n" +
                    "  #1 update(Up): R  put \"/items/{id}\"\n" +
                    "}"
            )
        val op = l.model.documents.single().paths.single().operations.single()
        assertEquals(listOf("id"), op.parameters.map { it.name })
        assertEquals("application/json", op.requestBody!!.mediaType)
        val body = op.requestBody!!.schema as ObjectSchema
        assertEquals(listOf("name", "note"), body.properties.map { it.name })
        assertEquals(listOf(true, false), body.properties.map { it.required })
        assertEquals(listOf("t.R"), l.model.documents.single().components.map { it.key })
        assertEquals(emptyList(), messages(l))
    }

    @Test
    fun `a partial body pulls in what its fields reference`() {
        val l =
            lower(
                "schema t\n" +
                    "\n" +
                    "model Up { #1 id uuid  #2 tags Tag[] }\n" +
                    "\n" +
                    "enum Tag { a b }\n" +
                    "\n" +
                    "service S {\n" +
                    "  #1 update(Up)  patch \"/items/{id}\"\n" +
                    "}"
            )
        val doc = l.model.documents.single()
        val op = doc.paths.single().operations.single()
        assertEquals(Response.Empty("No content"), op.response)
        val body = op.requestBody!!.schema as ObjectSchema
        assertEquals(
            ArraySchema(RefSchema("#/components/schemas/t.Tag", Common())),
            body.properties.single().schema,
        )
        assertEquals(listOf("t.Tag"), doc.components.map { it.key })
    }

    @Test
    fun `a body verb with every field bound has no body`() {
        val l =
            lower(
                "schema t\n" +
                    "\n" +
                    "model Id { #1 id uuid }\n" +
                    "\n" +
                    "service S {\n" +
                    "  #1 touch(Id)  post \"/items/{id}\"\n" +
                    "}"
            )
        val doc = l.model.documents.single()
        val op = doc.paths.single().operations.single()
        assertNull(op.requestBody)
        assertEquals(emptyList(), doc.components)
    }

    @Test
    fun `an operation without a request has no parameters and no body`() {
        val l = lower("schema t\n\nmodel R { #1 ok bool }\n\nservice S {\n  #1 ping(): R\n}")
        val op = l.model.documents.single().paths.single().operations.single()
        assertEquals("/S/ping", l.model.documents.single().paths.single().path)
        assertTrue(op.parameters.isEmpty())
        assertNull(op.requestBody)
    }

    @Test
    fun `a union request is a body of its component`() {
        val l =
            lower(
                "schema t\n" +
                    "\n" +
                    "model A { #1 a string }\n" +
                    "\n" +
                    "model B { #1 b B2 }\n" +
                    "\n" +
                    "model B2 { #1 x int32 }\n" +
                    "\n" +
                    "union U = #1 A | #2 B\n" +
                    "\n" +
                    "service S {\n" +
                    "  #1 send(U)  post \"/send\"\n" +
                    "}"
            )
        val doc = l.model.documents.single()
        val op = doc.paths.single().operations.single()
        assertEquals(
            Body("application/json", RefSchema("#/components/schemas/t.U", Common())),
            op.requestBody,
        )
        assertEquals(listOf("t.U", "t.A", "t.B", "t.B2"), doc.components.map { it.key })
    }

    @Test
    fun `a union request on a parameter verb is refused`() {
        val l =
            lower(
                "schema t\n" +
                    "\n" +
                    "model A { #1 a string }\n" +
                    "\n" +
                    "model B { #1 b string }\n" +
                    "\n" +
                    "union U = #1 A | #2 B\n" +
                    "\n" +
                    "model R { #1 ok bool }\n" +
                    "\n" +
                    "service S {\n" +
                    "  #1 find(U): R  get \"/find\"\n" +
                    "}"
            )
        assertEquals(
            listOf(
                "SCH2601 operation 'find': union 'U' cannot be query parameters; use a body verb"
            ),
            messages(l),
        )
        assertEquals("request a model, or use post, put, or patch", l.diagnostics.single().help)
        val doc = l.model.documents.single()
        val op = doc.paths.single().operations.single()
        assertNull(op.requestBody)
        assertTrue(op.parameters.isEmpty())
        assertEquals(listOf("t.R"), doc.components.map { it.key })
    }

    @Test
    fun `query parameters refuse structured fields`() {
        val l =
            lower(
                "schema t\n" +
                    "\n" +
                    "model F { #1 q string  #2 inner R  #3 tags string[]  #4 rs R[] }\n" +
                    "\n" +
                    "model R { #1 ok bool }\n" +
                    "\n" +
                    "service S {\n" +
                    "  #1 find(F): R  get \"/find\"\n" +
                    "}"
            )
        assertEquals(
            listOf(
                "SCH2601 operation 'find': field 'inner' cannot be a query parameter",
                "SCH2601 operation 'find': field 'rs' cannot be a query parameter",
            ),
            messages(l),
        )
    }

    @Test
    fun `query parameters refuse maps`() {
        val l =
            lower(
                "schema t\n" +
                    "\n" +
                    "model F { #1 m map<string, string> }\n" +
                    "\n" +
                    "model R { #1 ok bool }\n" +
                    "\n" +
                    "service S {\n" +
                    "  #1 find(F): R  get \"/find\"\n" +
                    "}"
            )
        assertEquals(
            listOf("SCH2601 operation 'find': field 'm' cannot be a query parameter"),
            messages(l),
        )
    }

    @Test
    fun `collisions overrides and derived paths`() {
        val l =
            lower(
                "schema t\n" +
                    "\n" +
                    "model R { #1 ok bool }\n" +
                    "\n" +
                    "service S {\n" +
                    "  @openapi(name: \"S_a\") #1 b(R): R\n" +
                    "  @openapi(name: \"a b\") #2 a(R): R\n" +
                    "  #3 s_b(): R\n" +
                    "}\n" +
                    "\n" +
                    "service Other {\n" +
                    "  #1 x(): R  post \"/S/s_b\"\n" +
                    "}"
            )
        assertEquals(
            listOf(
                "SCH2602 operations 'S.s_b' and 'Other.x' both lower to post \"/S/s_b\"",
                "SCH2602 operations 'b' and 'a' both lower to operationId 'S_a'",
                "SCH2603 operation 'a': @openapi(name: \"a b\") is not a valid operationId",
            ),
            messages(l).sorted(),
        )
    }

    @Test
    fun `paths that differ only in parameter names collide whatever their verbs`() {
        val l =
            lower(
                "schema t\n" +
                    "\n" +
                    "model Id { #1 id uuid  #2 order_id uuid }\n" +
                    "\n" +
                    "model R { #1 ok bool }\n" +
                    "\n" +
                    "service S {\n" +
                    "  #1 get(Id): R  get \"/orders/{id}\"\n" +
                    "  #2 put(Id): R  put \"/orders/{id}\"\n" +
                    "  #3 cancel(Id)  delete \"/orders/{order_id}\"\n" +
                    "}\n" +
                    "\n" +
                    "service Other {\n" +
                    "  #1 x(Id): R  post \"/orders/{order_id}\"\n" +
                    "}"
            )
        assertEquals(
            listOf(
                "SCH2602 operations 'get' and 'cancel' bind \"/orders/{id}\" and \"/orders/{order_id}\", which differ only in their parameter names",
                "SCH2602 operations 'S.get' and 'Other.x' bind \"/orders/{id}\" and \"/orders/{order_id}\", which differ only in their parameter names",
            ),
            messages(l),
        )
        val item = l.model.documents.single().paths.single()
        assertEquals("/orders/{id}", item.path)
        assertEquals(listOf(Verb.GET, Verb.PUT), item.operations.map { it.verb })
    }

    @Test
    fun `a route collision suggests another path or verb and a template clash another path`() {
        val l =
            lower(
                "schema t\n" +
                    "\n" +
                    "model Id { #1 id uuid  #2 order_id uuid }\n" +
                    "\n" +
                    "model R { #1 ok bool }\n" +
                    "\n" +
                    "service S {\n" +
                    "  #1 a(Id): R  get \"/orders/{id}\"\n" +
                    "  #2 b(Id): R  delete \"/orders/{order_id}\"\n" +
                    "  #3 s_d(): R\n" +
                    "}\n" +
                    "\n" +
                    "service Other {\n" +
                    "  #1 x(): R  post \"/S/s_d\"\n" +
                    "}"
            )
        assertEquals(
            listOf(
                "bind one of them to another path or verb",
                "give both paths the same parameter names, or bind one of them to another path",
            ),
            l.diagnostics.mapNotNull { it.help }.sorted(),
        )
    }

    @Test
    fun `an operation dropped by a route collision leaves its operationId free`() {
        val l =
            lower(
                "schema t\n" +
                    "\n" +
                    "model R { #1 ok bool }\n" +
                    "\n" +
                    "service S {\n" +
                    "  #1 s_b(): R\n" +
                    "}\n" +
                    "\n" +
                    "service Other {\n" +
                    "  @openapi(name: \"Z\") #1 x(): R  post \"/S/s_b\"\n" +
                    "  @openapi(name: \"Z\") #2 y(): R  post \"/y\"\n" +
                    "}"
            )
        assertEquals(
            listOf("SCH2602 operations 'S.s_b' and 'Other.x' both lower to post \"/S/s_b\""),
            messages(l),
        )
        val doc = l.model.documents.single()
        assertEquals(listOf("/S/s_b", "/y"), doc.paths.map { it.path })
        assertEquals(listOf("S_s_b", "Z"), doc.paths.map { it.operations.single().operationId })
    }

    @Test
    fun `declarations that share a component key and a field name are reported once`() {
        val l =
            lower(
                "schema t\n" +
                    "\n" +
                    "@jsonschema(name: \"B\")\n" +
                    "model A { #1 x int32 }\n" +
                    "\n" +
                    "model B { #1 x int32 }\n" +
                    "\n" +
                    "service S {\n" +
                    "  #1 one(A): B  post \"/one\"\n" +
                    "}"
            )
        assertEquals(
            listOf(
                "SCH2602 model 'B' lowers to \$defs key 't.B', already used by model 'A' (f0.schemata:4)"
            ),
            messages(l),
        )
    }

    @Test
    fun `a nested declaration reached before its parent is filed once`() {
        val l =
            lower(
                "schema t\n" +
                    "\n" +
                    "model Order {\n" +
                    "  #1 line Line\n" +
                    "\n" +
                    "  model Line { #1 n int32 }\n" +
                    "}\n" +
                    "\n" +
                    "service S {\n" +
                    "  #1 one(Order.Line): Order  post \"/one\"\n" +
                    "}"
            )
        assertEquals(emptyList(), messages(l))
        assertEquals(
            listOf("t.Order.Line", "t.Order"),
            l.model.documents.single().components.map { it.key },
        )
    }

    @Test
    fun `head and options take their fields as query parameters and no body`() {
        val l =
            lower(
                "schema t\n" +
                    "\n" +
                    "model Q { #1 id uuid  #2 limit int32? }\n" +
                    "\n" +
                    "service S {\n" +
                    "  #1 probe(Q)  head \"/items/{id}\"\n" +
                    "  #2 allowed(Q)  options \"/items/{id}\"\n" +
                    "}"
            )
        assertEquals(emptyList(), messages(l))
        val item = l.model.documents.single().paths.single()
        assertEquals(listOf(Verb.HEAD, Verb.OPTIONS), item.operations.map { it.verb })
        item.operations.forEach {
            assertEquals(listOf("path", "query"), it.parameters.map { p -> p.location })
            assertNull(it.requestBody)
        }
    }

    @Test
    fun `services may share operation names and each id carries its own tag`() {
        val l =
            lower(
                "schema t\n" +
                    "\n" +
                    "model R { #1 ok bool }\n" +
                    "\n" +
                    "service A { #1 get(): R }\n" +
                    "\n" +
                    "service B { #1 get(): R }"
            )
        assertEquals(emptyList(), messages(l))
        val doc = l.model.documents.single()
        assertEquals(listOf("/A/get", "/B/get"), doc.paths.map { it.path })
        assertEquals(listOf("A_get", "B_get"), doc.paths.map { it.operations.single().operationId })
    }

    @Test
    fun `an invalid service name falls back to the service name and can collide with another tag`() {
        val l =
            lower(
                "schema t\n" +
                    "\n" +
                    "model R { #1 ok bool }\n" +
                    "\n" +
                    "@openapi(name: \"a b\")\n" +
                    "service S { #1 get(): R }\n" +
                    "\n" +
                    "@openapi(name: \"S\")\n" +
                    "service T { #1 put(): R }"
            )
        assertEquals(
            listOf(
                "SCH2602 services 'S' and 'T' both lower to tag 'S'",
                "SCH2603 service 'S': @openapi(name: \"a b\") is not a valid tag",
            ),
            messages(l).sorted(),
        )
        assertEquals(listOf("/S/get", "/S/put"), l.model.documents.single().paths.map { it.path })
    }

    @Test
    fun `a service name override renames the derived path and the default operationId`() {
        val l =
            lower(
                "schema t\n" +
                    "\n" +
                    "model R { #1 ok bool }\n" +
                    "\n" +
                    "@openapi(name: \"Things\")\n" +
                    "service S {\n" +
                    "  #1 get(): R\n" +
                    "}"
            )
        val doc = l.model.documents.single()
        assertEquals(listOf("/Things/get"), doc.paths.map { it.path })
        val op = doc.paths.single().operations.single()
        assertEquals("Things_get", op.operationId)
        assertEquals("Things", op.tag)
        assertEquals(listOf(Tag("Things", null)), doc.tags)
        assertEquals(emptyList(), messages(l))
    }

    @Test
    fun `an operation name override replaces the operationId and keeps the derived path`() {
        val l =
            lower(
                "schema t\n" +
                    "\n" +
                    "model R { #1 ok bool }\n" +
                    "\n" +
                    "service S {\n" +
                    "  @openapi(name: \"fetch_it\") #1 get(): R\n" +
                    "}"
            )
        val doc = l.model.documents.single()
        assertEquals(listOf("/S/get"), doc.paths.map { it.path })
        assertEquals("fetch_it", doc.paths.single().operations.single().operationId)
        assertEquals(emptyList(), messages(l))
    }

    @Test
    fun `an invalid service name override is reported once and the service name stands`() {
        val l =
            lower(
                "schema t\n" +
                    "\n" +
                    "model R { #1 ok bool }\n" +
                    "\n" +
                    "@openapi(name: \"a b\")\n" +
                    "service S {\n" +
                    "  #1 get(): R\n" +
                    "  #2 put(): R\n" +
                    "}"
            )
        assertEquals(
            listOf("SCH2603 service 'S': @openapi(name: \"a b\") is not a valid tag"),
            messages(l),
        )
        val doc = l.model.documents.single()
        assertEquals(listOf(Tag("S", null)), doc.tags)
        assertEquals(listOf("/S/get", "/S/put"), doc.paths.map { it.path })
        assertEquals(listOf("S_get", "S_put"), doc.paths.map { it.operations.single().operationId })
        assertEquals(listOf("S", "S"), doc.paths.map { it.operations.single().tag })
    }

    @Test
    fun `two services with one tag name collide and the tag is emitted once`() {
        val l =
            lower(
                "schema t\n" +
                    "\n" +
                    "model R { #1 ok bool }\n" +
                    "\n" +
                    "/// First.\n" +
                    "@openapi(name: \"Same\")\n" +
                    "service A {\n" +
                    "  #1 one(): R\n" +
                    "}\n" +
                    "\n" +
                    "/// Second.\n" +
                    "@openapi(name: \"Same\")\n" +
                    "service B {\n" +
                    "  #1 two(): R\n" +
                    "}"
            )
        assertEquals(listOf("SCH2602 services 'A' and 'B' both lower to tag 'Same'"), messages(l))
        val doc = l.model.documents.single()
        assertEquals(listOf(Tag("Same", "First.")), doc.tags)
        assertEquals(listOf("/Same/one", "/Same/two"), doc.paths.map { it.path })
        assertEquals(listOf("Same", "Same"), doc.paths.map { it.operations.single().tag })
    }

    @Test
    fun `namespace annotations cross namespace payloads and deprecation`() {
        val cust =
            "schema cust\n" +
                "\n" +
                "model CustomerId { #1 id uuid }\n" +
                "\n" +
                "model Customer { #1 id uuid  #2 address Address }\n" +
                "\n" +
                "model Address { #1 city string }"
        val api =
            "schema api @openapi(version: \"2.3.0\") @openapi(server: \"https://api.example.com\")\n" +
                "\n" +
                "/// Customers.\n" +
                "@deprecated\n" +
                "service Customers {\n" +
                "  #1 get(cust.CustomerId): cust.Customer  get \"/customers/{id}\"\n" +
                "}"
        val l = lower(cust, api)
        val doc = l.model.documents.single()
        assertEquals("api.openapi.json", doc.path)
        assertEquals("2.3.0", doc.version)
        assertEquals("https://api.example.com", doc.server)
        assertNull(doc.description)
        assertEquals(listOf("cust.Customer", "cust.Address"), doc.components.map { it.key })
        assertTrue(doc.paths.single().operations.single().deprecated)
        assertEquals(Tag("Customers", "Customers.\n\nDeprecated."), doc.tags.single())
        assertEquals(emptyList(), messages(l))
    }

    @Test
    fun `records with one simple name in two namespaces keep their own property names`() {
        val billing = "schema billing\n\nmodel Money { #1 amount decimal(19, 4) }"
        val shop =
            "schema shop.orders\n" +
                "\n" +
                "model Money { #1 amount decimal(19, 4) }\n" +
                "\n" +
                "model Bill { #1 local Money  #2 billed billing.Money }\n" +
                "\n" +
                "service Bills {\n" +
                "  #1 get(): Bill\n" +
                "}"
        val l = lower(billing, shop)
        assertEquals(emptyList(), messages(l))
        val doc = l.model.documents.single()
        assertEquals(
            listOf("shop.orders.Bill", "shop.orders.Money", "billing.Money"),
            doc.components.map { it.key },
        )
        doc.components.drop(1).forEach {
            assertEquals(
                listOf("amount"),
                (it.schema as ObjectSchema).properties.map { p -> p.name },
            )
        }
    }

    @Test
    fun `an invalid server is reported and left out`() {
        val l =
            lower(
                "schema t @openapi(server: \"not a url\")\n" +
                    "\n" +
                    "model R { #1 ok bool }\n" +
                    "\n" +
                    "service S {\n" +
                    "  #1 ping(): R\n" +
                    "}"
            )
        assertNull(l.model.documents.single().server)
        assertEquals(
            listOf("SCH2603 schema 't': @openapi(server: \"not a url\") is not a valid URL"),
            messages(l),
        )
    }

    @Test
    fun `a component key outside the OpenAPI alphabet is reported`() {
        val l =
            lower(
                "schema t\n" +
                    "\n" +
                    "model R {\n" +
                    "  #1 ok bool\n" +
                    "\n" +
                    "  @@jsonschema(name: \"R:x\")\n" +
                    "}\n" +
                    "\n" +
                    "service S {\n" +
                    "  #1 ping(): R\n" +
                    "}"
            )
        assertEquals(
            listOf("SCH2603 model 'R': component key 't.R:x' is not a valid component key"),
            messages(l),
        )
    }

    @Test
    fun `a doc with paragraphs splits into summary and description`() {
        val l =
            lower(
                "schema t\n" +
                    "\n" +
                    "model R { #1 ok bool }\n" +
                    "\n" +
                    "service S {\n" +
                    "  /// Ping it.\n" +
                    "  ///\n" +
                    "  /// Twice if needed.\n" +
                    "  #1 ping(): R\n" +
                    "}"
            )
        val op = l.model.documents.single().paths.single().operations.single()
        assertEquals("Ping it.", op.summary)
        assertEquals("Twice if needed.", op.description)
    }

    @Test
    fun `a renamed parameter field warns that the override does not apply`() {
        val l =
            lower(
                "schema t\n" +
                    "\n" +
                    "model Q { #1 text string @jsonschema(name: \"Text\") }\n" +
                    "\n" +
                    "model R { #1 ok bool }\n" +
                    "\n" +
                    "service S {\n" +
                    "  #1 find(Q): R  get \"/find\"\n" +
                    "}"
            )
        val op = l.model.documents.single().paths.single().operations.single()
        assertEquals(listOf("text"), op.parameters.map { it.name })
        assertEquals(
            listOf(
                "SCH2604 operation 'find': @jsonschema(name) on field 'text' does not rename the parameter"
            ),
            messages(l),
        )
    }

    @Test
    fun `a parameter carries its field's doc and deprecation outside its schema`() {
        val l =
            lower(
                "schema t\n" +
                    "\n" +
                    "model Q {\n" +
                    "  /// What to find.\n" +
                    "  #1 text string @deprecated\n" +
                    "}\n" +
                    "\n" +
                    "model R { #1 ok bool }\n" +
                    "\n" +
                    "service S {\n" +
                    "  #1 find(Q): R  get \"/find\"\n" +
                    "}"
            )
        val p = l.model.documents.single().paths.single().operations.single().parameters.single()
        assertEquals("What to find.", p.description)
        assertTrue(p.deprecated)
        assertTrue(p.required)
        assertEquals(ScalarSchema("string"), p.schema)
    }

    @Test
    fun `a component shared by two documents reports its problems once`() {
        val shared = "schema m\n\nmodel R { #1 a string  #2 b string @jsonschema(name: \"a\") }"
        val one = "schema one\n\nservice S {\n  #1 ping(): m.R\n}"
        val two = "schema two\n\nservice S {\n  #1 ping(): m.R\n}"
        val l = lower(shared, one, two)
        assertEquals(2, l.model.documents.size)
        assertEquals(1, l.diagnostics.size)
        assertEquals("SCH2602", l.diagnostics.single().code.id)
    }

    @Test
    fun `every reference resolves to a component and nested declarations travel with their parent`() {
        val l =
            lower(
                "schema t\n" +
                    "\n" +
                    "model Q { #1 id uuid  #2 s S }\n" +
                    "\n" +
                    "enum S { a }\n" +
                    "\n" +
                    "model Outer {\n" +
                    "  #1 inner Outer.In\n" +
                    "\n" +
                    "  model In { #1 x X }\n" +
                    "\n" +
                    "  model Spare { #1 y Y }\n" +
                    "}\n" +
                    "\n" +
                    "model X { #1 v map<string, Z[]> }\n" +
                    "\n" +
                    "model Y { #1 ok bool }\n" +
                    "\n" +
                    "model Z { #1 ok bool }\n" +
                    "\n" +
                    "service S2 {\n" +
                    "  #1 get(Q): Outer  get \"/q/{id}\"\n" +
                    "}"
            )
        val doc = l.model.documents.single()
        assertEquals(
            listOf("t.S", "t.Outer", "t.Outer.In", "t.Outer.Spare", "t.X", "t.Z", "t.Y"),
            doc.components.map { it.key },
        )
        val keys = doc.components.map { "#/components/schemas/${it.key}" }.toSet()
        val schemas =
            doc.components.map { it.schema } +
                doc.paths.flatMap { item ->
                    item.operations.flatMap { op ->
                        op.parameters.map { it.schema } +
                            listOfNotNull(op.requestBody?.schema) +
                            listOfNotNull((op.response as? Response.Content)?.schema)
                    }
                }
        assertTrue(schemas.flatMap(::refs).all { it in keys })
        assertEquals(emptyList(), messages(l))
    }

    private fun refs(schema: JsonSchema): List<String> =
        when (schema) {
            is RefSchema -> listOf(schema.uri)
            is ObjectSchema -> schema.properties.flatMap { refs(it.schema) }
            is ArraySchema -> refs(schema.items)
            is MapSchema -> refs(schema.values)
            is TaggedUnionSchema -> schema.members.flatMap { refs(it.schema) }
            is ScalarSchema,
            is EnumSchema -> emptyList()
        }

    @Test
    fun `a namespace without services yields no document`() {
        assertEquals(emptyList(), lower("schema t\n\nmodel R { #1 ok bool }").model.documents)
    }

    @Test
    fun `a service with no operations is a tag without paths`() {
        val doc = lower("schema t\n\nservice S {}").model.documents.single()
        assertEquals(listOf(Tag("S", null)), doc.tags)
        assertEquals(emptyList(), doc.paths)
        assertEquals(emptyList(), doc.components)
    }

    private val relations by lazy { lower(RELATIONS).model.documents.single() }

    private fun component(key: String) =
        relations.components.single { it.key == "shop.$key" }.schema as ObjectSchema

    private fun property(key: String, name: String) =
        component(key).properties.single { it.name == name }

    @Test
    fun `a reference to a keyed model emits its key`() {
        val id = property("Customer", "id").schema
        assertEquals(
            listOf("id", "customer_id", "billing", "tags", "pair", "pairs", "backup_id"),
            component("Order").properties.map { it.name },
        )
        assertEquals(id, property("Order", "customer_id").schema)
        assertEquals(
            ArraySchema(ScalarSchema("string", maxLength = 16)),
            property("Order", "tags").schema,
        )
        val list = relations.paths.single().operations.single { it.verb == Verb.GET }
        assertEquals(listOf("customer_id"), list.parameters.map { it.name })
        assertEquals(id, list.parameters.single().schema)
    }

    @Test
    fun `embed restores the record`() {
        assertEquals(
            RefSchema("#/components/schemas/shop.Customer"),
            property("Order", "billing").schema,
        )
    }

    @Test
    fun `a composite-key reference emits one key object`() {
        // a key carries no copy of its model: only what the payloads hold is a component
        assertEquals(
            listOf("shop.Order", "shop.Customer", "shop.PairKey"),
            relations.components.map { it.key },
        )
        assertEquals(listOf("a", "b"), component("PairKey").properties.map { it.name })
        val key = RefSchema("#/components/schemas/shop.PairKey")
        assertEquals(key, property("Order", "pair").schema)
        assertEquals(ArraySchema(key), property("Order", "pairs").schema)
    }

    private val members by lazy { lower(MEMBERS).model.documents.single() }

    private fun member(key: String) = members.components.single { it.key == "shop.$key" }.schema

    @Test
    fun `a union member typed as a keyed model carries its key`() {
        val party = member("Party") as TaggedUnionSchema
        assertEquals(listOf("customer", "pair"), party.members.map { it.tag })
        assertEquals(uuid, party.members[0].schema)
        assertEquals(RefSchema("#/components/schemas/shop.PairKey"), party.members[1].schema)
    }

    @Test
    fun `a map value typed as a keyed model carries its key`() {
        val book = (member("Book") as ObjectSchema).properties.associate { it.name to it.schema }
        assertEquals(uuid, (book.getValue("by") as MapSchema).values)
        assertEquals(
            RefSchema("#/components/schemas/shop.PairKey"),
            (book.getValue("pairs") as MapSchema).values,
        )
        assertEquals(
            listOf("shop.Book", "shop.PairKey", "shop.Party"),
            members.components.map { it.key }.sorted(),
        )
    }
}
