package io.schemata.core

import io.schemata.core.ir.ErrorResponse
import io.schemata.core.ir.HttpBinding
import io.schemata.core.ir.Payload
import io.schemata.core.ir.QualifiedName
import io.schemata.core.ir.RecordType
import io.schemata.core.ir.Verb
import io.schemata.core.ir.effectiveErrors
import io.schemata.core.ir.service
import io.schemata.core.ir.services
import io.schemata.core.ir.successStatus
import io.schemata.lang.Parser
import io.schemata.lang.Span
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val BASE =
    """
schema shop
model OrderId { #1 id uuid }
model Order { #1 id uuid #2 n int32 }
model Filter { #1 status Status? #2 limit int32 = 50 #3 tags string[] }
model Nested { #1 inner Order #2 maybe uuid? }
enum Status { pending paid }
union Either = Order | Nested
"""

class ServicesTest {
    private fun analyze(vararg sources: String): AnalysisResult =
        Analyzer.analyze(
            sources.mapIndexed { i, src ->
                val path = if (sources.size == 1) "t.schemata" else "t$i.schemata"
                Parser.parse(src, path).file!!
            }
        )

    private fun messages(result: AnalysisResult): List<String> =
        result.diagnostics.map { "${it.code.id} ${it.message}" }

    private fun qn(ns: String, vararg path: String) = QualifiedName(ns, path.toList())

    @Test
    fun `a service lowers with ordinals payloads and bindings`() {
        val r =
            analyze(
                BASE +
                    """
                    /// Orders.
                    service Orders {
                      /// one
                      #1 get(OrderId): Order  get "/orders/{id}"
                      #2 list(Filter): stream Order  get "/orders"
                      #3 either(): Either
                      #4 ping()
                      @deprecated #5 old(OrderId)  delete "/old/{id}"
                      reserved #6, "archive"
                    }
                    """
            )
        assertEquals(emptyList(), messages(r))
        val s = r.schema!!.namespaces.single().services.single()
        assertEquals(qn("shop", "Orders"), s.qualifiedName)
        assertEquals("Orders", s.name)
        assertEquals("Orders.", s.doc)
        val ops = s.operations
        assertEquals(listOf(1, 2, 3, 4, 5), ops.map { it.ordinal })
        assertEquals(listOf("get", "list", "either", "ping", "old"), ops.map { it.name })
        assertEquals("one", ops[0].doc)
        assertEquals(Payload(qn("shop", "OrderId"), false), ops[0].request)
        assertEquals(Payload(qn("shop", "Order"), false), ops[0].response)
        assertEquals(HttpBinding(Verb.GET, "/orders/{id}", listOf("id")), ops[0].binding)
        assertEquals(Payload(qn("shop", "Filter"), false), ops[1].request)
        assertEquals(Payload(qn("shop", "Order"), true), ops[1].response)
        assertEquals(HttpBinding(Verb.GET, "/orders", emptyList()), ops[1].binding)
        assertNull(ops[2].request)
        assertEquals(qn("shop", "Either"), ops[2].response!!.target)
        assertNull(ops[2].binding)
        assertNull(ops[3].request)
        assertNull(ops[3].response)
        assertNull(ops[3].binding)
        assertTrue("deprecated" in ops[4].annotations[""])
        assertNull(ops[4].response)
        assertEquals(HttpBinding(Verb.DELETE, "/old/{id}", listOf("id")), ops[4].binding)
        assertEquals(setOf(6), s.reserved.ordinals.flatMap { it.toList() }.toSet())
        assertEquals(setOf("archive"), s.reserved.names)
        assertEquals(listOf(s), r.schema!!.services())
        assertEquals(s, r.schema!!.service(qn("shop", "Orders")))
        assertNull(r.schema!!.service(qn("shop", "Order")))
    }

    @Test
    fun `verbs that carry no body take their request fields as parameters`() {
        assertEquals(
            listOf(Verb.GET, Verb.DELETE, Verb.HEAD, Verb.OPTIONS),
            Verb.entries.filter { it.parameterised },
        )
        assertEquals(
            listOf("get", "post", "put", "patch", "delete", "head", "options"),
            Verb.entries.map { it.lower },
        )
    }

