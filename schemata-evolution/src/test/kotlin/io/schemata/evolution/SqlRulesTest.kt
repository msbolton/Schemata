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
import io.schemata.core.ir.Schema
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SqlRulesTest {
    @Test
    fun `a required field added is breaking`() {
        val old = record("s", "R", field(1, "a"))
        val new = record("s", "R", field(1, "a"), field(2, "b"))
        assertIs<Verdict.Breaking>(verdict(SqlRules, ns(old), ns(new)))
    }

    @Test
    fun `a nullable field added is compatible`() {
        val old = record("s", "R", field(1, "a"))
        val new = record("s", "R", field(1, "a"), field(2, "b", nullable = true))
        assertEquals(Verdict.Compatible, verdict(SqlRules, ns(old), ns(new)))
    }

    @Test
    fun `a field added with a default is compatible`() {
        val old = record("s", "R", field(1, "a"))
        val new =
            record(
                "s",
                "R",
                field(1, "a"),
                field(2, "b", Scalar(Builtin.INT32), default = IntValue(1)),
            )
        assertEquals(Verdict.Compatible, verdict(SqlRules, ns(old), ns(new)))
    }

    @Test
    fun `a field removed is breaking`() {
        val old = record("s", "R", field(1, "a"), field(2, "b"))
        val new = record("s", "R", field(1, "a"))
        assertIs<Verdict.Breaking>(verdict(SqlRules, ns(old), ns(new)))
    }

    @Test
    fun `a field removed with both its number and name reserved is still breaking`() {
        val reserved = Reserved(listOf(2..2), setOf("b"))
        val old = record("s", "R", field(1, "a"), field(2, "b"), reserved = reserved)
        val new = record("s", "R", field(1, "a"), reserved = reserved)
        assertIs<Verdict.Breaking>(verdict(SqlRules, ns(old), ns(new)))
    }

    @Test
    fun `a field renamed without a pin is breaking`() {
        val old = record("s", "R", field(1, "a"))
        val new = record("s", "R", field(1, "aa"))
        assertIs<Verdict.Breaking>(verdict(SqlRules, ns(old), ns(new)))
    }

    @Test
    fun `a field renamed but pinned by a sql column override is compatible`() {
        val override = Annotations(mapOf("sql" to mapOf("column" to AnnotationValue.Str("a"))))
        val old = record("s", "R", field(1, "a", annotations = override))
        val new = record("s", "R", field(1, "aa", annotations = override))
        assertEquals(Verdict.Compatible, verdict(SqlRules, ns(old), ns(new)))
    }

    @Test
    fun `a field type widened from int32 to int64 is compatible`() {
        val old = record("s", "R", field(1, "a", Scalar(Builtin.INT32)))
        val new = record("s", "R", field(1, "a", Scalar(Builtin.INT64)))
        assertEquals(Verdict.Compatible, verdict(SqlRules, ns(old), ns(new)))
    }

    @Test
    fun `a field type narrowed from int64 to int32 is breaking`() {
        val old = record("s", "R", field(1, "a", Scalar(Builtin.INT64)))
        val new = record("s", "R", field(1, "a", Scalar(Builtin.INT32)))
        assertIs<Verdict.Breaking>(verdict(SqlRules, ns(old), ns(new)))
    }

    @Test
    fun `a field type changed from a scalar to a record reference is breaking`() {
        val old = record("s", "R", field(1, "a", Scalar(Builtin.BOOL)))
        val new = record("s", "R", field(1, "a", Ref(qn("s", "Other"))))
        assertIs<Verdict.Breaking>(verdict(SqlRules, ns(old), ns(new)))
    }

    @Test
    fun `a field type changed from one enum reference to a different enum is breaking`() {
        val a = enum("s", "A", value(1, "x"))
        val b = enum("s", "B", value(1, "y"))
        val old = namespace("s", listOf(record("s", "R", field(1, "a", Ref(qn("s", "A")))), a, b))
        val new = namespace("s", listOf(record("s", "R", field(1, "a", Ref(qn("s", "B")))), a, b))
        assertIs<Verdict.Breaking>(verdict(SqlRules, old, new))
    }

    @Test
    fun `a nullable field made non-null is breaking`() {
        val old = record("s", "R", field(1, "a", nullable = true))
        val new = record("s", "R", field(1, "a", nullable = false))
        assertIs<Verdict.Breaking>(verdict(SqlRules, ns(old), ns(new)))
    }

    @Test
    fun `a non-null field made nullable is compatible`() {
        val old = record("s", "R", field(1, "a", nullable = false))
        val new = record("s", "R", field(1, "a", nullable = true))
        assertEquals(Verdict.Compatible, verdict(SqlRules, ns(old), ns(new)))
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
        assertIs<Verdict.Breaking>(verdict(SqlRules, ns(old), ns(new)))
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
        assertEquals(Verdict.Compatible, verdict(SqlRules, ns(old), ns(new)))
    }

    @Test
    fun `a default added is compatible`() {
        val old = record("s", "R", field(1, "a", Scalar(Builtin.INT32)))
        val new = record("s", "R", field(1, "a", Scalar(Builtin.INT32), default = IntValue(1)))
        assertEquals(Verdict.Compatible, verdict(SqlRules, ns(old), ns(new)))
    }

    @Test
    fun `a default changed is compatible`() {
        val old = record("s", "R", field(1, "a", Scalar(Builtin.INT32), default = IntValue(1)))
        val new = record("s", "R", field(1, "a", Scalar(Builtin.INT32), default = IntValue(2)))
        assertEquals(Verdict.Compatible, verdict(SqlRules, ns(old), ns(new)))
    }

    @Test
    fun `a default removed from a non-null column is breaking`() {
        val old = record("s", "R", field(1, "a", Scalar(Builtin.INT32), default = IntValue(1)))
        val new = record("s", "R", field(1, "a", Scalar(Builtin.INT32)))
        assertIs<Verdict.Breaking>(verdict(SqlRules, ns(old), ns(new)))
    }

    @Test
    fun `a default removed from a nullable column is compatible`() {
        val old =
            record(
                "s",
                "R",
                field(1, "a", Scalar(Builtin.INT32), nullable = true, default = IntValue(1)),
            )
        val new = record("s", "R", field(1, "a", Scalar(Builtin.INT32), nullable = true))
        assertEquals(Verdict.Compatible, verdict(SqlRules, ns(old), ns(new)))
    }

    @Test
    fun `an enum value added is compatible`() {
        val old = enum("s", "E", value(1, "a"))
        val new = enum("s", "E", value(1, "a"), value(2, "b"))
        assertEquals(Verdict.Compatible, verdict(SqlRules, ns(old), ns(new)))
    }

    @Test
    fun `an enum value removed is breaking`() {
        val old = enum("s", "E", value(1, "a"), value(2, "b"))
        val new = enum("s", "E", value(1, "a"))
        assertIs<Verdict.Breaking>(verdict(SqlRules, ns(old), ns(new)))
    }

    @Test
    fun `an enum value renamed is breaking`() {
        val old = enum("s", "E", value(1, "a"))
        val new = enum("s", "E", value(1, "aa"))
        assertIs<Verdict.Breaking>(verdict(SqlRules, ns(old), ns(new)))
    }

    @Test
    fun `a union member added is compatible`() {
        val old = union("s", "U", member(1, Scalar(Builtin.BOOL)))
        val new = union("s", "U", member(1, Scalar(Builtin.BOOL)), member(2, Scalar(Builtin.INT32)))
        assertEquals(Verdict.Compatible, verdict(SqlRules, ns(old), ns(new)))
    }

    @Test
    fun `a union member removed is breaking`() {
        val old = union("s", "U", member(1, Scalar(Builtin.BOOL)), member(2, Scalar(Builtin.INT32)))
        val new = union("s", "U", member(1, Scalar(Builtin.BOOL)))
        assertIs<Verdict.Breaking>(verdict(SqlRules, ns(old), ns(new)))
    }

    @Test
    fun `a union member type changed is breaking`() {
        val old = union("s", "U", member(1, Scalar(Builtin.BOOL)))
        val new = union("s", "U", member(1, Scalar(Builtin.INT32)))
        assertIs<Verdict.Breaking>(verdict(SqlRules, ns(old), ns(new)))
    }

    @Test
    fun `a declaration removed from a keyed record is breaking`() {
        val old = record("s", "R", field(1, "a"), compositeKey = listOf("a"))
        assertIs<Verdict.Breaking>(verdict(SqlRules, ns(old), namespace("s")))
    }

    @Test
    fun `a declaration removed from an unkeyed record is compatible`() {
        val old = record("s", "R", field(1, "a"))
        assertEquals(Verdict.Compatible, verdict(SqlRules, ns(old), namespace("s")))
    }

    @Test
    fun `a declaration removed from an unkeyed record used by a list of record field is breaking`() {
        val line = record("s", "Line", field(1, "sku"))
        val order = record("s", "Order", field(1, "lines", ListOf(Ref(qn("s", "Line")), false)))
        val old = namespace("s", listOf(line, order))
        val new = namespace("s", listOf(order))
        assertIs<Verdict.Breaking>(verdict(SqlRules, old, new))
    }

    @Test
    fun `a namespace removed with no tables is compatible`() {
        val removed = namespace("s", listOf(record("s", "R", field(1, "a"))))
        val kept = namespace("other")
        val oldSchema = Schema(listOf(removed, kept))
        val newSchema = Schema(listOf(kept))
        val change = Differ.diff(oldSchema, newSchema).single()
        assertEquals(
            Verdict.Compatible,
            SqlRules.classify(change, ChangeContext(oldSchema, newSchema)),
        )
    }

    @Test
    fun `a namespace removed with a keyed table is breaking`() {
        val removed =
            namespace("s", listOf(record("s", "R", field(1, "a"), compositeKey = listOf("a"))))
        val kept = namespace("other")
        val oldSchema = Schema(listOf(removed, kept))
        val newSchema = Schema(listOf(kept))
        val change = Differ.diff(oldSchema, newSchema).single()
        assertIs<Verdict.Breaking>(SqlRules.classify(change, ChangeContext(oldSchema, newSchema)))
    }

    @Test
    fun `a declaration added is compatible`() {
        val new = record("s", "R", field(1, "a"))
        assertEquals(Verdict.Compatible, verdict(SqlRules, namespace("s"), ns(new)))
    }

    @Test
    fun `a declaration's kind changed is breaking`() {
        val old = record("s", "R", field(1, "a"))
        val new = union("s", "R", member(1, Scalar(Builtin.BOOL)))
        assertIs<Verdict.Breaking>(verdict(SqlRules, ns(old), ns(new)))
    }

    @Test
    fun `a model key added is breaking`() {
        val old = record("s", "R", field(1, "a"))
        val new = record("s", "R", field(1, "a"), compositeKey = listOf("a"))
        assertIs<Verdict.Breaking>(verdict(SqlRules, ns(old), ns(new)))
    }

    @Test
    fun `a sql strategy annotation change is breaking`() {
        val old = record("s", "R", field(1, "a"))
        val new =
            record(
                "s",
                "R",
                field(1, "a"),
                annotations =
                    Annotations(mapOf("sql" to mapOf("strategy" to AnnotationValue.Str("joined")))),
            )
        assertIs<Verdict.Breaking>(verdict(SqlRules, ns(old), ns(new)))
    }

    @Test
    fun `a sql table annotation change is breaking`() {
        val old = record("s", "R", field(1, "a"))
        val new =
            record(
                "s",
                "R",
                field(1, "a"),
                annotations =
                    Annotations(mapOf("sql" to mapOf("table" to AnnotationValue.Str("renamed")))),
            )
        assertIs<Verdict.Breaking>(verdict(SqlRules, ns(old), ns(new)))
    }

    @Test
    fun `a sql column annotation change is breaking`() {
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
                            mapOf("sql" to mapOf("column" to AnnotationValue.Str("renamed")))
                        ),
                ),
            )
        assertIs<Verdict.Breaking>(verdict(SqlRules, ns(old), ns(new)))
    }

    @Test
    fun `a sql schema pin on a namespace changed to a new name is breaking`() {
        val decl = record("shop.orders", "R", field(1, "a"))
        val old = namespace("shop.orders", listOf(decl))
        val new = namespace("shop.orders", listOf(decl), schemaPin("orders_v2"))
        assertIs<Verdict.Breaking>(verdict(SqlRules, old, new))
    }

    @Test
    fun `a sql schema pin on a namespace matching its last segment is compatible`() {
        val decl = record("shop.orders", "R", field(1, "a"))
        val old = namespace("shop.orders", listOf(decl))
        val new = namespace("shop.orders", listOf(decl), schemaPin("orders"))
        assertEquals(Verdict.Compatible, verdict(SqlRules, old, new))
    }

    private fun schemaPin(name: String) =
        Annotations(mapOf("sql" to mapOf("schema" to AnnotationValue.Str(name))))

    @Test
    fun `a sql key flag added to a field is breaking`() {
        val old = record("s", "R", field(1, "a"))
        val new = record("s", "R", field(1, "a", key = true))
        assertIs<Verdict.Breaking>(verdict(SqlRules, ns(old), ns(new)))
    }

    @Test
    fun `a sql column pin added with no name change is compatible`() {
        val old = record("s", "R", field(1, "a"))
        val new =
            record(
                "s",
                "R",
                field(
                    1,
                    "a",
                    annotations =
                        Annotations(mapOf("sql" to mapOf("column" to AnnotationValue.Str("a")))),
                ),
            )
        assertEquals(Verdict.Compatible, verdict(SqlRules, ns(old), ns(new)))
    }

    @Test
    fun `a sql column pin changed to a different name is breaking`() {
        val pin = { name: String ->
            Annotations(mapOf("sql" to mapOf("column" to AnnotationValue.Str(name))))
        }
        val old = record("s", "R", field(1, "a", annotations = pin("a")))
        val new = record("s", "R", field(1, "a", annotations = pin("b")))
        assertIs<Verdict.Breaking>(verdict(SqlRules, ns(old), ns(new)))
    }

    @Test
    fun `a sql column pin removed where the derived name differs is breaking`() {
        val old =
            record(
                "s",
                "R",
                field(
                    1,
                    "a",
                    annotations =
                        Annotations(
                            mapOf("sql" to mapOf("column" to AnnotationValue.Str("custom")))
                        ),
                ),
            )
        val new = record("s", "R", field(1, "a"))
        assertIs<Verdict.Breaking>(verdict(SqlRules, ns(old), ns(new)))
    }

    @Test
    fun `a sql table pin matching the derived name is compatible`() {
        val old = record("s", "R", field(1, "a"))
        val new =
            record(
                "s",
                "R",
                field(1, "a"),
                annotations =
                    Annotations(mapOf("sql" to mapOf("table" to AnnotationValue.Str("r")))),
            )
        assertEquals(Verdict.Compatible, verdict(SqlRules, ns(old), ns(new)))
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
        assertEquals(Verdict.Compatible, verdict(SqlRules, ns(old), ns(new)))
    }

    @Test
    fun `a reservation change is compatible`() {
        val old = record("s", "R", field(1, "a"), reserved = Reserved(listOf(5..5), setOf("x")))
        val new = record("s", "R", field(1, "a"), reserved = Reserved.NONE)
        assertEquals(Verdict.Compatible, verdict(SqlRules, ns(old), ns(new)))
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
        assertEquals(Verdict.Compatible, verdict(SqlRules, ns(old), ns(new)))
    }

    @Test
    fun `a doc change is compatible`() {
        val old = record("s", "R", field(1, "a"))
        val new = old.copy(doc = "updated")
        assertEquals(Verdict.Compatible, verdict(SqlRules, ns(old), ns(new)))
    }

    @Test
    fun `a list element becoming nullable is compatible`() {
        val old = record("s", "R", field(1, "a", ListOf(Scalar(Builtin.INT32), false)))
        val new = record("s", "R", field(1, "a", ListOf(Scalar(Builtin.INT32), true)))
        assertEquals(Verdict.Compatible, verdict(SqlRules, ns(old), ns(new)))
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
        assertIs<Verdict.Breaking>(verdict(SqlRules, ns(old), ns(new)))
    }
}
