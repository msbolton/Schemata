package io.schemata.evolution

import io.schemata.core.ir.AnnotationValue
import io.schemata.core.ir.Annotations
import io.schemata.core.ir.Builtin
import io.schemata.core.ir.IntValue
import io.schemata.core.ir.ListOf
import io.schemata.core.ir.Namespace
import io.schemata.core.ir.Refinements
import io.schemata.core.ir.Scalar
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Rows whose verdict differs by target, or that need more than one change to express, judged by
 * every rulebook at once: each test states the verdict word per target, in report order.
 */
class RulebookRowsTest {
    private fun ann(target: String, key: String, value: AnnotationValue) =
        Annotations(mapOf(target to mapOf(key to value)))

    private fun ann(vararg entries: Triple<String, String, AnnotationValue>) =
        Annotations(
            entries
                .groupBy { it.first }
                .mapValues { (_, keys) -> keys.associate { it.second to it.third } }
        )

    private fun word(v: Verdict) =
        when (v) {
            is Verdict.Compatible -> "compatible"
            is Verdict.Note -> "note"
            is Verdict.Breaking -> "breaking"
        }

    /** Target name to the verdict words for every change between [old] and [new]. */
    private fun judged(old: List<Namespace>, new: List<Namespace>): Map<String, List<String>> =
        Rulebooks.all.associate { rb -> rb.target to verdicts(rb, old, new).map(::word) }

    private fun judged(old: Namespace, new: Namespace) = judged(listOf(old), listOf(new))

    /** The single change's verdict word per target. */
    private fun row(old: Namespace, new: Namespace): Map<String, String> =
        judged(old, new).mapValues { it.value.single() }

    private fun row(proto: String, sql: String, xsd: String, jsonschema: String) =
        mapOf("proto" to proto, "sql" to sql, "xsd" to xsd, "jsonschema" to jsonschema)

    @Test
    fun `a rename that adds every target's pin in the same step is compatible everywhere`() {
        val pins =
            ann(
                Triple("proto", "name", AnnotationValue.Str("note")),
                Triple("sql", "column", AnnotationValue.Str("note")),
                Triple("xsd", "name", AnnotationValue.Str("note")),
                Triple("jsonschema", "name", AnnotationValue.Str("note")),
            )
        val old = record("s", "Order", field(1, "id"), field(9, "note", nullable = true))
        val new =
            record(
                "s",
                "Order",
                field(1, "id"),
                field(9, "comment", nullable = true, annotations = pins),
            )
        val all = judged(ns(old), ns(new))
        all.forEach { (target, words) ->
            assertEquals(5, words.size, target)
            assertTrue(words.all { it == "compatible" }, "$target: $words")
        }
    }

    @Test
    fun `two fields swapping names judge each pin by its own ordinal`() {
        val pin = ann("sql", "column", AnnotationValue.Str("a"))
        val old = record("s", "R", field(1, "a"), field(2, "b"))
        val new = record("s", "R", field(1, "b", annotations = pin), field(2, "a"))
        // #1 renamed a to b but pinned to a, #1's pin added (still a), #2 renamed b to a
        assertEquals(
            listOf("compatible", "compatible", "breaking"),
            judged(ns(old), ns(new)).getValue("sql"),
        )
    }

    @Test
    fun `a widened type with a tightened bound breaks off proto and is a proto note`() {
        val old = record("s", "R", field(1, "n", Scalar(Builtin.INT32, max(100))))
        val new = record("s", "R", field(1, "n", Scalar(Builtin.INT64, max(10))))
        assertEquals(row("note", "breaking", "breaking", "breaking"), row(ns(old), ns(new)))
    }

    @Test
    fun `a uuid relaxed into a bounded string breaks the document targets`() {
        val old = record("s", "R", field(1, "id", Scalar(Builtin.UUID)))
        val new = record("s", "R", field(1, "id", Scalar(Builtin.STRING, max(10))))
        assertEquals(row("note", "breaking", "breaking", "breaking"), row(ns(old), ns(new)))
    }

    @Test
    fun `a list element bound tightened breaks off proto`() {
        fun tags(max: Int) = ListOf(Scalar(Builtin.STRING, max(max)), false)
        val old = record("s", "R", field(1, "tags", tags(20)))
        val new = record("s", "R", field(1, "tags", tags(10)))
        assertEquals(row("note", "breaking", "breaking", "breaking"), row(ns(old), ns(new)))
        assertEquals(
            row("compatible", "compatible", "compatible", "compatible"),
            row(ns(new), ns(old)),
        )
    }

    @Test
    fun `nullable to non-null with a default stays valid on xsd alone`() {
        val old = record("s", "R", field(1, "n", Scalar(Builtin.INT32), nullable = true))
        val new = record("s", "R", field(1, "n", Scalar(Builtin.INT32), default = IntValue(0)))
        // nullability, then the default added
        assertEquals(
            mapOf(
                "proto" to listOf("note", "note"),
                "sql" to listOf("breaking", "compatible"),
                "xsd" to listOf("compatible", "note"),
                "jsonschema" to listOf("breaking", "compatible"),
            ),
            judged(ns(old), ns(new)),
        )
    }

    @Test
    fun `a default removed from a non-null field breaks every target but proto`() {
        val old = record("s", "R", field(1, "n", Scalar(Builtin.INT32), default = IntValue(0)))
        val new = record("s", "R", field(1, "n", Scalar(Builtin.INT32)))
        assertEquals(row("note", "breaking", "breaking", "breaking"), row(ns(old), ns(new)))
    }