    @Test
    fun `deprecated is accepted on a service`() {
        val r =
            analyze(BASE + "@deprecated(\"use Orders2\")\nservice Orders { #1 a(OrderId): Order }")
        assertEquals(emptyList(), messages(r))
        assertTrue("deprecated" in r.schema!!.services().single().annotations[""])
    }

    @Test
    fun `implicit ordinals and reserved rules apply to operations`() {
        val ok = analyze(BASE + "service S { a(OrderId): Order  b(OrderId): Order }")
        assertEquals(
            listOf(1, 2),
            ok.schema!!.namespaces.single().services.single().operations.map { it.ordinal },
        )
        val mixed = analyze(BASE + "service S { #1 a(OrderId): Order  b(OrderId): Order }")
        assertEquals(
            listOf("SCH1013 service 'S' mixes explicit and implicit ordinals"),
            messages(mixed),
        )
        val reserved = analyze(BASE + "service S { #1 a(OrderId): Order  reserved #1 }")
        assertEquals(listOf("SCH1020 ordinal #1 is reserved in service 'S'"), messages(reserved))
        val reservedName = analyze(BASE + "service S { #1 a(OrderId): Order  reserved \"a\" }")
        assertEquals(listOf("SCH1020 name 'a' is reserved in service 'S'"), messages(reservedName))
        val strict =
            Analyzer.analyze(
                listOf(Parser.parse(BASE + "service S { a(OrderId): Order }", "t.schemata").file!!),
                AnalysisOptions(strictOrdinals = true),
            )
        assertTrue(
            messages(strict).contains("SCH1014 operation 'a' has no explicit ordinal (--strict)")
        )
    }

    @Test
    fun `payload kinds`() {
        val r =
            analyze(
                BASE +
                    "alias Id = uuid\nservice S { #1 a(uuid): Order  #2 b(Status): Order  #3 c(list<Order>): Order  #4 d(Id): Order  #5 e(Order): Order? }"
            )
        assertEquals(
            listOf(
                "SCH1047 operation 'a': request 'uuid' is not a model or a union",
                "SCH1047 operation 'b': request 'Status' is not a model or a union",
                "SCH1047 operation 'c': request 'list<Order>' is not a model or a union",
                "SCH1047 operation 'd': request 'Id' is not a model or a union",
                "SCH1047 operation 'e': response 'Order?' is not a model or a union",
            ),
            messages(r),
        )
    }

    @Test
    fun `an alias of a record is a record payload`() {
        val r =
            analyze(BASE + "alias Key = OrderId\nservice S { #1 a(Key): Order  get \"/a/{id}\" }")
        assertEquals(emptyList(), messages(r))
        assertEquals(
            qn("shop", "OrderId"),
            r.schema!!.services().single().operations[0].request!!.target,
        )
    }

    @Test
    fun `success codes lower and the default rule fills the rest`() {
        val r =
            analyze(
                BASE +
                    "service S {\n" +
                    "  #1 get(OrderId): Order  get \"/orders/{id}\"\n" +
                    "  #2 place(Order): Order  post \"/orders\" 201\n" +
                    "  #3 queue(Order)  post \"/queue\" 202\n" +
                    "  #4 drop(OrderId)  delete \"/orders/{id}\"\n" +
                    "  #5 touch(OrderId): Order  put \"/orders/{id}\" 200\n" +
                    "  #6 clear(OrderId)  delete \"/all/{id}\" 204\n" +
                    "  #7 ping()\n" +
                    "}"
            )
        assertEquals(emptyList(), messages(r))
        val ops = r.schema!!.services().single().operations
        assertEquals(listOf(null, 201, 202, null, 200, 204, null), ops.map { it.status })
        assertEquals(listOf(200, 201, 202, 204, 200, 204, 204), ops.map { it.successStatus() })
    }

    @Test
    fun `a success code outside the four or a 204 with a response is SCH1055`() {
        val r =
            analyze(
                BASE +
                    "service S {\n" +
                    "  #1 a(OrderId): Order  get \"/a/{id}\" 203\n" +
                    "  #2 b(OrderId): Order  get \"/b/{id}\" 204\n" +
                    "  #3 c(OrderId)  get \"/c/{id}\" 301\n" +
                    "}"
            )
        assertEquals(
            listOf(
                "SCH1055 operation 'a': success code 203 is not 200, 201, 202 or 204",
                "SCH1055 operation 'b': success code 204 cannot carry the response 'Order'",
                "SCH1055 operation 'c': success code 301 is not 200, 201, 202 or 204",
            ),
            messages(r),
        )
        assertEquals(Span("t.schemata", 10, 39, 10, 41), r.diagnostics[0].span)
    }

