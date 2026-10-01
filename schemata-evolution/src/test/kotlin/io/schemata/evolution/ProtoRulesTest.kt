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
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
    fun `a sql key annotation change is compatible`() {
        val old = record("s", "R", field(1, "a"))
        val new =
            record(
                "s",
                "R",
                field(1, "a"),
                annotations =
                    Annotations(mapOf("sql" to mapOf("key" to AnnotationValue.Names(listOf("a"))))),
            )
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

    @Test
    fun `a list element becoming nullable is compatible`() {
        val old = record("s", "R", field(1, "a", ListOf(Scalar(Builtin.INT32), false)))
        val new = record("s", "R", field(1, "a", ListOf(Scalar(Builtin.INT32), true)))
        assertEquals(Verdict.Compatible, verdict(ProtoRules, ns(old), ns(new)))
    }
}
