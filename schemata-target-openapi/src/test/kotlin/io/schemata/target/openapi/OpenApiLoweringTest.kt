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
namespace shop.orders

record OrderId { #1 id: uuid }

record ListOrders {
  #1 status: Status?
  #2 limit: int32(min = 1, max = 200) = 50
}

record PlaceOrder {
  #1 customer_id: uuid
  #2 lines: list<Order.Line>(min = 1)
}

record Order {
  #1 id: uuid
  #2 status: Status
  #3 lines: list<Line>
  #4 total: Money
  record Line { #1 sku: string(max = 64) #2 quantity: int32(min = 1) }
}

record Money { #1 amount: decimal(19, 4) }

enum Status { pending, paid, shipped, cancelled }

record Chunk { #1 bytes: bytes }
record Receipt { #1 count: int64 }

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
                "namespace t\nrecord Up { #1 id: uuid #2 name: string #3 note: string? }\n" +
                    "record R { #1 ok: bool }\nservice S { #1 update(Up): R  put \"/items/{id}\" }"
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
                "namespace t\nrecord Up { #1 id: uuid #2 tags: list<Tag> }\nenum Tag { a, b }\n" +
                    "service S { #1 update(Up)  patch \"/items/{id}\" }"
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
                "namespace t\nrecord Id { #1 id: uuid }\n" +
                    "service S { #1 touch(Id)  post \"/items/{id}\" }"
            )
        val doc = l.model.documents.single()
        val op = doc.paths.single().operations.single()
        assertNull(op.requestBody)
        assertEquals(emptyList(), doc.components)
    }

    @Test
    fun `an operation without a request has no parameters and no body`() {
        val l = lower("namespace t\nrecord R { #1 ok: bool }\nservice S { #1 ping(): R }")
        val op = l.model.documents.single().paths.single().operations.single()
        assertEquals("/S/ping", l.model.documents.single().paths.single().path)
        assertTrue(op.parameters.isEmpty())
        assertNull(op.requestBody)
    }

    @Test
    fun `a union request is a body of its component`() {
        val l =
            lower(
                "namespace t\nrecord A { #1 a: string }\nrecord B { #1 b: B2 }\nrecord B2 { #1 x: int32 }\n" +
                    "union U = #1 A | #2 B\nservice S { #1 send(U)  post \"/send\" }"
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
                "namespace t\nrecord A { #1 a: string }\nrecord B { #1 b: string }\n" +
                    "union U = #1 A | #2 B\nrecord R { #1 ok: bool }\n" +
                    "service S { #1 find(U): R  get \"/find\" }"
            )
        assertEquals(
            listOf(
                "SCH2601 operation 'find': union 'U' cannot be query parameters; use a body verb"
            ),
            messages(l),
        )
        assertEquals("request a record, or use post, put, or patch", l.diagnostics.single().help)
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
                "namespace t\nrecord F { #1 q: string #2 inner: R #3 tags: list<string> #4 rs: list<R> }\n" +
                    "record R { #1 ok: bool }\nservice S { #1 find(F): R  get \"/find\" }"
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
                "namespace t\nrecord F { #1 m: map<string, string> }\nrecord R { #1 ok: bool }\n" +
                    "service S { #1 find(F): R  get \"/find\" }"
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
                "namespace t\nrecord R { #1 ok: bool }\n" +
                    "service S { @openapi(name = \"S_a\") #1 b(R): R  @openapi(name = \"a b\") #2 a(R): R  #3 s_b(): R }\n" +
                    "service Other { #1 x(): R  post \"/S/s_b\" }"
            )
        assertEquals(
            listOf(
                "SCH2602 operations 'S.s_b' and 'Other.x' both lower to post \"/S/s_b\"",
                "SCH2602 operations 'b' and 'a' both lower to operationId 'S_a'",
                "SCH2603 operation 'a': @openapi(name = \"a b\") is not a valid operationId",
            ),
            messages(l).sorted(),
        )
    }

    @Test
    fun `a service name override renames the derived path and the default operationId`() {
        val l =
            lower(
                "namespace t\nrecord R { #1 ok: bool }\n" +
                    "@openapi(name = \"Things\")\nservice S { #1 get(): R }"
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
                "namespace t\nrecord R { #1 ok: bool }\n" +
                    "service S { @openapi(name = \"fetch_it\") #1 get(): R }"
            )
        val doc = l.model.documents.single()
        assertEquals(listOf("/S/get"), doc.paths.map { it.path })
        assertEquals("fetch_it", doc.paths.single().operations.single().operationId)
        assertEquals(emptyList(), messages(l))
    }