    @Test
    fun `errors clauses lower and merge into the effective list`() {
        val r =
            analyze(
                BASE +
                    "alias Err = Order\n" +
                    "service S {\n" +
                    "  errors { 500 Order  default Nested }\n" +
                    "  #1 a(OrderId): Order  get \"/a/{id}\"  errors { 404 Err  410  default Either }\n" +
                    "  #2 b(OrderId): Order  get \"/b/{id}\"\n" +
                    "}"
            )
        assertEquals(emptyList(), messages(r))
        val s = r.schema!!.services().single()
        val order = qn("shop", "Order")
        val nested = qn("shop", "Nested")
        val either = qn("shop", "Either")
        assertEquals(listOf(ErrorResponse(500, order), ErrorResponse(null, nested)), s.errors)
        val (a, b) = s.operations
        assertEquals(
            listOf(
                ErrorResponse(404, order),
                ErrorResponse(410, null),
                ErrorResponse(null, either),
            ),
            a.errors,
        )
        assertEquals(
            listOf(
                ErrorResponse(404, order),
                ErrorResponse(410, null),
                ErrorResponse(500, order),
                ErrorResponse(null, either),
            ),
            effectiveErrors(s, a),
        )
        assertEquals(emptyList(), b.errors)
        assertEquals(
            listOf(ErrorResponse(500, order), ErrorResponse(null, nested)),
            effectiveErrors(s, b),
        )
    }

    @Test
    fun `error codes and bodies that break a rule are SCH1055 at the code or the body`() {
        val r =
            analyze(
                BASE +
                    "service S {\n" +
                    "  errors { 500 Order  302  500 Nested }\n" +
                    "  errors { 503 }\n" +
                    "  #1 a(OrderId): Order  get \"/a/{id}\"  errors { 404 uuid  409 Order?  410 Status  411 Order[]  default Either  600  default }\n" +
                    "}"
            )
        assertEquals(
            listOf(
                "SCH1055 service 'S': error code 302 is outside 400 to 599",
                "SCH1055 service 'S': error code 500 is listed twice",
                "SCH1055 service 'S': more than one errors clause",
                "SCH1055 operation 'a': error body 'uuid' is not a model or a union",
                "SCH1055 operation 'a': error body 'Order?' is not a model or a union",
                "SCH1055 operation 'a': error body 'Status' is not a model or a union",
                "SCH1055 operation 'a': error body 'Order[]' is not a model or a union",
                "SCH1055 operation 'a': error code 600 is outside 400 to 599",
                "SCH1055 operation 'a': error code default is listed twice",
            ),
            messages(r),
        )
        assertEquals(
            listOf(
                Span("t.schemata", 10, 23, 10, 25),
                Span("t.schemata", 11, 3, 11, 8),
                Span("t.schemata", 12, 53, 12, 56),
            ),
            listOf(r.diagnostics[0].span, r.diagnostics[2].span, r.diagnostics[3].span),
        )
    }

    @Test
    fun `an error body from another schema marks its import used`() {
        val common = "schema common\nmodel Problem { #1 title string }"
        val onService =
            "schema api\nimport common\nservice S {\n  errors { 500 Problem }\n  #1 ping()\n}"
        val r = analyze(common, onService)
        assertEquals(emptyList(), messages(r))
        // namespaces are sorted by name: api, then common
        assertEquals(
            listOf(ErrorResponse(500, qn("common", "Problem"))),
            r.schema!!.namespaces[0].services.single().errors,
        )
        val onOperation =
            "schema api\nimport common\nmodel A { #1 x int32 }\n" +
                "service S { #1 a(A): A  errors { 409 Problem } }"
        val op = analyze(common, onOperation)
        assertEquals(emptyList(), messages(op))
        assertEquals(
            listOf(ErrorResponse(409, qn("common", "Problem"))),
            op.schema!!.namespaces[0].services.single().operations.single().errors,
        )
        val inSecondClause =
            "schema api\nimport common\nservice S {\n  errors { 500 }\n  errors { 503 Problem }\n}"
        assertEquals(
            listOf("SCH1055 service 'S': more than one errors clause"),
            messages(analyze(common, inSecondClause)),
        )
        val unused = "schema api\nimport common\nservice S { #1 ping() }"
        assertEquals(listOf("SCH1012 import 'common' is unused"), messages(analyze(common, unused)))
    }

