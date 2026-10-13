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
import io.schemata.testkit.OpenApi
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
            mapOf(
                "200" to
                    Response(
                        "Order",
                        Body(
                            "application/json",
                            RefSchema("#/components/schemas/shop.orders.Order", Common()),
                        ),
                    )
            ),
            get.responses,
        )

        val delete = doc.paths[0].operations.single { it.verb == Verb.DELETE }
        assertEquals("Orders_cancel", delete.operationId)
        assertEquals(mapOf("204" to Response("No content")), delete.responses)
        assertEquals(listOf(Verb.GET, Verb.DELETE), doc.paths[0].operations.map { it.verb })

        val list = doc.paths[1].operations.single { it.verb == Verb.GET }
        assertEquals(listOf("status", "limit"), list.parameters.map { it.name })
        assertEquals(listOf("query", "query"), list.parameters.map { it.location })
        assertEquals(listOf(false, false), list.parameters.map { it.required })
        assertEquals(listOf(true, true), list.parameters.map { it.style })
        assertEquals(
            RefSchema("#/components/schemas/shop.orders.Status", Common()),
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
            mapOf(
                "200" to
                    Response(
                        "Stream of Order",
                        Body(
                            "text/event-stream",
                            RefSchema("#/components/schemas/shop.orders.Order", Common()),
                        ),
                    )
            ),
            list.responses,
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
    fun `a nullable query parameter takes the plain schema while the body keeps null`() {
        val l =
            lower(
                "schema t\n" +
                    "\n" +
                    "model Find { #1 id uuid  #2 note string?  #3 tags string[]? }\n" +
                    "\n" +
                    "model R { #1 ok bool }\n" +
                    "\n" +
                    "service S {\n" +
                    "  #1 find(Find): R  get \"/items/{id}\"\n" +
                    "  #2 edit(Find): R  put \"/items/{id}\"\n" +
                    "}"
            )
        val path = l.model.documents.single().paths.single()

        val find = path.operations.single { it.verb == Verb.GET }
        assertEquals(
            listOf(
                Parameter("id", "path", true, uuid, null, false, style = false),
                Parameter(
                    "note",
                    "query",
                    false,
                    ScalarSchema("string"),
                    null,
                    false,
                    style = true,
                ),
                Parameter(
                    "tags",
                    "query",
                    false,
                    ArraySchema(ScalarSchema("string")),
                    null,
                    false,
                    style = true,
                ),
            ),
            find.parameters,
        )

        val edit = path.operations.single { it.verb == Verb.PUT }
        assertEquals(listOf("id"), edit.parameters.map { it.name })
        val body = edit.requestBody!!.schema as ObjectSchema
        assertEquals(listOf("note", "tags"), body.properties.map { it.name })
        assertEquals(listOf(false, false), body.properties.map { it.required })
        assertEquals(
            ScalarSchema("string", common = Common(nullable = true)),
            body.properties[0].schema,
        )
        assertEquals(
            ArraySchema(ScalarSchema("string"), common = Common(nullable = true)),
            body.properties[1].schema,
        )
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
        assertEquals(mapOf("204" to Response("No content")), op.responses)
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
                            op.responses.values.mapNotNull { it.content?.schema }
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

    private fun only(l: Lowered<OpenApiModel>): OpenApiOperation =
        l.model.documents.single().paths.single().operations.single()

    private fun json(key: String): Body =
        Body("application/json", RefSchema("#/components/schemas/$key"))

    @Test
    fun `a declared success code is the success response's code`() {
        val l =
            lower(
                """
                schema t

                model Place { #1 sku string }

                model R { #1 ok bool }

                service S {
                  #1 place(Place): R  post "/r" 201
                  #2 queue(Place)  post "/q" 202
                  #3 drop(Place)  post "/d" 204
                }
                """
            )
        val ops = l.model.documents.single().paths.associate { it.path to it.operations.single() }
        assertEquals(
            listOf("201" to Response("R", json("t.R"))),
            ops.getValue("/r").responses.toList(),
        )
        assertEquals(listOf("202" to Response("Accepted")), ops.getValue("/q").responses.toList())
        assertEquals(listOf("204" to Response("No content")), ops.getValue("/d").responses.toList())
        assertEquals(emptyList(), messages(l))
    }

    @Test
    fun `an operation error replaces the service body for the same code`() {
        val l =
            lower(
                """
                schema t

                model Id { #1 id uuid }

                model R { #1 ok bool }

                model Missing { #1 what string }

                model Gone { #1 since string }

                model Problem { #1 title string }

                service S {
                  errors { default Problem  404 Missing  500 }

                  #1 get(Id): R  get "/r/{id}"  errors { 404 Gone  400 }
                  #2 ping(): R  get "/ping"
                }
                """
            )
        val doc = l.model.documents.single()
        val get = doc.paths.single { it.path == "/r/{id}" }.operations.single()
        assertEquals(
            listOf(
                "200" to Response("R", json("t.R")),
                "400" to Response("Bad Request"),
                "404" to Response("Gone", json("t.Gone")),
                "500" to Response("Internal Server Error"),
                "default" to Response("Problem", json("t.Problem")),
            ),
            get.responses.toList(),
        )
        val ping = doc.paths.single { it.path == "/ping" }.operations.single()
        assertEquals(listOf("200", "404", "500", "default"), ping.responses.keys.toList())
        assertEquals(Response("Missing", json("t.Missing")), ping.responses.getValue("404"))
        assertEquals(
            listOf("t.R", "t.Gone", "t.Problem", "t.Missing"),
            doc.components.map { it.key },
        )

        val text = OpenApiRenderer.render(l.model).single().content
        val written =
            text.substringAfter("\"operationId\": \"S_get\"").substringBefore("\"operationId\"")
        val at =
            listOf("\"200\"", "\"400\"", "\"404\"", "\"500\"", "\"default\"").map {
                written.indexOf(it)
            }
        assertTrue(at.all { it >= 0 }, written)
        assertEquals(at.sorted(), at)
        assertNull(OpenApi.validate(text))
        assertEquals(emptyList(), messages(l))
    }

    @Test
    fun `a code without a body is described by its reason phrase`() {
        val l =
            lower(
                """
                schema t

                model R { #1 ok bool }

                service S {
                  errors { 401  404  418  422  503  default }

                  #1 ping(): R  get "/ping"
                }
                """
            )
        assertEquals(
            listOf(
                "200" to Response("R", json("t.R")),
                "401" to Response("Unauthorized"),
                "404" to Response("Not Found"),
                "418" to Response("Error"),
                "422" to Response("Unprocessable Content"),
                "503" to Response("Service Unavailable"),
                "default" to Response("Error"),
            ),
            only(l).responses.toList(),
        )
    }

    @Test
    fun `reason phrases cover the registered client and server errors`() {
        assertEquals("Bad Request", HttpStatus.reason(400))
        assertEquals("Content Too Large", HttpStatus.reason(413))
        assertEquals("Too Many Requests", HttpStatus.reason(429))
        assertEquals("Unavailable For Legal Reasons", HttpStatus.reason(451))
        assertEquals("Not Extended", HttpStatus.reason(510))
        assertEquals("Network Authentication Required", HttpStatus.reason(511))
        assertEquals("Error", HttpStatus.reason(418))
        assertEquals("Error", HttpStatus.reason(499))
        assertEquals("Error", HttpStatus.reason(null))
        assertEquals(39, (400..599).count { HttpStatus.reason(it) != "Error" })
    }

    @Test
    fun `an error body reaches its components as a response does`() {
        val l =
            lower(
                """
                schema t

                model Id { #1 id uuid }

                model R { #1 ok bool }

                model Problem {
                  #1 title  string
                  #2 kind   Kind
                  #3 fields Problem.Field[]

                  model Field { #1 name string }
                }

                enum Kind { a b }

                service S {
                  #1 get(Id): R  get "/r/{id}"  errors { 400 Problem }
                }
                """
            )
        val doc = l.model.documents.single()
        assertEquals(Response("Problem", json("t.Problem")), only(l).responses.getValue("400"))
        assertEquals(
            listOf("t.R", "t.Problem", "t.Problem.Field", "t.Kind"),
            doc.components.map { it.key },
        )
        val problem = doc.components.single { it.key == "t.Problem" }.schema as ObjectSchema
        assertEquals(listOf("title", "kind", "fields"), problem.properties.map { it.name })
        assertEquals(emptyList(), messages(l))
    }

    @Test
    fun `an error body from another schema is a component under its schema's key`() {
        val l =
            lower(
                "schema c\n\nmodel Problem { #1 title string }",
                """
                schema t

                import c

                model R { #1 ok bool }

                service S {
                  #1 ping(): R  get "/ping"  errors { 500 Problem }
                }
                """,
            )
        val doc = l.model.documents.single()
        assertEquals(Response("Problem", json("c.Problem")), only(l).responses.getValue("500"))
        assertEquals(listOf("t.R", "c.Problem"), doc.components.map { it.key })
        assertEquals(emptyList(), messages(l))
    }

    @Test
    fun `a union error body is a reference to its union component`() {
        val l =
            lower(
                """
                schema t

                model Place { #1 sku string }

                model R { #1 ok bool }

                model Stock { #1 sku string }

                model Clash { #1 reason string }

                union Refusal = #1 Stock | #2 Clash

                service S {
                  #1 place(Place): R  post "/r" 201  errors { 409 Refusal }
                }
                """
            )
        assertEquals(
            listOf(
                "201" to Response("R", json("t.R")),
                "409" to Response("Refusal", json("t.Refusal")),
            ),
            only(l).responses.toList(),
        )
        assertEquals(
            listOf("t.Place", "t.R", "t.Refusal", "t.Stock", "t.Clash"),
            l.model.documents.single().components.map { it.key },
        )
        assertEquals(emptyList(), messages(l))
    }

    @Test
    fun `a get request header is a header parameter`() {
        val l =
            lower(
                """
                schema t

                model Find {
                  #1 id    uuid
                  #2 key   string  @openapi(header: "Idempotency-Key")
                  /// Who is asking.
                  #3 trace string? @openapi(header: "X-Trace")
                  #4 q     string?
                }

                model R { #1 ok bool }

                service S {
                  #1 find(Find): R  get "/items/{id}"
                }
                """
            )
        val op = only(l)
        assertEquals(
            listOf(
                Parameter("id", "path", true, uuid, null, false, style = false),
                Parameter("q", "query", false, ScalarSchema("string"), null, false, style = true),
                Parameter(
                    "Idempotency-Key",
                    "header",
                    true,
                    ScalarSchema("string"),
                    null,
                    false,
                    style = false,
                ),
                Parameter(
                    "X-Trace",
                    "header",
                    false,
                    ScalarSchema("string"),
                    "Who is asking.",
                    false,
                    style = false,
                ),
            ),
            op.parameters,
        )
        assertNull(op.requestBody)
        assertEquals(listOf("t.R"), l.model.documents.single().components.map { it.key })
        assertEquals(emptyList(), messages(l))
    }

    @Test
    fun `a request header on a body verb leaves the other fields as a partial body`() {
        val l =
            lower(
                """
                schema t

                model Place { #1 key string @openapi(header: "Idempotency-Key")  #2 sku string }

                model R { #1 ok bool }

                service S {
                  #1 place(Place): R  post "/orders"
                }
                """
            )
        val op = only(l)
        assertEquals(
            listOf(
                Parameter(
                    "Idempotency-Key",
                    "header",
                    true,
                    ScalarSchema("string"),
                    null,
                    false,
                    style = false,
                )
            ),
            op.parameters,
        )
        val body = op.requestBody!!.schema as ObjectSchema
        assertEquals(listOf("sku"), body.properties.map { it.name })
        assertEquals(listOf("t.R"), l.model.documents.single().components.map { it.key })
        assertEquals(emptyList(), messages(l))
    }

    @Test
    fun `a request of headers only has no body`() {
        val l =
            lower(
                """
                schema t

                model Ping { #1 key string @openapi(header: "X-Key") }

                service S {
                  #1 ping(Ping)  post "/ping"
                }
                """
            )
        val op = only(l)
        assertEquals(listOf("X-Key"), op.parameters.map { it.name })
        assertNull(op.requestBody)
        assertEquals(emptyList(), l.model.documents.single().components)
    }

    @Test
    fun `a response of headers only has no content`() {
        val l =
            lower(
                """
                schema t

                model Place { #1 sku string }

                model Created {
                  /// Where the order lives.
                  #1 location string @openapi(header: "Location")
                }

                service S {
                  #1 place(Place): Created  post "/orders" 201
                }
                """
            )
        assertEquals(
            listOf(
                "201" to
                    Response(
                        "Created",
                        null,
                        mapOf(
                            "Location" to
                                Header(
                                    ScalarSchema("string"),
                                    "Where the order lives.",
                                    required = true,
                                    deprecated = false,
                                )
                        ),
                    )
            ),
            only(l).responses.toList(),
        )
        assertEquals(listOf("t.Place"), l.model.documents.single().components.map { it.key })
        assertEquals(emptyList(), messages(l))
    }

    @Test
    fun `a response header takes its field's schema and the body the remaining fields`() {
        val l =
            lower(
                """
                schema t

                model Page {
                  #1 next  string?  @openapi(header: "X-Next")
                  #2 total int32    @openapi(header: "X-Total") = 0
                  #3 items string[]
                }

                service S {
                  #1 list(): Page  get "/items"
                }
                """
            )
        val ok = only(l).responses.getValue("200")
        assertEquals("Page", ok.description)
        assertEquals(
            listOf(
                "X-Next" to
                    Header(ScalarSchema("string"), null, required = false, deprecated = false),
                "X-Total" to
                    Header(
                        ScalarSchema(
                            "integer",
                            minimum = BigDecimal("-2147483648"),
                            maximum = BigDecimal("2147483647"),
                            common = Common(default = JsonNumber("0")),
                        ),
                        null,
                        required = false,
                        deprecated = false,
                    ),
            ),
            ok.headers.toList(),
        )
        assertEquals("application/json", ok.content!!.mediaType)
        assertEquals(
            listOf("items"),
            (ok.content!!.schema as ObjectSchema).properties.map { it.name },
        )
        assertEquals(emptyList(), l.model.documents.single().components)
    }

    @Test
    fun `an error body with header fields writes its headers and the remaining fields`() {
        val l =
            lower(
                """
                schema t

                model R { #1 ok bool }

                model Limited { #1 retry int32 @openapi(header: "Retry-After")  #2 detail string }

                service S {
                  #1 ping(): R  get "/ping"  errors { 429 Limited }
                }
                """
            )
        val limited = only(l).responses.getValue("429")
        assertEquals("Limited", limited.description)
        assertEquals(listOf("Retry-After"), limited.headers.keys.toList())
        assertTrue(limited.headers.getValue("Retry-After").required)
        assertEquals(
            listOf("detail"),
            (limited.content!!.schema as ObjectSchema).properties.map { it.name },
        )
        assertEquals(listOf("t.R"), l.model.documents.single().components.map { it.key })
        assertEquals(emptyList(), messages(l))
    }

    @Test
    fun `a header model reused as a field type keeps every field in its component`() {
        val l =
            lower(
                """
                schema t

                model Session { #1 token string @openapi(header: "X-Token")  #2 user string }

                model Audit { #1 session Session }

                model Q { #1 id uuid }

                service S {
                  #1 open(): Session  post "/sessions"
                  #2 audit(Q): Audit  get "/audits/{id}"
                }
                """
            )
        val doc = l.model.documents.single()
        val open = doc.paths.single { it.path == "/sessions" }.operations.single()
        val ok = open.responses.getValue("200")
        assertEquals(listOf("X-Token"), ok.headers.keys.toList())
        assertEquals(
            listOf("user"),
            (ok.content!!.schema as ObjectSchema).properties.map { it.name },
        )
        assertEquals(listOf("t.Audit", "t.Session"), doc.components.map { it.key })
        val session = doc.components.single { it.key == "t.Session" }.schema as ObjectSchema
        assertEquals(listOf("token", "user"), session.properties.map { it.name })
        assertEquals(
            RefSchema("#/components/schemas/t.Session"),
            (doc.components.single { it.key == "t.Audit" }.schema as ObjectSchema)
                .properties
                .single()
                .schema,
        )
        assertEquals(emptyList(), messages(l))
    }

    @Test
    fun `a streamed payload keeps its header fields`() {
        val l =
            lower(
                """
                schema t

                model Event { #1 id string @openapi(header: "X-Id")  #2 body string }

                service S {
                  #1 watch(): stream Event  get "/events"
                }
                """
            )
        assertEquals(
            Response(
                "Stream of Event",
                Body("text/event-stream", RefSchema("#/components/schemas/t.Event")),
            ),
            only(l).responses.getValue("200"),
        )
        val event = l.model.documents.single().components.single().schema as ObjectSchema
        assertEquals(listOf("id", "body"), event.properties.map { it.name })
    }

    @Test
    fun `headers lower to a valid document`() {
        val l =
            lower(
                """
                schema t

                model Place { #1 key string @openapi(header: "Idempotency-Key")  #2 sku string }

                model Created { #1 location string @openapi(header: "Location")  #2 id uuid }

                model Limited { #1 retry int32 @openapi(header: "Retry-After")  #2 detail string }

                model Find { #1 trace string? @openapi(header: "X-Trace")  #2 q string? }

                service S {
                  #1 place(Place): Created  post "/orders" 201  errors { 429 Limited }
                  #2 find(Find): Created  get "/orders"
                }
                """
            )
        assertEquals(emptyList(), messages(l))
        assertNull(OpenApi.validate(OpenApiRenderer.render(l.model).single().content))
    }

    @Test
    fun `a header field that is not a scalar or an enum stays a body field`() {
        val l =
            lower(
                """
                schema t

                model Who { #1 name string }

                enum Kind { a b }

                model Call {
                  #1 tags string[]            @openapi(header: "X-Tags")
                  #2 meta map<string, string> @openapi(header: "X-Meta")
                  #3 who  Who                 @openapi(header: "X-Who")
                  #4 kind Kind                @openapi(header: "X-Kind")
                }

                model R { #1 ok bool }

                service S {
                  #1 call(Call): R  post "/calls"
                }
                """
            )
        assertEquals(
            listOf(
                "SCH2605 field 'Call.tags': a header holds a scalar or an enum, not a list",
                "SCH2605 field 'Call.meta': a header holds a scalar or an enum, not a map",
                "SCH2605 field 'Call.who': a header holds a scalar or an enum, not a model",
            ),
            messages(l),
        )
        assertEquals(
            List(3) { "make the field a scalar or an enum, or take off @openapi(header)" },
            l.diagnostics.map { it.help },
        )
        val op = only(l)
        assertEquals(
            listOf(
                Parameter(
                    "X-Kind",
                    "header",
                    true,
                    RefSchema("#/components/schemas/t.Kind"),
                    null,
                    false,
                    style = false,
                )
            ),
            op.parameters,
        )
        assertEquals(
            listOf("tags", "meta", "who"),
            (op.requestBody!!.schema as ObjectSchema).properties.map { it.name },
        )
        assertEquals(
            listOf("t.Kind", "t.Who", "t.R"),
            l.model.documents.single().components.map { it.key },
        )
    }

    @Test
    fun `a header name that is not an HTTP token is refused`() {
        val l =
            lower(
                """
                schema t

                model Call {
                  #1 a string @openapi(header: "X Bad")
                  #2 b string @openapi(header: "X:Y")
                  #3 c string @openapi(header: "X-Ok_1.2~")
                }

                service S {
                  #1 call(Call)  post "/calls"
                }
                """
            )
        assertEquals(
            listOf(
                "SCH2605 field 'Call.a': header \"X Bad\" is not an HTTP token",
                "SCH2605 field 'Call.b': header \"X:Y\" is not an HTTP token",
            ),
            messages(l),
        )
        assertEquals(
            "use letters, digits, and the punctuation an HTTP token allows, such as `-`, `_`, and `.`",
            l.diagnostics.first().help,
        )
        assertEquals(listOf("X-Ok_1.2~"), only(l).parameters.map { it.name })
    }

    @Test
    fun `accept content-type and authorization are refused as headers`() {
        val l =
            lower(
                """
                schema t

                model Call {
                  #1 a string @openapi(header: "accept")
                  #2 b string @openapi(header: "Content-Type")
                  #3 c string @openapi(header: "AUTHORIZATION")
                }

                service S {
                  #1 call(Call)  post "/calls"
                }
                """
            )
        assertEquals(
            listOf(
                "SCH2605 field 'Call.a': header \"accept\" is ignored by OpenAPI",
                "SCH2605 field 'Call.b': header \"Content-Type\" is ignored by OpenAPI",
                "SCH2605 field 'Call.c': header \"AUTHORIZATION\" is ignored by OpenAPI",
            ),
            messages(l),
        )
        assertEquals(
            listOf(
                "the media type is written with the content; take off @openapi(header)",
                "the media type is written with the content; take off @openapi(header)",
                "describe authentication with a security scheme on the schema line, such as `@openapi(bearer)`",
            ),
            l.diagnostics.map { it.help },
        )
        assertTrue(only(l).parameters.isEmpty())
    }

    @Test
    fun `two headers of one model equal ignoring case are refused`() {
        val l =
            lower(
                """
                schema t

                model Call {
                  #1 first  string @openapi(header: "X-Id")
                  #2 second string @openapi(header: "x-id")
                }

                service S {
                  #1 call(Call)  post "/calls"
                }
                """
            )
        assertEquals(
            listOf(
                "SCH2605 field 'Call.second': header \"x-id\" repeats header \"X-Id\" of field 'first', ignoring case"
            ),
            messages(l),
        )
        assertEquals(
            "give each header of a model its own name; header names are compared ignoring case",
            l.diagnostics.single().help,
        )
        val op = only(l)
        assertEquals(listOf("X-Id"), op.parameters.map { it.name })
        assertEquals(
            listOf("second"),
            (op.requestBody!!.schema as ObjectSchema).properties.map { it.name },
        )
    }

    @Test
    fun `a refused header is reported once though two operations use its model`() {
        val l =
            lower(
                """
                schema t

                model Call { #1 a string @openapi(header: "X Bad") }

                service S {
                  #1 one(Call)  post "/one"
                  #2 two(Call): Call  post "/two"
                }
                """
            )
        assertEquals(
            listOf("SCH2605 field 'Call.a': header \"X Bad\" is not an HTTP token"),
            messages(l),
        )
    }

    @Test
    fun `every declared scheme is a component in a fixed order`() {
        val l =
            lower(
                """
                schema t @openapi(openid: "https://id.example.com/.well-known/openid-configuration", oauth2_authorization_url: "https://id.example.com/authorize", oauth2_token_url: "https://id.example.com/token", api_key: "header X-API-Key", basic, bearer: "JWT")

                model R { #1 ok bool }

                service S {
                  @openapi(auth: oauth2, scopes: "orders:write orders:read")
                  #1 write(): R  post "/w"
                  @openapi(auth: (oauth2, openid), scopes: "admin")
                  #2 admin(): R  post "/a"
                  @openapi(auth: openid, scopes: "profile")
                  #3 me(): R  get "/me"
                }
                """
            )
        assertEquals(emptyList(), messages(l))
        assertEquals(
            listOf(
                SecurityScheme.Http("bearer", "bearer", "JWT"),
                SecurityScheme.Http("basic", "basic", null),
                SecurityScheme.ApiKey("api_key", "header", "X-API-Key"),
                SecurityScheme.OAuth2(
                    "oauth2",
                    "https://id.example.com/token",
                    "https://id.example.com/authorize",
                    listOf("admin", "orders:read", "orders:write"),
                ),
                SecurityScheme.OpenIdConnect(
                    "openid",
                    "https://id.example.com/.well-known/openid-configuration",
                ),
            ),
            l.model.documents.single().securitySchemes,
        )
    }

    @Test
    fun `a requirement lists one entry per scheme with scopes only for oauth2 and openid`() {
        val l =
            lower(
                """
                schema t @openapi(bearer, oauth2_token_url: "https://id.example.com/token")

                model R { #1 ok bool }

                service S {
                  @openapi(auth: (bearer, oauth2), scopes: "orders:read")
                  #1 get(): R  get "/r"
                }
                """
            )
        assertEquals(emptyList(), messages(l))
        val doc = l.model.documents.single()
        assertNull(doc.security)
        assertEquals(
            listOf(
                Requirement("bearer", emptyList()),
                Requirement("oauth2", listOf("orders:read")),
            ),
            doc.paths.single().operations.single().security,
        )
    }

    @Test
    fun `operation security is written only where it differs from the document`() {
        val l =
            lower(
                """
                schema t @openapi(bearer, auth: bearer)

                model R { #1 ok bool }

                service S {
                  #1 get(): R  get "/r"
                  @openapi(auth: none)
                  #2 health(): R  get "/health"
                  @openapi(auth: bearer)
                  #3 same(): R  get "/same"
                }
                """
            )
        assertEquals(emptyList(), messages(l))
        val doc = l.model.documents.single()
        assertEquals(listOf(Requirement("bearer", emptyList())), doc.security)
        fun op(path: String) = doc.paths.single { it.path == path }.operations.single()
        assertNull(op("/r").security)
        assertEquals(emptyList(), op("/health").security)
        assertNull(op("/same").security)
    }

    @Test
    fun `a service requirement reaches its operations and scopes travel with their auth`() {
        val l =
            lower(
                """
                schema t @openapi(bearer, oauth2_token_url: "https://id.example.com/token", auth: bearer)

                model R { #1 ok bool }

                @openapi(auth: oauth2, scopes: "orders:read")
                service S {
                  #1 list(): R  get "/r"
                  @openapi(scopes: "orders:write")
                  #2 write(): R  post "/r"
                  @openapi(auth: oauth2)
                  #3 plain(): R  put "/r"
                  @openapi(auth: bearer)
                  #4 token(): R  delete "/r"
                }
                """
            )
        assertEquals(emptyList(), messages(l))
        val doc = l.model.documents.single()
        assertEquals(listOf(Requirement("bearer", emptyList())), doc.security)
        fun op(verb: Verb) = doc.paths.single().operations.single { it.verb == verb }
        assertEquals(listOf(Requirement("oauth2", listOf("orders:read"))), op(Verb.GET).security)
        assertEquals(listOf(Requirement("oauth2", listOf("orders:write"))), op(Verb.POST).security)
        assertEquals(listOf(Requirement("oauth2", emptyList())), op(Verb.PUT).security)
        assertNull(op(Verb.DELETE).security)
        assertEquals(
            listOf("orders:read", "orders:write"),
            doc.securitySchemes.filterIsInstance<SecurityScheme.OAuth2>().single().scopes,
        )
    }

    @Test
    fun `no security is written when no auth is in effect`() {
        val l =
            lower(
                """
                schema t @openapi(bearer)

                model R { #1 ok bool }

                service S {
                  #1 get(): R  get "/r"
                }
                """
            )
        assertEquals(emptyList(), messages(l))
        val doc = l.model.documents.single()
        assertNull(doc.security)
        assertNull(doc.paths.single().operations.single().security)
        assertEquals(listOf(SecurityScheme.Http("bearer", "bearer", null)), doc.securitySchemes)
    }

    @Test
    fun `an auth naming an undeclared or unknown scheme or none with a scheme is SCH2606`() {
        val l =
            lower(
                """
                schema t @openapi(bearer)

                model R { #1 ok bool }

                service S {
                  @openapi(auth: basic)
                  #1 a(): R  get "/a"
                  @openapi(auth: jwt)
                  #2 b(): R  get "/b"
                  @openapi(auth: (none, bearer))
                  #3 c(): R  get "/c"
                }
                """
            )
        assertEquals(
            listOf(
                "SCH2606 operation 'a': @openapi(auth) names 'basic', which the schema does not declare",
                "SCH2606 operation 'b': @openapi(auth) names 'jwt', which is not a scheme",
                "SCH2606 operation 'c': @openapi(auth) names none together with a scheme",
            ),
            messages(l),
        )
        assertEquals(
            listOf(
                "declare it on the schema line with `@openapi(basic)`, or name a declared scheme",
                "name bearer, basic, api_key, oauth2, openid, or none",
                "write `@openapi(auth: none)` alone, or leave none out",
            ),
            l.diagnostics.map { it.help },
        )
    }

    @Test
    fun `scopes without an oauth2 or openid requirement are SCH2606`() {
        val l =
            lower(
                """
                schema t @openapi(bearer, oauth2_token_url: "https://id.example.com/token", scopes: "a")

                model R { #1 ok bool }

                @openapi(auth: oauth2)
                service S {
                  @openapi(scopes: "b")
                  #1 a(): R  get "/a"
                  @openapi(auth: bearer, scopes: "c")
                  #2 b(): R  get "/b"
                  @openapi(auth: none, scopes: "d")
                  #3 c(): R  get "/c"
                }
                """
            )
        assertEquals(
            listOf(
                "SCH2606 schema 't': @openapi(scopes) applies to no oauth2 or openid scheme",
                "SCH2606 operation 'b': @openapi(scopes) applies to no oauth2 or openid scheme",
                "SCH2606 operation 'c': @openapi(scopes) applies to no oauth2 or openid scheme",
            ),
            messages(l),
        )
        assertEquals(
            "require oauth2 or openid with `@openapi(auth: oauth2)` at the same level, or remove " +
                "the scopes",
            l.diagnostics.first().help,
        )
    }

    @Test
    fun `a malformed scheme value is SCH2606 and the scheme is left out`() {
        val l =
            lower(
                """
                schema t @openapi(api_key: "body token", oauth2_authorization_url: "https://id.example.com/authorize", openid: "id.example.com")

                model R { #1 ok bool }

                service S {
                  #1 get(): R  get "/r"
                }
                """
            )
        assertEquals(
            listOf(
                "SCH2606 schema 't': @openapi(api_key: \"body token\") is not \"<in> <name>\"",
                "SCH2606 schema 't': @openapi(oauth2_authorization_url) needs @openapi(oauth2_token_url)",
                "SCH2606 schema 't': @openapi(openid: \"id.example.com\") is not an absolute http or https URL",
            ),
            messages(l),
        )
        assertEquals(emptyList(), l.model.documents.single().securitySchemes)
    }
}
