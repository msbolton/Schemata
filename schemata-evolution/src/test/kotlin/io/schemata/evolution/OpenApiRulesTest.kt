package io.schemata.evolution

import io.schemata.core.ir.Schema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OpenApiRulesTest {
    /** Every change between [old] and [new], each with its `openapi` verdict. */
    private fun judged(old: Schema, new: Schema): List<Pair<Change, Verdict>> {
        val ctx = ChangeContext(old, new)
        return Differ.diff(old, new).map { it to OpenApiRules.classify(it, ctx) }
    }

    private fun only(old: Schema, new: Schema): Verdict = judged(old, new).single().second

    private val base = "schema t\n\nmodel A { #1 id uuid }\n\nmodel B { #1 id uuid }\n"

    @Test
    fun `openapi verdicts per change`() {
        val ctx = ChangeContext(serviceOld, serviceNew)
        val changes = Differ.diff(serviceOld, serviceNew)
        fun verdict(kind: String) = OpenApiRules.classify(changes.single { it.kind == kind }, ctx)
        assertEquals(
            Verdict.Breaking(
                "t.Orders.fetch: the operation was renamed, so its operationId changes from " +
                    "Orders_get to Orders_fetch",
                "pin the operationId with @openapi(name: \"Orders_get\")",
            ),
            verdict("operation.renamed"),
        )
        assertEquals(
            Verdict.Breaking(
                "t.Orders.list: the response changed from stream Order to Order breaks clients " +
                    "that read the old one",
                "add a new operation instead of changing this one's response",
            ),
            verdict("operation.responseChanged"),
        )
        assertEquals(
            Verdict.Breaking(
                "t.Orders.place: the URL changes from post /orders to post /orders/new, which " +
                    "breaks clients that call the old one",
                "add a new operation for the new URL instead of moving this one",
            ),
            verdict("operation.bindingChanged"),
        )
        assertEquals(
            Verdict.Breaking(
                "t.Orders.cancel: the operation was removed breaks clients that call it",
                "deprecate the operation and keep it until no client calls it; reserve " +
                    "\"cancel\" so its operationId is not reused for another call",
            ),
            verdict("operation.removed"),
        )
        assertEquals(
            Verdict.Note(
                "t.Orders.old: the operation is now deprecated; generated clients flag every " +
                    "call to it",
                "tell clients when the operation will be removed",
            ),
            verdict("deprecation.changed"),
        )
        assertEquals(Verdict.Compatible, verdict("operation.added"))
        assertEquals(Verdict.Compatible, verdict("service.added"))
        assertEquals(Verdict.Compatible, verdict("reserved.changed"))
    }

    @Test
    fun `the rulebooks without services find every service change compatible`() {
        val ctx = ChangeContext(serviceOld, serviceNew)
        val changes = Differ.diff(serviceOld, serviceNew)
        listOf(SqlRules, XsdRules, JsonSchemaRules).forEach { rules ->
            changes.forEach {
                assertEquals(
                    Verdict.Compatible,
                    rules.classify(it, ctx),
                    "${rules.target} ${it.kind}",
                )
            }
        }
    }

    @Test
    fun `a pinned operationId makes a rename compatible`() {
        val old = analysed(base + "service Orders { #1 get(A): B  get \"/a/{id}\" }")
        val new =
            analysed(
                base +
                    "service Orders { @openapi(name: \"Orders_get\") #1 fetch(A): B  get \"/a/{id}\" }"
            )
        val judged = judged(old, new)
        assertEquals(
            listOf("operation.renamed", "annotation.changed"),
            judged.map { it.first.kind },
        )
        judged.forEach { assertEquals(Verdict.Compatible, it.second, it.first.kind) }
    }

    @Test
    fun `a rename of an unbound operation moves its derived URL even when the id is pinned`() {
        val old = analysed(base + "service Orders { #1 get(A): B }")
        val new =
            analysed(base + "service Orders { @openapi(name: \"Orders_get\") #1 fetch(A): B }")
        val renamed = judged(old, new).first { it.first is OperationRenamed }.second
        assertEquals(
            Verdict.Breaking(
                "t.Orders.fetch: the operation was renamed, so its URL changes from " +
                    "post /Orders/get to post /Orders/fetch",
                "bind the operation to its old URL with post /Orders/get",
            ),
            renamed,
        )
    }

    @Test
    fun `the default operationId takes the service's tag name`() {
        val old =
            analysed(base + "@openapi(name: \"Things\") service S { #1 get(A): B  get \"/a\" }")
        val new =
            analysed(base + "@openapi(name: \"Things\") service S { #1 fetch(A): B  get \"/a\" }")
        val verdict = only(old, new)
        assertIs<Verdict.Breaking>(verdict)
        assertEquals(
            "t.S.fetch: the operation was renamed, so its operationId changes from Things_get to " +
                "Things_fetch",
            verdict.message,
        )
    }

    @Test
    fun `an invalid service name falls back to the service's own name`() {
        val ctx = ChangeContext(Schema(emptyList()), Schema(emptyList()))
        val schema =
            analysed(base + "@openapi(name: \"a b\") service S { #1 get(A): B  get \"/a\" }")
        val service = schema.namespaces.single().services.single()
        assertEquals("S", ctx.emittedName("openapi", ServiceOwner(service)))
        assertEquals(
            "S_get",
            ctx.emittedName("openapi", OperationOwner(service, service.operations.single())),
        )
        assertEquals(
            "get",
            ctx.emittedName("sql", OperationOwner(service, service.operations.single())),
        )
    }

    @Test
    fun `a request change names both payloads`() {
        val old = analysed(base + "service S { #1 put(A)  post \"/a\" }")
        val new = analysed(base + "service S { #1 put(stream B)  post \"/a\" }")
        assertEquals(
            Verdict.Breaking(
                "t.S.put: the request changed from A to stream B breaks clients that send the " +
                    "old one",
                "add a new operation instead of changing this one's request",
            ),
            only(old, new),
        )
    }

    @Test
    fun `a binding added or removed names the derived URL`() {
        val unbound = analysed(base + "service S { #1 put(A) }")
        val bound = analysed(base + "service S { #1 put(A)  put \"/a\" }")
        assertEquals(
            "t.S.put: the derived path is now bound, so the URL changes from post /S/put to " +
                "put /a, which breaks clients that call the old one",
            (only(unbound, bound) as Verdict.Breaking).message,
        )
        assertEquals(
            "t.S.put: the binding was removed, so the URL changes to the derived one from put /a " +
                "to post /S/put, which breaks clients that call the old one",
            (only(bound, unbound) as Verdict.Breaking).message,
        )
    }

    @Test
    fun `binding an operation to its derived URL is compatible and moving it is breaking`() {
        val unbound = analysed(base + "service S { #1 put(A): B }")
        val derived = analysed(base + "service S { #1 put(A): B  post \"/S/put\" }")
        val moved = analysed(base + "service S { #1 put(A): B  post \"/S/other\" }")
        assertEquals(Verdict.Compatible, only(unbound, derived))
        assertEquals(Verdict.Compatible, only(derived, unbound))
        assertEquals(
            Verdict.Breaking(
                "t.S.put: the URL changes from post /S/put to post /S/other, which breaks " +
                    "clients that call the old one",
                "add a new operation for the new URL instead of moving this one",
            ),
            only(derived, moved),
        )
    }

    @Test
    fun `an operation removed with its name reserved needs no reservation help`() {
        val old = analysed(base + "service S { #1 get(A)  #2 put(A) }")
        val new = analysed(base + "service S { #1 get(A)  reserved \"put\" }")
        val removed = judged(old, new).first { it.first is OperationRemoved }.second
        assertEquals(
            Verdict.Breaking(
                "t.S.put: the operation was removed breaks clients that call it",
                "deprecate the operation and keep it until no client calls it",
            ),
            removed,
        )
    }

    @Test
    fun `a removed service or unreserved operation is breaking`() {
        val old = analysed(base + "service S { #1 get(A)  #2 put(A) }\nservice Gone { #1 ping() }")
        val new = analysed(base + "service S { #1 get(A) }")
        val judged = judged(old, new)
        assertEquals(
            listOf(
                Verdict.Breaking(
                    "t.S.put: the operation was removed breaks clients that call it",
                    "deprecate the operation and keep it until no client calls it; reserve " +
                        "\"put\" so its operationId is not reused for another call",
                ),
                Verdict.Breaking(
                    "t.Gone: the service was removed breaks clients that call its operations",
                    "deprecate the service and keep it until no client calls it",
                ),
            ),
            judged.map { it.second },
        )
    }

    @Test
    fun `an openapi name change on a service or operation is breaking`() {
        val old = analysed(base + "service S { #1 get(A)  get \"/a\" }")
        val tagged = analysed(base + "@openapi(name: \"T\") service S { #1 get(A)  get \"/a\" }")
        assertEquals(
            Verdict.Breaking(
                "t.S: @openapi(name) added, so the tag changes from S to T, and every " +
                    "operationId built on it changes too",
                "keep @openapi(name: \"S\")",
            ),
            only(old, tagged),
        )
        val pinned = analysed(base + "service S { @openapi(name: \"getA\") #1 get(A)  get \"/a\" }")
        assertEquals(
            Verdict.Breaking(
                "t.S.get: @openapi(name) added, so the operationId changes from S_get to getA",
                "keep @openapi(name: \"S_get\")",
            ),
            only(old, pinned),
        )
        val same = analysed(base + "service S { @openapi(name: \"S_get\") #1 get(A)  get \"/a\" }")
        assertEquals(Verdict.Compatible, only(old, same))
    }

    @Test
    fun `a deprecation lifted is a note and doc and reserved changes are compatible`() {
        val old = analysed(base + "@deprecated service S { #1 get(A)  get \"/a\" }")
        val new =
            analysed(base + "/// Doc.\nservice S { /// Get.\n #1 get(A)  get \"/a\"  reserved #9 }")
        assertEquals(
            listOf(
                "doc.changed" to Verdict.Compatible,
                "reserved.changed" to Verdict.Compatible,
                "deprecation.changed" to
                    Verdict.Note(
                        "t.S: the service is no longer deprecated",
                        "tell clients that moved off it that it stays",
                    ),
                "doc.changed" to Verdict.Compatible,
            ),
            judged(old, new).map { it.first.kind to it.second },
        )
    }

    @Test
    fun `a data change is judged as json schema judges it only when a service reaches it`() {
        val oldText =
            "schema t\n" +
                "\n" +
                "model A { #1 id uuid  #2 inner Inner }\n" +
                "\n" +
                "model Inner { #1 x int32 }\n" +
                "\n" +
                "model Loose { #1 x int32 }\n" +
                "\n" +
                "service S {\n" +
                "  #1 get(A)  get \"/a\"\n" +
                "}"
        val newText =
            "schema t\n" +
                "\n" +
                "model A { #1 id uuid  #2 inner Inner }\n" +
                "\n" +
                "model Inner { #1 x string }\n" +
                "\n" +
                "model Loose { #1 x string }\n" +
                "\n" +
                "service S {\n" +
                "  #1 get(A)  get \"/a\"\n" +
                "}"
        val old = analysed(oldText)
        val new = analysed(newText)
        val ctx = ChangeContext(old, new)
        val changes = Differ.diff(old, new)
        assertEquals(listOf("t.Inner.x", "t.Loose.x"), changes.map { it.path })
        assertEquals(
            JsonSchemaRules.classify(changes[0], ctx),
            OpenApiRules.classify(changes[0], ctx),
        )
        assertIs<Verdict.Breaking>(OpenApiRules.classify(changes[0], ctx))
        assertEquals(Verdict.Compatible, OpenApiRules.classify(changes[1], ctx))
    }

    @Test
    fun `a namespace with services removed is breaking`() {
        val keep = "schema k\n\nmodel K { #1 id uuid }\n"
        val old =
            Schema(
                analysed(keep).namespaces +
                    analysed(base + "service S { #1 get(A)  get \"/a\" }").namespaces
            )
        val new = analysed(keep)
        val removed = only(old, new)
        assertIs<Verdict.Breaking>(removed)
        val plain = Schema(analysed(keep).namespaces + analysed(base).namespaces)
        assertEquals(Verdict.Compatible, only(plain, new))
    }

    @Test
    fun `a type reachable only through a removed operation's payload is judged by OLD's document`() {
        val head = "schema t\n\nmodel Id { #1 id uuid }\n\n"
        fun text(field: String, service: String) =
            head + "model Order { #1 id uuid  #2 note $field }\n\nservice S { $service }"
        val old = analysed(text("int32", "#1 keep(Id)  get \"/k\"  #2 place(Order)  post \"/o\""))
        val new = analysed(text("string", "#1 keep(Id)  get \"/k\"  reserved #2, \"place\""))
        val verdicts = judged(old, new).associate { it.first.kind to it.second }
        assertIs<Verdict.Breaking>(verdicts["operation.removed"])
        assertIs<Verdict.Breaking>(verdicts["field.typeChanged"])
    }

    @Test
    fun `a type behind two operations is still judged as data after one is removed`() {
        val head = "schema t\n\nmodel Id { #1 id uuid }\n\n"
        fun text(field: String, service: String) =
            head + "model Order { #1 id uuid  #2 note $field }\n\nservice S { $service }"
        val old = analysed(text("int32", "#1 a(Order)  post \"/a\"  #2 b(Id): Order  get \"/b\""))
        val new = analysed(text("string", "#1 a(Order)  post \"/a\"  reserved #2, \"b\""))
        val verdicts = judged(old, new).associate { it.first.kind to it.second }
        assertIs<Verdict.Breaking>(verdicts["field.typeChanged"])
    }

    @Test
    fun `a cyclic type reachable from a service is judged as data`() {
        val text = { field: String ->
            "schema t\n\nmodel A { #1 b B?  #2 n $field }\n\nmodel B { #1 a A? }\n\n" +
                "service S { #1 get(B)  get \"/b\" }"
        }
        val verdict = only(analysed(text("int32")), analysed(text("string")))
        assertIs<Verdict.Breaking>(verdict)
    }

    @Test
    fun `a service tag change says the operationIds built on it change too`() {
        val old = analysed(base + "service S { #1 get(A)  get \"/a\" }")
        val tagged = analysed(base + "@openapi(name: \"T\") service S { #1 get(A)  get \"/a\" }")
        assertEquals(
            Verdict.Breaking(
                "t.S: @openapi(name) added, so the tag changes from S to T, and every " +
                    "operationId built on it changes too",
                "keep @openapi(name: \"S\")",
            ),
            only(old, tagged),
        )
    }

    @Test
    fun `a rename with a rebind names each URL once`() {
        val old = analysed(base + "service Orders { #1 get(A): B  get \"/a\" }")
        val new = analysed(base + "service Orders { #1 fetch(A): B  get \"/b\" }")
        val pinned =
            analysed(
                base +
                    "service Orders { @openapi(name: \"Orders_get\") #1 fetch(A): B  get \"/b\" }"
            )
        for (side in listOf(new, pinned)) {
            val messages =
                judged(old, side)
                    .map { it.second }
                    .filterIsInstance<Verdict.Breaking>()
                    .joinToString("\n") { it.message }
            assertEquals(1, Regex("get /a").findAll(messages).count(), messages)
            assertEquals(1, Regex("get /b").findAll(messages).count(), messages)
        }
    }

    @Test
    fun `a rename alone still names both derived URLs`() {
        val old = analysed(base + "service Orders { #1 get(A): B }")
        val new = analysed(base + "service Orders { #1 fetch(A): B }")
        val message = (only(old, new) as Verdict.Breaking).message
        assertEquals(
            "t.Orders.fetch: the operation was renamed, so its operationId changes from " +
                "Orders_get to Orders_fetch, and its URL changes from post /Orders/get to " +
                "post /Orders/fetch",
            message,
        )
    }

    @Test
    fun `an operation that moves ordinal is compatible`() {
        val old = analysed(base + "service S { #1 get(A): B  #2 put(A): B }")
        val new = analysed(base + "service S { #1 get(A): B  #5 put(A): B }")
        val (change, verdict) = judged(old, new).single()
        val moved = assertIs<OperationMoved>(change)
        assertEquals("t.S.put", moved.path)
        assertEquals(2 to 5, moved.from.ordinal to moved.to.ordinal)
        assertEquals(Verdict.Compatible, verdict)
        val ctx = ChangeContext(old, new)
        Rulebooks.all.forEach { rulebook ->
            assertEquals(Verdict.Compatible, rulebook.classify(moved, ctx), rulebook.target)
        }
    }

    @Test
    fun `an operation that moves ordinal and changes its request is judged on the request`() {
        val old = analysed(base + "service S { #1 get(A): B  #2 put(A): B }")
        val new = analysed(base + "service S { #1 get(A): B  #3 put(B): B }")
        assertEquals(
            listOf(
                "operation.moved" to Verdict.Compatible,
                "operation.requestChanged" to
                    Verdict.Breaking(
                        "t.S.put: the request changed from A to B breaks clients that send the " +
                            "old one",
                        "add a new operation instead of changing this one's request",
                    ),
            ),
            judged(old, new).map { it.first.kind to it.second },
        )
    }

    @Test
    fun `a renamed operation at a new ordinal is removed and added`() {
        val old = analysed(base + "service S { #1 get(A): B  #2 put(A): B }")
        val new = analysed(base + "service S { #1 get(A): B  #5 post(A): B }")
        val kinds = judged(old, new).map { it.first.kind }
        assertEquals(listOf("operation.added", "operation.removed"), kinds)
    }

    @Test
    fun `a success code changed is breaking`() {
        val old = analysed(base + "service S { #1 put(A): B  post \"/a\" 201 }")
        val new = analysed(base + "service S { #1 put(A): B  post \"/a\" 202 }")
        assertEquals(
            Verdict.Breaking(
                "t.S.put: the success code changed from 201 to 202 breaks clients that check " +
                    "for the old one",
                "add a new operation instead of changing this one's success code",
            ),
            only(old, new),
        )
    }

    @Test
    fun `an error added is a note, removed compatible and a body changed breaking`() {
        val old =
            analysed(base + "service S { #1 put(A): B  post \"/a\"  errors { 404 A  409 A  500 } }")
        val new =
            analysed(base + "service S { #1 put(A): B  post \"/a\"  errors { 404 A  409 B  503 } }")
        assertEquals(
            listOf(
                "operation.errorChanged" to
                    Verdict.Breaking(
                        "t.S.put: the 409 response changed from A to B breaks clients that read " +
                            "the old one",
                        "add a new operation instead of changing this one's error responses",
                    ),
                "operation.errorAdded" to
                    Verdict.Note(
                        "t.S.put: the operation can now answer 503; a client may not handle it",
                        "tell clients about the new response before the operation sends it",
                    ),
                "operation.errorRemoved" to Verdict.Compatible,
            ),
            judged(old, new).map { it.first.kind to it.second },
        )
    }

    @Test
    fun `an error body is reachable from services and judged as JSON Schema judges it`() {
        val old =
            analysed(
                "schema t\n\nmodel A { #1 id uuid }\n\nmodel P { #1 detail string }\n\n" +
                    "service S { errors { 404 P }  #1 get(A): A  get \"/a\" }"
            )
        val new =
            analysed(
                "schema t\n\nmodel A { #1 id uuid }\n\nmodel P { #1 detail int32 }\n\n" +
                    "service S { errors { 404 P }  #1 get(A): A  get \"/a\" }"
            )
        val ctx = ChangeContext(old, new)
        assertTrue(ctx.reachableFromServices(Side.OLD, qn("t", "P")))
        val change = Differ.diff(old, new).single()
        assertIs<FieldTypeChanged>(change)
        assertEquals(JsonSchemaRules.classify(change, ctx), OpenApiRules.classify(change, ctx))
        assertIs<Verdict.Breaking>(OpenApiRules.classify(change, ctx))
    }

    private val headerBase = "schema t\n\nmodel Inner { #1 id uuid }\n"

    private fun headers(request: String, response: String) =
        analysed(
            headerBase +
                "\nmodel In { #1 id uuid  $request }\n\nmodel Out { #1 id uuid  $response }\n\n" +
                "model Holder { #1 in In }\n\nservice S { #1 put(In): Out  post \"/a\" }"
        )

    @Test
    fun `a field moved into or out of a header or its header renamed is breaking`() {
        val plain = headers("#2 key string?", "#2 tag string?")
        val keyed =
            headers("#2 key string? @openapi(header: \"Idempotency-Key\")", "#2 tag string?")
        assertEquals(
            Verdict.Breaking(
                "t.In.key: @openapi(header) added, so field 'key' moves into the Idempotency-Key " +
                    "header, which breaks clients that send or read it where it was",
                "add a new field for the header instead of moving this one",
            ),
            only(plain, keyed),
        )
        assertEquals(
            Verdict.Breaking(
                "t.In.key: @openapi(header) removed, so field 'key' moves out of the " +
                    "Idempotency-Key header, which breaks clients that send or read the header",
                "keep @openapi(header: \"Idempotency-Key\")",
            ),
            only(keyed, plain),
        )
        val renamed = headers("#2 key string? @openapi(header: \"X-Key\")", "#2 tag string?")
        assertEquals(
            Verdict.Breaking(
                "t.In.key: @openapi(header) changed, so the Idempotency-Key header is renamed " +
                    "to X-Key, which breaks clients that send or read the old one",
                "keep @openapi(header: \"Idempotency-Key\")",
            ),
            only(keyed, renamed),
        )
        val recased =
            headers("#2 key string? @openapi(header: \"idempotency-key\")", "#2 tag string?")
        assertEquals(Verdict.Compatible, only(keyed, recased))
        val tagged = headers("#2 key string?", "#2 tag string? @openapi(header: \"X-Tag\")")
        assertIs<Verdict.Breaking>(only(plain, tagged))
    }

    @Test
    fun `a header on a model no operation takes or answers with changes nothing`() {
        val old = analysed(headerBase + "\nmodel Loose { #1 id uuid  #2 key string? }\n")
        val new =
            analysed(
                headerBase +
                    "\nmodel Loose { #1 id uuid  #2 key string? @openapi(header: \"X-Key\") }\n"
            )
        assertEquals(Verdict.Compatible, only(old, new))
    }

    @Test
    fun `a header added with a new field breaks only when a request requires it`() {
        val old = headers("#2 note string?", "#2 tag string?")
        assertEquals(
            Verdict.Breaking(
                "t.In.key: a required request header X-Key was added, which breaks clients " +
                    "that do not send it",
                "make the field nullable or give it a default, so the header is optional",
            ),
            only(
                old,
                headers(
                    "#2 note string?  #3 key string @openapi(header: \"X-Key\")",
                    "#2 tag string?",
                ),
            ),
        )
        assertEquals(
            Verdict.Compatible,
            only(
                old,
                headers(
                    "#2 note string?  #3 key string? @openapi(header: \"X-Key\")",
                    "#2 tag string?",
                ),
            ),
        )
        assertEquals(
            Verdict.Compatible,
            only(
                old,
                headers(
                    "#2 note string?",
                    "#2 tag string?  #3 etag string @openapi(header: \"ETag\")",
                ),
            ),
        )
    }

    private fun secured(line: String, service: String) =
        analysed(
            "schema t @openapi(bearer, basic, oauth2_token_url: \"https://a.example.com/token\"" +
                "$line)\n\nmodel A { #1 id uuid }\n\n$service"
        )

    private val oneGet = "service S { #1 get(A): A  get \"/a\" }"

    @Test
    fun `a requirement added tightened or given scopes is breaking`() {
        assertEquals(
            Verdict.Breaking(
                "t: @openapi(auth) added, so operation 'S.get' now requires bearer, which breaks " +
                    "clients without those credentials",
                "keep the old requirement on these operations, or require the new one on new " +
                    "operations",
            ),
            only(secured("", oneGet), secured(", auth: bearer", oneGet)),
        )
        assertEquals(
            Verdict.Breaking(
                "t: @openapi(auth) changed, so operation 'S.get' no longer accepts basic, which " +
                    "breaks clients without those credentials",
                "keep the old requirement on these operations, or require the new one on new " +
                    "operations",
            ),
            only(secured(", auth: (bearer, basic)", oneGet), secured(", auth: bearer", oneGet)),
        )
        assertEquals(
            Verdict.Breaking(
                "t: @openapi(auth) changed, so operation 'S.get' no longer accepts bearer, which " +
                    "breaks clients without those credentials",
                "keep the old requirement on these operations, or require the new one on new " +
                    "operations",
            ),
            only(secured(", auth: bearer", oneGet), secured(", auth: basic", oneGet)),
        )
        val read =
            "service S { @openapi(auth: oauth2, scopes: \"a:read\") #1 get(A): A  get \"/a\" }"
        val write =
            "service S { @openapi(auth: oauth2, scopes: \"a:read a:write\") #1 get(A): A  get \"/a\" }"
        assertEquals(
            Verdict.Breaking(
                "t.S.get: @openapi(scopes) changed, so operation 'get' now needs the scopes " +
                    "a:write, which breaks clients without those credentials",
                "keep the old requirement on these operations, or require the new one on new " +
                    "operations",
            ),
            only(secured("", read), secured("", write)),
        )
    }

    @Test
    fun `a requirement relaxed is compatible`() {
        assertEquals(
            Verdict.Compatible,
            only(secured(", auth: bearer", oneGet), secured(", auth: (bearer, basic)", oneGet)),
        )
        assertEquals(
            Verdict.Compatible,
            only(
                secured(", auth: bearer", oneGet),
                secured(
                    ", auth: bearer",
                    "service S { @openapi(auth: none) #1 get(A): A  get \"/a\" }",
                ),
            ),
        )
        assertEquals(
            Verdict.Compatible,
            only(
                secured(
                    "",
                    "service S { @openapi(auth: oauth2, scopes: \"a:read a:write\") #1 get(A): A  get \"/a\" }",
                ),
                secured(
                    "",
                    "service S { @openapi(auth: oauth2, scopes: \"a:read\") #1 get(A): A  get \"/a\" }",
                ),
            ),
        )
        val service =
            "@openapi(auth: basic) service S { @openapi(auth: bearer) #1 get(A): A  get \"/a\" }"
        assertEquals(
            Verdict.Compatible,
            only(secured(", auth: bearer", service), secured(", auth: basic", service)),
        )
    }

    @Test
    fun `scheme keys judge removal while required, URL and format changes and additions`() {
        val bearerOnly =
            "schema t @openapi(bearer, auth: bearer)\n\nmodel A { #1 id uuid }\n\n$oneGet"
        val withBasic =
            "schema t @openapi(bearer, basic, auth: bearer)\n\nmodel A { #1 id uuid }\n\n$oneGet"
        val basicRequired =
            "schema t @openapi(bearer, basic, auth: basic)\n\nmodel A { #1 id uuid }\n\n$oneGet"
        val basicGone =
            "schema t @openapi(bearer, auth: basic)\n\nmodel A { #1 id uuid }\n\n$oneGet"
        assertEquals(Verdict.Compatible, only(analysed(bearerOnly), analysed(withBasic)))
        assertEquals(Verdict.Compatible, only(analysed(withBasic), analysed(bearerOnly)))
        assertEquals(
            Verdict.Breaking(
                "t: @openapi(basic) removed, so the basic scheme is gone while operations " +
                    "require it, which breaks clients that authenticate with it",
                "keep the scheme until no operation requires it",
            ),
            only(analysed(basicRequired), analysed(basicGone)),
        )
        val jwt =
            "schema t @openapi(bearer: \"JWT\", auth: bearer)\n\nmodel A { #1 id uuid }\n\n$oneGet"
        assertEquals(
            Verdict.Note(
                "t: the bearer token format changed from none to JWT",
                "tell clients which token format to send",
            ),
            only(analysed(bearerOnly), analysed(jwt)),
        )
        fun token(url: String) =
            analysed(
                "schema t @openapi(oauth2_token_url: \"$url\", auth: oauth2)\n\n" +
                    "model A { #1 id uuid }\n\n$oneGet"
            )
        assertEquals(
            Verdict.Note(
                "t: @openapi(oauth2_token_url) changed from \"https://a.example.com/token\" to " +
                    "\"https://b.example.com/token\"; clients configured with the old URL must move",
                "keep the old URL answering until every client uses the new one",
            ),
            only(token("https://a.example.com/token"), token("https://b.example.com/token")),
        )
        fun apiKey(value: String) =
            analysed(
                "schema t @openapi(api_key: \"$value\", auth: api_key)\n\n" +
                    "model A { #1 id uuid }\n\n$oneGet"
            )
        assertEquals(
            Verdict.Breaking(
                "t: @openapi(api_key) changed from \"header X-Key\" to \"query key\", which " +
                    "breaks clients that send the key where it was",
                "keep @openapi(api_key: \"header X-Key\")",
            ),
            only(apiKey("header X-Key"), apiKey("query key")),
        )
    }
}