    @Test
    fun `binding checks`() {
        val r =
            analyze(
                BASE +
                    """
                    service S {
                      #1 a(OrderId): Order  get "/a/{order}"
                      #2 b(Filter): Order  get "/b/{status}"
                      #3 c(Nested): Order  get "/c/{inner}"
                      #4 d(Nested): Order  get "/d/{maybe}"
                      #5 e(): Order  get "/e/{id}"
                      #6 f(Either): Order  get "/f/{id}"
                      #7 g(OrderId): Order  get "/g/{id}/{id}"
                      #8 h(stream OrderId): Order  get "/h"
                      #9 i(stream OrderId): Order  post "/i/{id}"
                      #10 j(OrderId): Order  post "/j"
                      #11 k(OrderId): Order  post "/j"
                    }
                    """
            )
        assertEquals(
            listOf(
                "SCH1048 operation 'a': path parameter 'order' is not a field of 'OrderId'",
                "SCH1048 operation 'b': path parameter 'status' is nullable",
                "SCH1048 operation 'c': path parameter 'inner' is not a scalar or enum field",
                "SCH1048 operation 'd': path parameter 'maybe' is nullable",
                "SCH1048 operation 'e': path parameter 'id' needs a request model",
                "SCH1048 operation 'f': path parameter 'id' needs a request model, not union 'Either'",
                "SCH1048 operation 'g': path parameter 'id' appears twice",
                "SCH1048 operation 'h': a streamed request cannot use get",
                "SCH1048 operation 'i': a streamed request cannot bind path parameters",
                "SCH1048 operation 'k': post \"/j\" is already bound by operation 'j'",
            ),
            messages(r),
        )
    }

    @Test
    fun `an enum field binds and a verb may share a path with another verb`() {
        val r =
            analyze(
                BASE +
                    """
                    model ByStatus { #1 status Status #2 limit int32 }
                    service S {
                      #1 a(ByStatus): Order  get "/s/{status}"
                      #2 b(ByStatus): Order  delete "/s/{status}"
                      #3 c(Either): Order  post "/either"
                      #4 d(stream OrderId): Order  post "/upload"
                    }
                    """
            )
        assertEquals(emptyList(), messages(r))
    }

    @Test
    fun `verb and path are unique across the services of a namespace`() {
        val r =
            analyze(
                BASE +
                    "service A { #1 a(OrderId): Order  get \"/x/{id}\" }\nservice B { #1 b(OrderId): Order  get \"/x/{id}\" }"
            )
        assertEquals(
            listOf("SCH1048 operation 'b': get \"/x/{id}\" is already bound by operation 'A.a'"),
            messages(r),
        )
        val other =
            "schema other\nmodel OrderId { #1 id uuid }\nservice C { #1 c(OrderId)  get \"/x/{id}\" }"
        assertEquals(
            emptyList(),
            messages(analyze(BASE + "service A { #1 a(OrderId): Order  get \"/x/{id}\" }", other)),
        )
    }

    @Test
    fun `paths that differ only in parameter names are one route`() {
        val r =
            analyze(
                BASE +
                    """
                    model ByStatus { #1 status Status #2 id uuid }
                    service S {
                      #1 a(OrderId): Order  get "/orders/{id}"
                      #2 b(ByStatus): Order  get "/orders/{status}"
                      #3 c(ByStatus): Order  delete "/orders/{status}"
                      #4 d(ByStatus): Order  get "/orders/{status}/{id}"
                    }
                    """
            )
        assertEquals(
            listOf(
                "SCH1048 operation 'b': get \"/orders/{status}\" is already bound by operation 'a'"
            ),
            messages(r),
        )
    }

    @Test
    fun `names and collisions`() {
        val r =
            analyze(
                BASE +
                    "service orders { #1 Get(OrderId): Order  #2 get(OrderId): Order  #3 get(OrderId): Order }\nservice Order { }"
            )
        // The index reports collisions before analysis names anything, so compare as a set.
        assertEquals(
            setOf(
                "SCH1002 service name 'orders' must be UpperCamel",
                "SCH1003 operation name 'Get' must be lower_snake",
                "SCH1005 operation 'get' is declared more than once in service 'orders'",
                "SCH1004 service 'Order' and model 'Order' are both declared in t.schemata",
            ),
            messages(r).toSet(),
        )
        assertEquals(4, messages(r).size)
    }