    @Test
    fun `a namespace removed is judged by its declarations`() {
        val keep = namespace("keep", listOf(record("keep", "K", field(1, "a"))))
        val rooted = namespace("gone", listOf(record("gone", "R", field(1, "a"))))
        val enumOnly = namespace("gone", listOf(enum("gone", "E", value(1, "A"))))
        val keyed =
            namespace(
                "gone",
                listOf(
                    record(
                        "gone",
                        "T",
                        field(1, "id", annotations = ann("sql", "key", AnnotationValue.Flag)),
                    )
                ),
            )
        val empty = namespace("gone")
        fun removed(gone: Namespace) =
            judged(listOf(keep, gone), listOf(keep)).mapValues { it.value.single() }
        assertEquals(row("note", "compatible", "breaking", "breaking"), removed(rooted))
        assertEquals(row("note", "compatible", "note", "breaking"), removed(enumOnly))
        assertEquals(row("note", "breaking", "breaking", "breaking"), removed(keyed))
        assertEquals(row("compatible", "compatible", "compatible", "compatible"), removed(empty))
    }

    @Test
    fun `an xsd name override changed on a root record breaks xsd alone`() {
        val old = record("s", "R", field(1, "a"))
        val new = record("s", "R", field(1, "a"), annotations = xsdName("Root"))
        assertEquals(
            row("compatible", "compatible", "breaking", "compatible"),
            row(ns(old), ns(new)),
        )
    }

    @Test
    fun `an xsd name override changed on a record without a root element is an xsd note`() {
        val inner = record("s", "Inner", field(1, "a")).copy(qualifiedName = qn("s", "R", "Inner"))
        val pinned = inner.copy(annotations = xsdName("Nested"))
        val old = record("s", "R", field(1, "a")).copy(nested = listOf(inner))
        val new = record("s", "R", field(1, "a")).copy(nested = listOf(pinned))
        val notRoot =
            record(
                "s",
                "Q",
                field(1, "a"),
                annotations = ann("xsd", "root", AnnotationValue.Bool(false)),
            )
        val notRootPinned =
            notRoot.copy(
                annotations =
                    ann(
                        Triple("xsd", "root", AnnotationValue.Bool(false)),
                        Triple("xsd", "name", AnnotationValue.Str("Queue")),
                    )
            )
        assertEquals(row("compatible", "compatible", "note", "compatible"), row(ns(old), ns(new)))
        assertEquals(
            row("compatible", "compatible", "note", "compatible"),
            row(ns(notRoot), ns(notRootPinned)),
        )
    }

    @Test
    fun `a jsonschema name override changed on a declaration breaks jsonschema alone`() {
        val old = enum("s", "E", value(1, "A"))
        val new = old.copy(annotations = ann("jsonschema", "name", AnnotationValue.Str("Status")))
        assertEquals(
            row("compatible", "compatible", "compatible", "breaking"),
            row(ns(old), ns(new)),
        )
    }

    @Test
    fun `a proto name override changed on a declaration is a proto note`() {
        val old = record("s", "R", field(1, "a"))
        val new =
            record(
                "s",
                "R",
                field(1, "a"),
                annotations = ann("proto", "name", AnnotationValue.Str("Rec")),
            )
        assertEquals(row("note", "compatible", "compatible", "compatible"), row(ns(old), ns(new)))
    }

    @Test
    fun `sql unique added breaks sql and removed is compatible`() {
        val plain = record("s", "R", field(1, "a"))
        val unique =
            record(
                "s",
                "R",
                field(1, "a", annotations = ann("sql", "unique", AnnotationValue.Flag)),
            )
        assertEquals(
            row("compatible", "breaking", "compatible", "compatible"),
            row(ns(plain), ns(unique)),
        )
        assertEquals(
            row("compatible", "compatible", "compatible", "compatible"),
            row(ns(unique), ns(plain)),
        )
    }

    @Test
    fun `sql index changed is compatible and sql type changed breaks sql`() {
        val plain = record("s", "R", field(1, "a", Scalar(Builtin.STRING)))
        val indexed =
            record(
                "s",
                "R",
                field(
                    1,
                    "a",
                    Scalar(Builtin.STRING),
                    annotations = ann("sql", "index", AnnotationValue.Flag),
                ),
            )
        val retyped =
            record(
                "s",
                "R",
                field(
                    1,
                    "a",
                    Scalar(Builtin.STRING),
                    annotations = ann("sql", "type", AnnotationValue.Str("citext")),
                ),
            )
        assertEquals(
            row("compatible", "compatible", "compatible", "compatible"),
            row(ns(plain), ns(indexed)),
        )
        assertEquals(
            row("compatible", "breaking", "compatible", "compatible"),
            row(ns(plain), ns(retyped)),
        )
    }

    @Test
    fun `an sql key this rulebook does not know is assumed breaking`() {
        val plain = record("s", "R", field(1, "a"))
        val odd =
            record(
                "s",
                "R",
                field(1, "a", annotations = ann("sql", "collation", AnnotationValue.Str("C"))),
            )
        assertEquals("breaking", row(ns(plain), ns(odd)).getValue("sql"))
    }

    @Test
    fun `a representation key is only read by the target that defines it`() {
        val plain = record("s", "R", field(1, "a"))
        val xsdOpen =
            record("s", "R", field(1, "a"), annotations = ann("xsd", "open", AnnotationValue.Flag))
        val jsonAttribute =
            record(
                "s",
                "R",
                field(1, "a", annotations = ann("jsonschema", "attribute", AnnotationValue.Flag)),
            )
        assertEquals("compatible", row(ns(xsdOpen), ns(plain)).getValue("xsd"))
        assertEquals("compatible", row(ns(plain), ns(jsonAttribute)).getValue("jsonschema"))
    }

    private fun max(n: Int) = Refinements(max = BigDecimal(n))

    private fun xsdName(name: String) = ann("xsd", "name", AnnotationValue.Str(name))
}
