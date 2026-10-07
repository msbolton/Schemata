package io.schemata.evolution

import io.schemata.core.ir.AnnotationValue
import io.schemata.core.ir.Annotations
import io.schemata.core.ir.Builtin
import io.schemata.core.ir.IntValue
import io.schemata.core.ir.ListOf
import io.schemata.core.ir.MapOf
import io.schemata.core.ir.Ref
import io.schemata.core.ir.Refinements
import io.schemata.core.ir.Reserved
import io.schemata.core.ir.Scalar
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class JsonSchemaRulesTest {
    @Test
    fun `a nullable field added is compatible`() {
        val old = record("s", "R", field(1, "a"))
        val new = record("s", "R", field(1, "a"), field(2, "b", nullable = true))
        assertEquals(Verdict.Compatible, verdict(JsonSchemaRules, ns(old), ns(new)))
    }

    @Test
    fun `a defaulted field added is compatible`() {
        val old = record("s", "R", field(1, "a"))
        val new =
            record(
                "s",
                "R",
                field(1, "a"),
                field(2, "b", Scalar(Builtin.INT32), default = IntValue(1)),
            )
        assertEquals(Verdict.Compatible, verdict(JsonSchemaRules, ns(old), ns(new)))
    }

    @Test
    fun `a required field added is breaking`() {
        val old = record("s", "R", field(1, "a"))
        val new = record("s", "R", field(1, "a"), field(2, "b"))
        assertIs<Verdict.Breaking>(verdict(JsonSchemaRules, ns(old), ns(new)))
    }

    @Test
    fun `a field removed from a closed record is breaking`() {
        val old = record("s", "R", field(1, "a"), field(2, "b"))
        val new = record("s", "R", field(1, "a"))
        assertIs<Verdict.Breaking>(verdict(JsonSchemaRules, ns(old), ns(new)))
    }

    @Test
    fun `a field removed from an open record is compatible`() {
        val open = Annotations(mapOf("jsonschema" to mapOf("open" to AnnotationValue.Flag)))
        val old = record("s", "R", field(1, "a"), field(2, "b"), annotations = open)
        val new = record("s", "R", field(1, "a"), annotations = open)
        assertEquals(Verdict.Compatible, verdict(JsonSchemaRules, ns(old), ns(new)))
    }

    @Test
    fun `a field renamed without a pin is breaking`() {
        val old = record("s", "R", field(1, "a"))
        val new = record("s", "R", field(1, "aa"))
        assertIs<Verdict.Breaking>(verdict(JsonSchemaRules, ns(old), ns(new)))
    }

    @Test
    fun `a field renamed but pinned by a jsonschema name override is compatible`() {
        val override = Annotations(mapOf("jsonschema" to mapOf("name" to AnnotationValue.Str("a"))))
        val old = record("s", "R", field(1, "a", annotations = override))
        val new = record("s", "R", field(1, "aa", annotations = override))
        assertEquals(Verdict.Compatible, verdict(JsonSchemaRules, ns(old), ns(new)))
    }

    @Test
    fun `a field type widened from int32 to int64 is compatible`() {
        val old = record("s", "R", field(1, "a", Scalar(Builtin.INT32)))
        val new = record("s", "R", field(1, "a", Scalar(Builtin.INT64)))
        assertEquals(Verdict.Compatible, verdict(JsonSchemaRules, ns(old), ns(new)))
    }

    @Test
    fun `a field type narrowed from int64 to int32 is breaking`() {
        val old = record("s", "R", field(1, "a", Scalar(Builtin.INT64)))
        val new = record("s", "R", field(1, "a", Scalar(Builtin.INT32)))
        assertIs<Verdict.Breaking>(verdict(JsonSchemaRules, ns(old), ns(new)))
    }

    @Test
    fun `a field type changed from a scalar to a record reference is breaking`() {
        val old = record("s", "R", field(1, "a", Scalar(Builtin.BOOL)))
        val new = record("s", "R", field(1, "a", Ref(qn("s", "Other"))))
        assertIs<Verdict.Breaking>(verdict(JsonSchemaRules, ns(old), ns(new)))
    }

    @Test
    fun `a field type changed from one enum reference to a different enum is breaking`() {
        val a = enum("s", "A", value(1, "x"))
        val b = enum("s", "B", value(1, "y"))
        val old = namespace("s", listOf(record("s", "R", field(1, "a", Ref(qn("s", "A")))), a, b))
        val new = namespace("s", listOf(record("s", "R", field(1, "a", Ref(qn("s", "B")))), a, b))
        assertIs<Verdict.Breaking>(verdict(JsonSchemaRules, old, new))
    }

    @Test
    fun `a nullable field made non-null is breaking`() {
        val old = record("s", "R", field(1, "a", nullable = true))
        val new = record("s", "R", field(1, "a", nullable = false))
        assertIs<Verdict.Breaking>(verdict(JsonSchemaRules, ns(old), ns(new)))
    }

    @Test
    fun `a non-null field made nullable is compatible`() {
        val old = record("s", "R", field(1, "a", nullable = false))
        val new = record("s", "R", field(1, "a", nullable = true))
        assertEquals(Verdict.Compatible, verdict(JsonSchemaRules, ns(old), ns(new)))
    }

    @Test
    fun `a tightened refinement is breaking`() {
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
        assertIs<Verdict.Breaking>(verdict(JsonSchemaRules, ns(old), ns(new)))
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
        assertEquals(Verdict.Compatible, verdict(JsonSchemaRules, ns(old), ns(new)))
    }

    @Test
    fun `a default added is compatible`() {
        val old = record("s", "R", field(1, "a", Scalar(Builtin.INT32)))
        val new = record("s", "R", field(1, "a", Scalar(Builtin.INT32), default = IntValue(1)))
        assertEquals(Verdict.Compatible, verdict(JsonSchemaRules, ns(old), ns(new)))
    }

    @Test
    fun `a default changed is compatible`() {
        val old = record("s", "R", field(1, "a", Scalar(Builtin.INT32), default = IntValue(1)))
        val new = record("s", "R", field(1, "a", Scalar(Builtin.INT32), default = IntValue(2)))
        assertEquals(Verdict.Compatible, verdict(JsonSchemaRules, ns(old), ns(new)))
    }

    @Test
    fun `a default removed from a non-null field is breaking and names required`() {
        val old = record("s", "R", field(1, "a", Scalar(Builtin.INT32), default = IntValue(1)))
        val new = record("s", "R", field(1, "a", Scalar(Builtin.INT32)))
        val breaking = assertIs<Verdict.Breaking>(verdict(JsonSchemaRules, ns(old), ns(new)))
        assertTrue(breaking.message.contains("required"))
    }

    @Test
    fun `a default removed from a nullable field is compatible`() {
        val old =
            record(
                "s",
                "R",
                field(1, "a", Scalar(Builtin.INT32), nullable = true, default = IntValue(1)),
            )
        val new = record("s", "R", field(1, "a", Scalar(Builtin.INT32), nullable = true))
        assertEquals(Verdict.Compatible, verdict(JsonSchemaRules, ns(old), ns(new)))
    }

    @Test
    fun `an enum value added is compatible`() {
        val old = enum("s", "E", value(1, "a"))
        val new = enum("s", "E", value(1, "a"), value(2, "b"))
        assertEquals(Verdict.Compatible, verdict(JsonSchemaRules, ns(old), ns(new)))
    }

    @Test
    fun `an enum value removed is breaking`() {
        val old = enum("s", "E", value(1, "a"), value(2, "b"))
        val new = enum("s", "E", value(1, "a"))
        assertIs<Verdict.Breaking>(verdict(JsonSchemaRules, ns(old), ns(new)))
    }

    @Test
    fun `an enum value renamed without a pin is breaking`() {
        val old = enum("s", "E", value(1, "a"))
        val new = enum("s", "E", value(1, "aa"))
        assertIs<Verdict.Breaking>(verdict(JsonSchemaRules, ns(old), ns(new)))
    }

    @Test
    fun `an enum value renamed but pinned by a jsonschema name override is compatible`() {
        val override = Annotations(mapOf("jsonschema" to mapOf("name" to AnnotationValue.Str("a"))))
        val old = enum("s", "E", value(1, "a").copy(annotations = override))
        val new = enum("s", "E", value(1, "aa").copy(annotations = override))
        assertEquals(Verdict.Compatible, verdict(JsonSchemaRules, ns(old), ns(new)))
    }

    @Test
    fun `a union member added is compatible`() {
        val old = union("s", "U", member(1, Scalar(Builtin.BOOL)))
        val new = union("s", "U", member(1, Scalar(Builtin.BOOL)), member(2, Scalar(Builtin.INT32)))
        assertEquals(Verdict.Compatible, verdict(JsonSchemaRules, ns(old), ns(new)))
    }

    @Test
    fun `a union member removed is breaking`() {
        val old = union("s", "U", member(1, Scalar(Builtin.BOOL)), member(2, Scalar(Builtin.INT32)))
        val new = union("s", "U", member(1, Scalar(Builtin.BOOL)))
        assertIs<Verdict.Breaking>(verdict(JsonSchemaRules, ns(old), ns(new)))
    }

    @Test
    fun `a union member type changed is breaking`() {
        val old = union("s", "U", member(1, Scalar(Builtin.BOOL)))
        val new = union("s", "U", member(1, Scalar(Builtin.INT32)))
        assertIs<Verdict.Breaking>(verdict(JsonSchemaRules, ns(old), ns(new)))
    }

    @Test
    fun `a declaration removed is breaking even without a root element`() {
        val notRoot = Annotations(mapOf("xsd" to mapOf("root" to AnnotationValue.Bool(false))))
        val old = record("s", "R", field(1, "a"), annotations = notRoot)
        assertIs<Verdict.Breaking>(verdict(JsonSchemaRules, ns(old), namespace("s")))
    }

    @Test
    fun `a declaration removed that was never a record is breaking`() {
        val old = enum("s", "E", value(1, "a"))
        assertIs<Verdict.Breaking>(verdict(JsonSchemaRules, ns(old), namespace("s")))
    }

    @Test
    fun `a declaration added is compatible`() {
        val new = record("s", "R", field(1, "a"))
        assertEquals(Verdict.Compatible, verdict(JsonSchemaRules, namespace("s"), ns(new)))
    }

    @Test
    fun `a declaration's kind changed is breaking`() {
        val old = record("s", "R", field(1, "a"))
        val new = union("s", "R", member(1, Scalar(Builtin.BOOL)))
        assertIs<Verdict.Breaking>(verdict(JsonSchemaRules, ns(old), ns(new)))
    }

    @Test
    fun `a jsonschema id annotation change is breaking`() {
        val old = record("s", "R", field(1, "a"))
        val new =
            record(
                "s",
                "R",
                field(1, "a"),
                annotations =
                    Annotations(mapOf("jsonschema" to mapOf("id" to AnnotationValue.Str("other")))),
            )
        assertIs<Verdict.Breaking>(verdict(JsonSchemaRules, ns(old), ns(new)))
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
                    Annotations(mapOf("xsd" to mapOf("namespace" to AnnotationValue.Str("other")))),
            )
        assertEquals(Verdict.Compatible, verdict(JsonSchemaRules, ns(old), ns(new)))
    }

    @Test
    fun `a proto package annotation change is compatible`() {
        val old = record("s", "R", field(1, "a"))
        val new =
            record(
                "s",
                "R",
                field(1, "a"),
                annotations =
                    Annotations(mapOf("proto" to mapOf("package" to AnnotationValue.Str("other")))),
            )
        assertEquals(Verdict.Compatible, verdict(JsonSchemaRules, ns(old), ns(new)))
    }

    @Test
    fun `a model key added is compatible`() {
        val old = record("s", "R", field(1, "a"))
        val new = record("s", "R", field(1, "a"), compositeKey = listOf("a"))
        assertEquals(Verdict.Compatible, verdict(JsonSchemaRules, ns(old), ns(new)))
    }

    @Test
    fun `a sql strategy annotation change is compatible`() {
        val old = record("s", "R", field(1, "a"))
        val new =
            record(
                "s",
                "R",
                field(1, "a"),
                annotations =
                    Annotations(mapOf("sql" to mapOf("strategy" to AnnotationValue.Str("joined")))),
            )
        assertEquals(Verdict.Compatible, verdict(JsonSchemaRules, ns(old), ns(new)))
    }

    @Test
    fun `a reservation removed is compatible`() {
        val old = record("s", "R", field(1, "a"), reserved = Reserved(listOf(5..5), setOf("x")))
        val new = record("s", "R", field(1, "a"), reserved = Reserved.NONE)
        assertEquals(Verdict.Compatible, verdict(JsonSchemaRules, ns(old), ns(new)))
    }

    @Test
    fun `a reservation added is compatible`() {
        val old = record("s", "R", field(1, "a"), reserved = Reserved.NONE)
        val new = record("s", "R", field(1, "a"), reserved = Reserved(listOf(5..5), setOf("x")))
        assertEquals(Verdict.Compatible, verdict(JsonSchemaRules, ns(old), ns(new)))
    }

    @Test
    fun `a jsonschema name override changed to a different name is breaking`() {
        val pin = { name: String ->
            Annotations(mapOf("jsonschema" to mapOf("name" to AnnotationValue.Str(name))))
        }
        val old = record("s", "R", field(1, "a", annotations = pin("x")))
        val new = record("s", "R", field(1, "a", annotations = pin("y")))
        assertIs<Verdict.Breaking>(verdict(JsonSchemaRules, ns(old), ns(new)))
    }

    @Test
    fun `a jsonschema name override added matching the declared name is compatible`() {
        val old = record("s", "R", field(1, "a"))
        val new =
            record(
                "s",
                "R",
                field(
                    1,
                    "a",
                    annotations =
                        Annotations(
                            mapOf("jsonschema" to mapOf("name" to AnnotationValue.Str("a")))
                        ),
                ),
            )
        assertEquals(Verdict.Compatible, verdict(JsonSchemaRules, ns(old), ns(new)))
    }

    @Test
    fun `a jsonschema name override on an enum value changed is breaking`() {
        val pin = { name: String ->
            Annotations(mapOf("jsonschema" to mapOf("name" to AnnotationValue.Str(name))))
        }
        val old = enum("s", "E", value(1, "a").copy(annotations = pin("x")))
        val new = enum("s", "E", value(1, "a").copy(annotations = pin("y")))
        assertIs<Verdict.Breaking>(verdict(JsonSchemaRules, ns(old), ns(new)))
    }

    @Test
    fun `an xsd attribute flag change is compatible`() {
        val old = record("s", "R", field(1, "a"))
        val new =
            record(
                "s",
                "R",
                field(
                    1,
                    "a",
                    annotations =
                        Annotations(mapOf("xsd" to mapOf("attribute" to AnnotationValue.Flag))),
                ),
            )
        assertEquals(Verdict.Compatible, verdict(JsonSchemaRules, ns(old), ns(new)))
    }

    @Test
    fun `an xsd root false change is compatible`() {
        val old = record("s", "R", field(1, "a"))
        val new =
            record(
                "s",
                "R",
                field(1, "a"),
                annotations =
                    Annotations(mapOf("xsd" to mapOf("root" to AnnotationValue.Bool(false)))),
            )
        assertEquals(Verdict.Compatible, verdict(JsonSchemaRules, ns(old), ns(new)))
    }

    @Test
    fun `a jsonschema open flag removed is breaking`() {
        val open = Annotations(mapOf("jsonschema" to mapOf("open" to AnnotationValue.Flag)))
        val old = record("s", "R", field(1, "a"), annotations = open)
        val new = record("s", "R", field(1, "a"))
        val breaking = assertIs<Verdict.Breaking>(verdict(JsonSchemaRules, ns(old), ns(new)))
        assertTrue(breaking.message.contains("extra properties are now rejected"))
    }

    @Test
    fun `a jsonschema open flag added is compatible`() {
        val old = record("s", "R", field(1, "a"))
        val new =
            record(
                "s",
                "R",
                field(1, "a"),
                annotations =
                    Annotations(mapOf("jsonschema" to mapOf("open" to AnnotationValue.Flag))),
            )
        assertEquals(Verdict.Compatible, verdict(JsonSchemaRules, ns(old), ns(new)))
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
        assertEquals(Verdict.Compatible, verdict(JsonSchemaRules, ns(old), ns(new)))
    }

    @Test
    fun `a doc change is compatible`() {
        val old = record("s", "R", field(1, "a"))
        val new = old.copy(doc = "updated")
        assertEquals(Verdict.Compatible, verdict(JsonSchemaRules, ns(old), ns(new)))
    }

    @Test
    fun `a list element becoming nullable is compatible`() {
        val old = record("s", "R", field(1, "a", ListOf(Scalar(Builtin.INT32), false)))
        val new = record("s", "R", field(1, "a", ListOf(Scalar(Builtin.INT32), true)))
        assertEquals(Verdict.Compatible, verdict(JsonSchemaRules, ns(old), ns(new)))
    }

    @Test
    fun `a map key type change is breaking`() {
        val old =
            record(
                "s",
                "R",
                field(1, "a", MapOf(Scalar(Builtin.STRING), Scalar(Builtin.INT32), false)),
            )
        val new =
            record(
                "s",
                "R",
                field(1, "a", MapOf(Scalar(Builtin.INT32), Scalar(Builtin.INT32), false)),
            )
        assertIs<Verdict.Breaking>(verdict(JsonSchemaRules, ns(old), ns(new)))
    }
}
