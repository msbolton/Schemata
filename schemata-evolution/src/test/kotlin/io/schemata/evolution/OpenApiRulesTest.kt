package io.schemata.evolution

import io.schemata.core.ir.Schema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class OpenApiRulesTest {
    /** Every change between [old] and [new], each with its `openapi` verdict. */
    private fun judged(old: Schema, new: Schema): List<Pair<Change, Verdict>> {
        val ctx = ChangeContext(old, new)
        return Differ.diff(old, new).map { it to OpenApiRules.classify(it, ctx) }
    }

    private fun only(old: Schema, new: Schema): Verdict = judged(old, new).single().second

    private val base = "namespace t\nrecord A { #1 id: uuid }\nrecord B { #1 id: uuid }\n"

    @Test
    fun `openapi verdicts per change`() {
        val ctx = ChangeContext(serviceOld, serviceNew)
        val changes = Differ.diff(serviceOld, serviceNew)
        fun verdict(kind: String) = OpenApiRules.classify(changes.single { it.kind == kind }, ctx)
        assertEquals(
            Verdict.Breaking(
                "t.Orders.fetch: the operation was renamed, so its operationId changes from " +
                    "Orders_get to Orders_fetch",
                "pin the operationId with @openapi(name = \"Orders_get\")",
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
    fun `the other rulebooks find every service change compatible`() {
        val ctx = ChangeContext(serviceOld, serviceNew)
        val changes = Differ.diff(serviceOld, serviceNew)
        listOf(ProtoRules, SqlRules, XsdRules, JsonSchemaRules).forEach { rules ->
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
                    "service Orders { @openapi(name = \"Orders_get\") #1 fetch(A): B  get \"/a/{id}\" }"
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
            analysed(base + "service Orders { @openapi(name = \"Orders_get\") #1 fetch(A): B }")
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
            analysed(base + "@openapi(name = \"Things\") service S { #1 get(A): B  get \"/a\" }")
        val new =
            analysed(base + "@openapi(name = \"Things\") service S { #1 fetch(A): B  get \"/a\" }")
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
            analysed(base + "@openapi(name = \"a b\") service S { #1 get(A): B  get \"/a\" }")
        val service = schema.namespaces.single().services.single()
        assertEquals("S", ctx.emittedName("openapi", ServiceOwner(service)))
        assertEquals(
            "S_get",
            ctx.emittedName("openapi", OperationOwner(service, service.operations.single())),
        )
        assertEquals(
            "get",
            ctx.emittedName("proto", OperationOwner(service, service.operations.single())),
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
        val tagged = analysed(base + "@openapi(name = \"T\") service S { #1 get(A)  get \"/a\" }")
        assertEquals(
            Verdict.Breaking(
                "t.S: @openapi(name) added, so the tag changes from S to T, and with it every " +
                    "operationId it prefixes",
                "keep @openapi(name = \"S\")",
            ),
            only(old, tagged),
        )
        val pinned =
            analysed(base + "service S { @openapi(name = \"getA\") #1 get(A)  get \"/a\" }")
        assertEquals(
            Verdict.Breaking(
                "t.S.get: @openapi(name) added, so the operationId changes from S_get to getA",
                "keep @openapi(name = \"S_get\")",
            ),
            only(old, pinned),
        )
        val same = analysed(base + "service S { @openapi(name = \"S_get\") #1 get(A)  get \"/a\" }")
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
            "namespace t\nrecord A { #1 id: uuid  #2 inner: Inner }\nrecord Inner { #1 x: int32 }\n" +
                "record Loose { #1 x: int32 }\nservice S { #1 get(A)  get \"/a\" }"
        val newText =
            "namespace t\nrecord A { #1 id: uuid  #2 inner: Inner }\nrecord Inner { #1 x: string }\n" +
                "record Loose { #1 x: string }\nservice S { #1 get(A)  get \"/a\" }"
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
        val keep = "namespace k\nrecord K { #1 id: uuid }\n"
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
}