    @Test
    fun `an operation named null is reserved and two services of one name collide`() {
        val r = analyze(BASE + "service S { #1 null(OrderId): Order }\nservice S { }")
        assertEquals(
            setOf(
                "SCH1003 operation name 'null' is reserved",
                "SCH1004 service 'S' is declared more than once",
            ),
            messages(r).toSet(),
        )
    }

    @Test
    fun `a duplicate service is reported once and not analysed`() {
        val r =
            analyze(
                BASE +
                    "service S { #1 a(OrderId): Order  get \"/x/{id}\" }\n" +
                    "service S { #1 b(OrderId): Order  get \"/x/{id}\" }"
            )
        assertEquals(listOf("SCH1004 service 'S' is declared more than once"), messages(r))
    }

    @Test
    fun `a service declared before its record resolves`() {
        val r =
            analyze(
                "schema shop\nservice S { #1 get(Order): Order  get \"/orders/{id}\" }\n" +
                    "model Order { #1 id uuid }"
            )
        assertEquals(emptyList(), messages(r))
        val op = r.schema!!.services().single().operations.single()
        assertEquals(Payload(qn("shop", "Order"), false), op.request)
        assertEquals(HttpBinding(Verb.GET, "/orders/{id}", listOf("id")), op.binding)
    }

    @Test
    fun `the same service name in two files of a schema is reported once`() {
        val r = analyze("schema shop\nservice S { }", "schema shop\nservice S { }")
        assertEquals(
            listOf("SCH1004 service 'S' is declared in both t0.schemata:2 and t1.schemata:2"),
            messages(r),
        )
    }

    @Test
    fun `same-named services in different schemas keep their routes apart`() {
        val one =
            "schema one\nmodel OrderId { #1 id uuid }\nservice S { #1 a(OrderId)  get \"/x/{id}\" }"
        val two =
            "schema two\nmodel OrderId { #1 id uuid }\nservice S { #1 a(OrderId)  get \"/x/{id}\" }"
        assertEquals(emptyList(), messages(analyze(one, two)))
    }

    @Test
    fun `a service collides with a declaration in another file`() {
        val r =
            analyze("schema shop\nmodel Order { #1 id uuid }", "schema shop\n\nservice Order { }")
        assertEquals(
            listOf(
                "SCH1004 service 'Order' and model 'Order' are both declared in t1.schemata:3 and t0.schemata:2"
            ),
            messages(r),
        )
    }

    @Test
    fun `payloads resolve through imports and mark them used`() {
        val cust = "schema cust\nmodel CustomerId { #1 id uuid }\nmodel Customer { #1 id uuid }"
        val api = "schema api\nimport cust\nservice Customers { #1 get(CustomerId): Customer }"
        val r = analyze(cust, api)
        assertEquals(emptyList(), messages(r))
        // namespaces are sorted by name: api, then cust
        val op = r.schema!!.namespaces[0].services.single().operations[0]
        assertEquals(qn("cust", "CustomerId"), op.request!!.target)
        assertEquals(qn("cust", "Customer"), op.response!!.target)
        val qualified = "schema api\nservice Customers { #1 get(cust.CustomerId): cust.Customer }"
        assertEquals(emptyList(), messages(analyze(cust, qualified)))
        val unused = "schema api\nimport cust\nservice Customers { #1 ping() }"
        assertEquals(listOf("SCH1012 import 'cust' is unused"), messages(analyze(cust, unused)))
    }

    @Test
    fun `services survive recursion marking`() {
        val r = analyze(BASE + "model Node { #1 next Node? }\nservice S { #1 a(Node): Node }")
        assertEquals(emptyList(), messages(r))
        val ns = r.schema!!.namespaces.single()
        assertTrue((ns.declarations.single { it.name == "Node" } as RecordType).recursive)
        assertEquals(qn("shop", "Node"), ns.services.single().operations.single().request!!.target)
        assertEquals(ns.services, Recursion.mark(listOf(ns)).single().services)
    }
}
