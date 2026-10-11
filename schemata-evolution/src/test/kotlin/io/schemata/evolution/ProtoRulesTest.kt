package io.schemata.evolution

import io.schemata.core.ir.AnnotationValue
import io.schemata.core.ir.Annotations
import io.schemata.core.ir.Builtin
import io.schemata.core.ir.IntValue
import io.schemata.core.ir.ListOf
import io.schemata.core.ir.Ref
import io.schemata.core.ir.Refinements
import io.schemata.core.ir.Reserved
import io.schemata.core.ir.Scalar
import io.schemata.core.ir.Schema
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ProtoRulesTest {
    @Test
    fun `field added is compatible`() {
        val old = record("s", "R", field(1, "a"))
        val new = record("s", "R", field(1, "a"), field(2, "b"))
        assertEquals(Verdict.Compatible, verdict(ProtoRules, ns(old), ns(new)))
    }

    @Test
    fun `field removed with both its number and name reserved is compatible`() {
        val reserved = Reserved(listOf(2..2), setOf("b"))
        val old = record("s", "R", field(1, "a"), field(2, "b"), reserved = reserved)
        val new = record("s", "R", field(1, "a"), reserved = reserved)
        assertEquals(Verdict.Compatible, verdict(ProtoRules, ns(old), ns(new)))
    }

    @Test
    fun `field removed with only its name reserved notes the free number`() {
        val reserved = Reserved(emptyList(), setOf("b"))
        val old = record("s", "R", field(1, "a"), field(2, "b"), reserved = reserved)
        val new = record("s", "R", field(1, "a"), reserved = reserved)
        val note = assertIs<Verdict.Note>(verdict(ProtoRules, ns(old), ns(new)))
        assertTrue(note.message.contains("number 2 is free to be reused"))
    }

    @Test
    fun `field removed with only its number reserved notes the free name`() {
        val reserved = Reserved(listOf(2..2), emptySet())
        val old = record("s", "R", field(1, "a"), field(2, "b"), reserved = reserved)
        val new = record("s", "R", field(1, "a"), reserved = reserved)
        val note = assertIs<Verdict.Note>(verdict(ProtoRules, ns(old), ns(new)))
        assertTrue(note.message.contains("name 'b' is free"))
    }

    @Test
    fun `field renamed without a pin notes the json mapping change`() {
        val old = record("s", "R", field(1, "a"))
        val new = record("s", "R", field(1, "aa"))
        val note = assertIs<Verdict.Note>(verdict(ProtoRules, ns(old), ns(new)))
        assertTrue(note.message.contains("JSON mapping"))
    }

    @Test
    fun `field renamed but pinned by a proto override is compatible`() {
        val override = Annotations(mapOf("proto" to mapOf("name" to AnnotationValue.Str("a"))))
        val old = record("s", "R", field(1, "a", annotations = override))
        val new = record("s", "R", field(1, "aa", annotations = override))
        assertEquals(Verdict.Compatible, verdict(ProtoRules, ns(old), ns(new)))
    }

    @Test
    fun `field type widened from int32 to int64 is compatible`() {
        val old = record("s", "R", field(1, "a", Scalar(Builtin.INT32)))
        val new = record("s", "R", field(1, "a", Scalar(Builtin.INT64)))
        assertEquals(Verdict.Compatible, verdict(ProtoRules, ns(old), ns(new)))
    }

    @Test
    fun `field type changed from int32 to string is breaking`() {
        val old = record("s", "R", field(1, "a", Scalar(Builtin.INT32)))
        val new = record("s", "R", field(1, "a", Scalar(Builtin.STRING)))
        assertIs<Verdict.Breaking>(verdict(ProtoRules, ns(old), ns(new)))
    }

    @Test
    fun `field type changed from a scalar to a record reference is breaking`() {
        val old = record("s", "R", field(1, "a", Scalar(Builtin.BOOL)))
        val new = record("s", "R", field(1, "a", Ref(qn("s", "Other"))))
        assertIs<Verdict.Breaking>(verdict(ProtoRules, ns(old), ns(new)))
    }

    @Test
    fun `a record reference changing to int32 breaks`() {
        val other = record("s", "Other", field(1, "x"))
        val recordThenInt32Old =
            namespace("s", listOf(record("s", "R", field(1, "a", Ref(qn("s", "Other")))), other))
        val recordThenInt32New =
            namespace("s", listOf(record("s", "R", field(1, "a", Scalar(Builtin.INT32))), other))
        assertIs<Verdict.Breaking>(verdict(ProtoRules, recordThenInt32Old, recordThenInt32New))

        val int32ThenRecordOld =
            namespace("s", listOf(record("s", "R", field(1, "a", Scalar(Builtin.INT32))), other))
        val int32ThenRecordNew =
            namespace("s", listOf(record("s", "R", field(1, "a", Ref(qn("s", "Other")))), other))
        assertIs<Verdict.Breaking>(verdict(ProtoRules, int32ThenRecordOld, int32ThenRecordNew))
    }

    @Test
    fun `an enum reference changing to int32 is compatible`() {
        val e = enum("s", "E", value(1, "a"))
        val enumThenInt32Old =
            namespace("s", listOf(record("s", "R", field(1, "a", Ref(qn("s", "E")))), e))
        val enumThenInt32New =
            namespace("s", listOf(record("s", "R", field(1, "a", Scalar(Builtin.INT32))), e))
        assertEquals(Verdict.Compatible, verdict(ProtoRules, enumThenInt32Old, enumThenInt32New))

        val int32ThenEnumOld =
            namespace("s", listOf(record("s", "R", field(1, "a", Scalar(Builtin.INT32))), e))
        val int32ThenEnumNew =
            namespace("s", listOf(record("s", "R", field(1, "a", Ref(qn("s", "E")))), e))
        assertEquals(Verdict.Compatible, verdict(ProtoRules, int32ThenEnumOld, int32ThenEnumNew))
    }

    @Test
    fun `field made non-null notes the lost presence`() {
        val old = record("s", "R", field(1, "a", nullable = true))
        val new = record("s", "R", field(1, "a", nullable = false))
        assertIs<Verdict.Note>(verdict(ProtoRules, ns(old), ns(new)))
    }

    @Test
    fun `field made nullable is compatible`() {
        val old = record("s", "R", field(1, "a", nullable = false))
        val new = record("s", "R", field(1, "a", nullable = true))
        assertEquals(Verdict.Compatible, verdict(ProtoRules, ns(old), ns(new)))
    }

    @Test
    fun `a tightened refinement is noted`() {
        val old =
            record(
                "s",
                "R",
                field(1, "a", Scalar(Builtin.STRING, Refinements(max = BigDecimal(10)))),
            )
        val new =
            record(
                "s",
                "R",
                field(1, "a", Scalar(Builtin.STRING, Refinements(max = BigDecimal(5)))),
            )
        assertIs<Verdict.Note>(verdict(ProtoRules, ns(old), ns(new)))
    }

    @Test
    fun `a loosened refinement is compatible`() {
        val old =
            record(
                "s",
                "R",
                field(1, "a", Scalar(Builtin.STRING, Refinements(max = BigDecimal(5)))),
            )
        val new =
            record(
                "s",
                "R",
                field(1, "a", Scalar(Builtin.STRING, Refinements(max = BigDecimal(10)))),
            )
        assertEquals(Verdict.Compatible, verdict(ProtoRules, ns(old), ns(new)))
    }

    @Test
    fun `a default added is noted`() {
        val old = record("s", "R", field(1, "a", Scalar(Builtin.INT32)))
        val new = record("s", "R", field(1, "a", Scalar(Builtin.INT32), default = IntValue(1)))
        assertIs<Verdict.Note>(verdict(ProtoRules, ns(old), ns(new)))
    }

    @Test
    fun `a default removed is noted`() {
        val old = record("s", "R", field(1, "a", Scalar(Builtin.INT32), default = IntValue(1)))
        val new = record("s", "R", field(1, "a", Scalar(Builtin.INT32)))
        assertIs<Verdict.Note>(verdict(ProtoRules, ns(old), ns(new)))
    }

    @Test
    fun `an enum value added is compatible`() {
        val old = enum("s", "E", value(1, "a"))
        val new = enum("s", "E", value(1, "a"), value(2, "b"))
        assertEquals(Verdict.Compatible, verdict(ProtoRules, ns(old), ns(new)))
    }

    @Test
    fun `an enum value removed without reservation is breaking`() {
        val old = enum("s", "E", value(1, "a"), value(2, "b"))
        val new = enum("s", "E", value(1, "a"))
        assertIs<Verdict.Breaking>(verdict(ProtoRules, ns(old), ns(new)))
    }

    @Test
    fun `an enum value removed and reserved is noted`() {
        val reserved = Reserved(listOf(2..2), setOf("b"))
        val old = enum("s", "E", value(1, "a"), value(2, "b"), reserved = reserved)
        val new = enum("s", "E", value(1, "a"), reserved = reserved)
        assertIs<Verdict.Note>(verdict(ProtoRules, ns(old), ns(new)))
    }

    @Test
    fun `an enum value renamed is compatible`() {
        val old = enum("s", "E", value(1, "a"))
        val new = enum("s", "E", value(1, "aa"))
        assertEquals(Verdict.Compatible, verdict(ProtoRules, ns(old), ns(new)))
    }

    @Test
    fun `a union member added is compatible`() {
        val old = union("s", "U", member(1, Scalar(Builtin.BOOL)))
        val new = union("s", "U", member(1, Scalar(Builtin.BOOL)), member(2, Scalar(Builtin.INT32)))
        assertEquals(Verdict.Compatible, verdict(ProtoRules, ns(old), ns(new)))
    }

    @Test
    fun `a union member removed is breaking`() {
        val old = union("s", "U", member(1, Scalar(Builtin.BOOL)), member(2, Scalar(Builtin.INT32)))
        val new = union("s", "U", member(1, Scalar(Builtin.BOOL)))
        assertIs<Verdict.Breaking>(verdict(ProtoRules, ns(old), ns(new)))
    }

    @Test
    fun `a union member type changed is breaking`() {
        val old = union("s", "U", member(1, Scalar(Builtin.BOOL)))
        val new = union("s", "U", member(1, Scalar(Builtin.INT32)))
        assertIs<Verdict.Breaking>(verdict(ProtoRules, ns(old), ns(new)))
    }

    @Test
    fun `a declaration removed is noted`() {
        val old = record("s", "R", field(1, "a"))
        assertIs<Verdict.Note>(verdict(ProtoRules, ns(old), namespace("s")))
    }

    @Test
    fun `a declaration added is compatible`() {
        val new = record("s", "R", field(1, "a"))
        assertEquals(Verdict.Compatible, verdict(ProtoRules, namespace("s"), ns(new)))
    }

    @Test
    fun `a declaration's kind changed is breaking`() {
        val old = record("s", "R", field(1, "a"))
        val new = union("s", "R", member(1, Scalar(Builtin.BOOL)))
        assertIs<Verdict.Breaking>(verdict(ProtoRules, ns(old), ns(new)))
    }

    @Test
    fun `a model key added is compatible`() {
        val old = record("s", "R", field(1, "a"))
        val new = record("s", "R", field(1, "a"), compositeKey = listOf("a"))
        assertEquals(Verdict.Compatible, verdict(ProtoRules, ns(old), ns(new)))
    }

    @Test
    fun `a proto package annotation change is breaking`() {
        val old = record("s", "R", field(1, "a"))
        val new =
            record(
                "s",
                "R",
                field(1, "a"),
                annotations =
                    Annotations(mapOf("proto" to mapOf("package" to AnnotationValue.Str("other")))),
            )
        assertIs<Verdict.Breaking>(verdict(ProtoRules, ns(old), ns(new)))
    }

    @Test
    fun `a proto name override changed to a different name is noted`() {
        val pin = { name: String ->
            Annotations(mapOf("proto" to mapOf("name" to AnnotationValue.Str(name))))
        }
        val old = record("s", "R", field(1, "a", annotations = pin("x")))
        val new = record("s", "R", field(1, "a", annotations = pin("y")))
        val note = assertIs<Verdict.Note>(verdict(ProtoRules, ns(old), ns(new)))
        assertTrue(note.message.contains("JSON mapping"))
    }

    @Test
    fun `a proto name override added matching the declared name is compatible`() {
        val old = record("s", "R", field(1, "a"))
        val new =
            record(
                "s",
                "R",
                field(
                    1,
                    "a",
                    annotations =
                        Annotations(mapOf("proto" to mapOf("name" to AnnotationValue.Str("a")))),
                ),
            )
        assertEquals(Verdict.Compatible, verdict(ProtoRules, ns(old), ns(new)))
    }

    @Test
    fun `a proto name override on an enum value changed is compatible`() {
        val pin = { name: String ->
            Annotations(mapOf("proto" to mapOf("name" to AnnotationValue.Str(name))))
        }
        val old = enum("s", "E", value(1, "a").copy(annotations = pin("x")))
        val new = enum("s", "E", value(1, "a").copy(annotations = pin("y")))
        assertEquals(Verdict.Compatible, verdict(ProtoRules, ns(old), ns(new)))
    }

    @Test
    fun `an xsd namespace annotation change is compatible`() {
        val old = record("s", "R", field(1, "a"))
        val new =
            record(
                "s",
                "R",
                field(1, "a"),
                annotations =
                    Annotations(mapOf("xsd" to mapOf("namespace" to AnnotationValue.Str("urn:x")))),
            )
        assertEquals(Verdict.Compatible, verdict(ProtoRules, ns(old), ns(new)))
    }

    @Test
    fun `a reservation added is compatible`() {
        val old = record("s", "R", field(1, "a"), reserved = Reserved.NONE)
        val new = record("s", "R", field(1, "a"), reserved = Reserved(listOf(5..5), setOf("x")))
        assertEquals(Verdict.Compatible, verdict(ProtoRules, ns(old), ns(new)))
    }

    @Test
    fun `a reservation removed is noted`() {
        val old = record("s", "R", field(1, "a"), reserved = Reserved(listOf(5..5), setOf("x")))
        val new = record("s", "R", field(1, "a"), reserved = Reserved.NONE)
        assertIs<Verdict.Note>(verdict(ProtoRules, ns(old), ns(new)))
    }

    @Test
    fun `a deprecation change is compatible`() {
        val old = record("s", "R", field(1, "a"))
        val new =
            record(
                "s",
                "R",
                field(1, "a"),
                annotations = Annotations(mapOf("" to mapOf("deprecated" to AnnotationValue.Flag))),
            )
        assertEquals(Verdict.Compatible, verdict(ProtoRules, ns(old), ns(new)))
    }

    @Test
    fun `a doc change is compatible`() {
        val old = record("s", "R", field(1, "a"))
        val new = old.copy(doc = "updated")
        assertEquals(Verdict.Compatible, verdict(ProtoRules, ns(old), ns(new)))
    }

    /** Every change between [old] and [new], each with its `proto` verdict. */
    private fun judged(old: Schema, new: Schema): List<Pair<Change, Verdict>> {
        val ctx = ChangeContext(old, new)
        return Differ.diff(old, new).map { it to ProtoRules.classify(it, ctx) }
    }

    private fun only(old: Schema, new: Schema): Verdict = judged(old, new).single().second

    private val serviceBase = "schema t\n\nmodel A { #1 id uuid }\n\nmodel B { #1 id uuid }\n"

    private val pinnedBase = "schema t @proto(package: \"shop.v1\")\n\nmodel Id { #1 id uuid }\n"

    @Test
    fun `proto verdicts per service change`() {
        val ctx = ChangeContext(serviceOld, serviceNew)
        val changes = Differ.diff(serviceOld, serviceNew)
        fun verdict(kind: String) = ProtoRules.classify(changes.single { it.kind == kind }, ctx)
        assertEquals(
            Verdict.Breaking(
                "t.Orders.fetch: the operation was renamed, so its rpc path changes from " +
                    "/t.Orders/Get to /t.Orders/Fetch",
                "pin the rpc name with @proto(name: \"Get\")",
            ),
            verdict("operation.renamed"),
        )
        assertEquals(
            Verdict.Breaking(
                "t.Orders.list: the response changed from stream Order to Order breaks clients " +
                    "that read the old message",
                "add a new operation instead of changing this one's response",
            ),
            verdict("operation.responseChanged"),
        )
        assertEquals(Verdict.Compatible, verdict("operation.bindingChanged"))
        assertEquals(
            Verdict.Breaking(
                "t.Orders.cancel: the rpc was removed breaks clients that call /t.Orders/Cancel",
                "deprecate the operation and keep it until no client calls it; reserve " +
                    "\"cancel\" so its rpc name is not reused",
            ),
            verdict("operation.removed"),
        )
        assertEquals(
            Verdict.Note(
                "t.Orders.old: the rpc is now deprecated; generated stubs flag every call to it",
                "tell clients when the rpc will be removed",
            ),
            verdict("deprecation.changed"),
        )
        assertEquals(Verdict.Compatible, verdict("operation.added"))
        assertEquals(Verdict.Compatible, verdict("service.added"))
        assertEquals(Verdict.Compatible, verdict("reserved.changed"))
    }

    @Test
    fun `a namespace removed with services is breaking and names the first service's path`() {
        val keep = namespace("keep")
        val gone =
            analysed(
                    pinnedBase +
                        "@proto(name: \"OrderApi\") service Orders { #1 get(Id): Id }\n" +
                        "service Audit { #1 log(Id) }"
                )
                .namespaces
                .single()
        val old = Schema(listOf(gone, keep))
        val new = Schema(listOf(keep))
        assertEquals(
            Verdict.Breaking(
                "t: the schema was removed breaks clients that call /shop.v1.OrderApi/…",
                "keep the schema's services until no client calls them",
            ),
            only(old, new),
        )
        val plain = analysed(pinnedBase).namespaces.single()
        assertIs<Verdict.Note>(only(Schema(listOf(plain, keep)), new))
    }

    @Test
    fun `a pinned rpc name makes a rename compatible`() {
        val old = analysed(pinnedBase + "service S { #1 get(Id): Id }")
        val new = analysed(pinnedBase + "service S { @proto(name: \"Get\") #1 fetch(Id): Id }")
        val verdicts = judged(old, new).associate { it.first.kind to it.second }
        assertEquals(Verdict.Compatible, verdicts["operation.renamed"])
        assertEquals(Verdict.Compatible, verdicts["annotation.changed"])
        assertEquals(2, verdicts.size)
    }

    @Test
    fun `an rpc name pinned away from the derived one moves the rpc path`() {
        val old = analysed(pinnedBase + "service S { #1 get(Id): Id }")
        val moved = analysed(pinnedBase + "service S { @proto(name: \"Other\") #1 get(Id): Id }")
        assertEquals(
            Verdict.Breaking(
                "t.S.get: the rpc path changes from /shop.v1.S/Get to /shop.v1.S/Other",
                "pin the rpc name with @proto(name: \"Get\")",
            ),
            only(old, moved),
        )
    }

    @Test
    fun `a service name override moves every rpc path`() {
        val old = analysed(pinnedBase + "service S { #1 get(Id): Id }")
        val new = analysed(pinnedBase + "@proto(name: \"Store\")\nservice S { #1 get(Id): Id }")
        assertEquals(
            Verdict.Breaking(
                "t.S: the service's rpc paths change from /shop.v1.S/* to /shop.v1.Store/*",
                "pin the service name with @proto(name: \"S\")",
            ),
            only(old, new),
        )
    }

    @Test
    fun `a service name override that keeps the service name is compatible`() {
        val old = analysed(serviceBase + "service S { #1 get(A): B }")
        val new = analysed(serviceBase + "@proto(name: \"S\")\nservice S { #1 get(A): B }")
        assertEquals(Verdict.Compatible, only(old, new))
    }

    @Test
    fun `an invalid rpc name override falls back to the derived name`() {
        val old = analysed(serviceBase + "service S { #1 list_all(A): B }")
        val new =
            analysed(serviceBase + "service S { @proto(name: \"not-valid\") #1 list_all(A): B }")
        assertEquals(Verdict.Compatible, only(old, new))
    }

    @Test
    fun `a request change names both payloads`() {
        val old = analysed(serviceBase + "service S { #1 put(A): B }")
        val new = analysed(serviceBase + "service S { #1 put(stream B): B }")
        assertEquals(
            Verdict.Breaking(
                "t.S.put: the request changed from A to stream B breaks clients that send the " +
                    "old message",
                "add a new operation instead of changing this one's request",
            ),
            only(old, new),
        )
        val dropped = analysed(serviceBase + "service S { #1 put(): B }")
        assertEquals(
            "t.S.put: the request changed from A to none breaks clients that send the old message",
            assertIs<Verdict.Breaking>(only(old, dropped)).message,
        )
    }

    @Test
    fun `a removed service or reserved operation is breaking`() {
        val old =
            analysed(pinnedBase + "service S { #1 get(Id): Id  #2 drop_all(Id) }\nservice T {}")
        val new = analysed(pinnedBase + "service S { #1 get(Id): Id  reserved #2, \"drop_all\" }")
        val verdicts = judged(old, new).associate { it.first.kind to it.second }
        assertEquals(
            Verdict.Breaking(
                "t.S.drop_all: the rpc was removed breaks clients that call /shop.v1.S/DropAll",
                "deprecate the operation and keep it until no client calls it",
            ),
            verdicts["operation.removed"],
        )
        assertEquals(
            Verdict.Breaking(
                "t.T: the service was removed breaks clients that call /shop.v1.T/…",
                "deprecate its operations and keep the service until no client calls it",
            ),
            verdicts["service.removed"],
        )
        assertEquals(Verdict.Compatible, verdicts["reserved.changed"])
    }

    private val cycleAlpha = "schema cyc.alpha\n\nimport cyc.beta\n\nmodel A { #1 b B? }\n"

    private val cycleBeta = "schema cyc.beta\n\nimport cyc.alpha\n\nmodel B { #1 a A? }\n"

    @Test
    fun `a service in a reference cycle is called under the cycle's package`() {
        val old = analysedAll(cycleAlpha + "\nservice S { #1 get(A): B }", cycleBeta)
        val new = analysedAll(cycleAlpha, cycleBeta)
        assertEquals(
            Verdict.Breaking(
                "cyc.alpha.S: the service was removed breaks clients that call /cyc.S/…",
                "deprecate its operations and keep the service until no client calls it",
            ),
            only(old, new),
        )
    }

    @Test
    fun `a cycle's declared package names its rpc paths`() {
        val pinned =
            cycleAlpha.replace("schema cyc.alpha", "schema cyc.alpha @proto(package: \"shop.v1\")")
        val old = analysedAll(pinned + "\nservice S { #1 get(A): B }", cycleBeta)
        val new = analysedAll(pinned, cycleBeta)
        assertEquals(
            "cyc.alpha.S: the service was removed breaks clients that call /shop.v1.S/…",
            assertIs<Verdict.Breaking>(only(old, new)).message,
        )
    }

    @Test
    fun `each side's package assignment is computed once`() {
        val alone = analysedAll("schema cyc.beta\n\nmodel B { #1 x int32 }\n")
        val ctx = ChangeContext(analysedAll(cycleAlpha, cycleBeta), alone)
        assertSame(ctx.protoPackages(Side.OLD), ctx.protoPackages(Side.OLD))
        assertSame(ctx.protoPackages(Side.NEW), ctx.protoPackages(Side.NEW))
        assertEquals("cyc", ctx.protoPackages(Side.OLD).packageOf("cyc.beta"))
        assertEquals("cyc.beta", ctx.protoPackages(Side.NEW).packageOf("cyc.beta"))
    }

    @Test
    fun `a service deprecation lifted is a note and a doc change is compatible`() {
        val old = analysed(serviceBase + "@deprecated\nservice S { #1 get(A): B }")
        val new = analysed(serviceBase + "/// Orders.\nservice S { #1 get(A): B }")
        val verdicts = judged(old, new).associate { it.first.kind to it.second }
        assertEquals(
            Verdict.Note(
                "t.S: the service is no longer deprecated",
                "tell clients that moved off it that it stays",
            ),
            verdicts["deprecation.changed"],
        )
        assertEquals(Verdict.Compatible, verdicts["doc.changed"])
    }

    @Test
    fun `a list element becoming nullable is compatible`() {
        val old = record("s", "R", field(1, "a", ListOf(Scalar(Builtin.INT32), false)))
        val new = record("s", "R", field(1, "a", ListOf(Scalar(Builtin.INT32), true)))
        assertEquals(Verdict.Compatible, verdict(ProtoRules, ns(old), ns(new)))
    }

    @Test
    fun `a rename that also drops the old proto name pin moves the rpc path`() {
        val old = analysed(pinnedBase + "service S { @proto(name: \"Get\") #1 fetch(Id): Id }")
        val new = analysed(pinnedBase + "service S { #1 load(Id): Id }")
        val verdicts = judged(old, new).associate { it.first.kind to it.second }
        assertEquals(
            Verdict.Breaking(
                "t.S.load: the operation was renamed, so its rpc path changes from " +
                    "/shop.v1.S/Get to /shop.v1.S/Load",
                "pin the rpc name with @proto(name: \"Get\")",
            ),
            verdicts["operation.renamed"],
        )
        assertEquals(
            Verdict.Breaking(
                "t.S.load: the rpc path changes from /shop.v1.S/Get to /shop.v1.S/Load",
                "pin the rpc name with @proto(name: \"Get\")",
            ),
            verdicts["annotation.changed"],
        )
    }

    @Test
    fun `a service deprecation added is a note`() {
        val old = analysed(serviceBase + "service S { #1 get(A): B }")
        val new = analysed(serviceBase + "@deprecated\nservice S { #1 get(A): B }")
        assertEquals(
            Verdict.Note(
                "t.S: the service is now deprecated; generated stubs flag every call to it",
                "tell clients when the service will be removed",
            ),
            judged(old, new).single { it.first.kind == "deprecation.changed" }.second,
        )
    }
}