    @Test
    fun `two services with one tag name collide and the tag is emitted once`() {
        val l =
            lower(
                "namespace t\nrecord R { #1 ok: bool }\n" +
                    "/// First.\n@openapi(name = \"Same\")\nservice A { #1 one(): R }\n" +
                    "/// Second.\n@openapi(name = \"Same\")\nservice B { #1 two(): R }"
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
            "namespace cust\nrecord CustomerId { #1 id: uuid }\n" +
                "record Customer { #1 id: uuid #2 address: Address }\nrecord Address { #1 city: string }"
        val api =
            "@openapi(version = \"2.3.0\")\n@openapi(server = \"https://api.example.com\")\nnamespace api\n" +
                "/// Customers.\n@deprecated\n" +
                "service Customers { #1 get(cust.CustomerId): cust.Customer  get \"/customers/{id}\" }"
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
    fun `an invalid server is reported and left out`() {
        val l =
            lower(
                "@openapi(server = \"not a url\")\nnamespace t\nrecord R { #1 ok: bool }\n" +
                    "service S { #1 ping(): R }"
            )
        assertNull(l.model.documents.single().server)
        assertEquals(
            listOf("SCH2603 namespace 't': @openapi(server = \"not a url\") is not a valid URL"),
            messages(l),
        )
    }

    @Test
    fun `a component key outside the OpenAPI alphabet is reported`() {
        val l =
            lower(
                "namespace t\n@jsonschema(name = \"R:x\")\nrecord R { #1 ok: bool }\n" +
                    "service S { #1 ping(): R }"
            )
        assertEquals(
            listOf("SCH2603 record 'R': component key 't.R:x' is not a valid component key"),
            messages(l),
        )
    }

    @Test
    fun `a doc with paragraphs splits into summary and description`() {
        val l =
            lower(
                "namespace t\nrecord R { #1 ok: bool }\n" +
                    "service S {\n  /// Ping it.\n  ///\n  /// Twice if needed.\n  #1 ping(): R\n}"
            )
        val op = l.model.documents.single().paths.single().operations.single()
        assertEquals("Ping it.", op.summary)
        assertEquals("Twice if needed.", op.description)
    }

    @Test
    fun `a renamed parameter field warns that the override does not apply`() {
        val l =
            lower(
                "namespace t\nrecord Q { @jsonschema(name = \"Text\") #1 text: string }\n" +
                    "record R { #1 ok: bool }\nservice S { #1 find(Q): R  get \"/find\" }"
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
                "namespace t\nrecord Q {\n  /// What to find.\n  @deprecated\n  #1 text: string\n}\n" +
                    "record R { #1 ok: bool }\nservice S { #1 find(Q): R  get \"/find\" }"
            )
        val p = l.model.documents.single().paths.single().operations.single().parameters.single()
        assertEquals("What to find.", p.description)
        assertTrue(p.deprecated)
        assertTrue(p.required)
        assertEquals(ScalarSchema("string"), p.schema)
    }

    @Test
    fun `a component shared by two documents reports its problems once`() {
        val shared = "namespace m\nrecord R { #1 a: string @jsonschema(name = \"a\") #2 b: string }"
        val one = "namespace one\nservice S { #1 ping(): m.R }"
        val two = "namespace two\nservice S { #1 ping(): m.R }"
        val l = lower(shared, one, two)
        assertEquals(2, l.model.documents.size)
        assertEquals(1, l.diagnostics.size)
        assertEquals("SCH2602", l.diagnostics.single().code.id)
    }

    @Test
    fun `every reference resolves to a component and nested declarations travel with their parent`() {
        val l =
            lower(
                "namespace t\nrecord Q { #1 id: uuid #2 s: S }\nenum S { a }\n" +
                    "record Outer { #1 inner: Outer.In record In { #1 x: X } record Spare { #1 y: Y } }\n" +
                    "record X { #1 v: map<string, list<Z>> }\nrecord Y { #1 ok: bool }\nrecord Z { #1 ok: bool }\n" +
                    "service S2 { #1 get(Q): Outer  get \"/q/{id}\" }"
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
        assertEquals(emptyList(), lower("namespace t\nrecord R { #1 ok: bool }").model.documents)
    }

    @Test
    fun `a service with no operations is a tag without paths`() {
        val doc = lower("namespace t\nservice S {}").model.documents.single()
        assertEquals(listOf(Tag("S", null)), doc.tags)
        assertEquals(emptyList(), doc.paths)
        assertEquals(emptyList(), doc.components)
    }
}
