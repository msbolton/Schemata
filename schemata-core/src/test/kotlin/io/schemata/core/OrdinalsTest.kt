package io.schemata.core

import io.schemata.core.ir.EnumType
import io.schemata.core.ir.QualifiedName
import io.schemata.core.ir.RecordType
import io.schemata.core.ir.Reserved
import io.schemata.core.ir.UnionType
import io.schemata.lang.Parser
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OrdinalsTest {
    private fun analyze(src: String, strict: Boolean = false): AnalysisResult =
        Analyzer.analyze(
            listOf(Parser.parse(src, "t").file!!),
            AnalysisOptions(strictOrdinals = strict),
        )

    private fun messages(r: AnalysisResult) =
        r.diagnostics.map { "${it.span.startLine}:${it.span.startColumn} ${it.message}" }

    private fun qn(vararg path: String) = QualifiedName("a", path.toList())

    @Test
    fun `explicit ordinals are honoured and reserved is recorded`() {
        val src =
            "schema a\nmodel R {\n  #4 d bool\n  #2 b bool\n  reserved #1, #5..#7, \"old\"\n}\nenum E { #10 x, #20 y\n reserved #15 }\nmodel C {}\nunion U = #3 C | #1 uuid"
        val r = analyze(src)
        assertEquals(emptyList(), r.diagnostics)
        val rec = r.schema!!.lookup(qn("R")) as RecordType
        assertEquals(listOf(4, 2), rec.fields.map { it.ordinal })
        assertEquals(Reserved(listOf(1..1, 5..7), setOf("old")), rec.reserved)
        val e = r.schema!!.lookup(qn("E")) as EnumType
        assertEquals(listOf(10, 20), e.values.map { it.ordinal })
        assertEquals(Reserved(listOf(15..15), emptySet()), e.reserved)
        val u = r.schema!!.lookup(qn("U")) as UnionType
        assertEquals(listOf(3, 1), u.members.map { it.ordinal })
    }

    @Test
    fun `implicit ordinals are declaration order`() {
        val rec =
            analyze("schema a\nmodel R { a bool  b bool  c bool }").schema!!.lookup(qn("R"))
                as RecordType
        assertEquals(listOf(1, 2, 3), rec.fields.map { it.ordinal })
    }

    @Test
    fun `mixing explicit and implicit is an error at the declaration name`() {
        val r = analyze("schema a\nmodel R {\n  #1 a bool\n  b bool\n}\nenum E { #1 x, y }")
        assertNull(r.schema)
        assertEquals(
            listOf(
                "2:7 model 'R' mixes explicit and implicit ordinals",
                "6:6 enum 'E' mixes explicit and implicit ordinals",
            ),
            messages(r),
        )
    }

    @Test
    fun `strict mode rejects every implicit ordinal`() {
        val r =
            analyze(
                "schema a\nmodel R { a bool  b bool }\nmodel C {}\nunion U = C | uuid",
                strict = true,
            )
        assertNull(r.schema)
        assertEquals(
            listOf(
                "2:11 field 'a' has no explicit ordinal (--strict)",
                "2:19 field 'b' has no explicit ordinal (--strict)",
                "4:11 member 'C' has no explicit ordinal (--strict)",
                "4:15 member 'uuid' has no explicit ordinal (--strict)",
            ),
            messages(r),
        )
        assertEquals(
            emptyList(),
            analyze("schema a\nmodel R { #1 a bool }", strict = true).diagnostics,
        )
    }

    @Test
    fun `duplicates, zero, and reserved conflicts point at the ordinal or name`() {
        val src =
            "schema a\nmodel R {\n  #3 a bool\n  #3 b bool\n  #0 c bool\n  #9 old bool\n  #5 e bool\n  reserved #5, \"old\", #7..#6\n}"
        val r = analyze(src)
        assertNull(r.schema)
        assertEquals(
            listOf(
                "8:23 reserved range #7..#6 is inverted",
                "4:3 ordinal #3 is used more than once in model 'R'",
                "5:3 ordinal #0 is not positive",
                "6:6 name 'old' is reserved in model 'R'",
                "7:3 ordinal #5 is reserved in model 'R'",
            ),
            messages(r),
        )
    }

    @Test
    fun `a huge reserved range is kept as a range and still conflicts`() {
        val r = analyze("schema a\nmodel R {\n  #5 x bool\n  reserved #1..#2000000000\n}")
        assertEquals(listOf("3:3 ordinal #5 is reserved in model 'R'"), messages(r))
    }

    @Test
    fun `next free ordinal skips used and reserved`() {
        assertEquals(5, Ordinals.nextFree(setOf(1, 2, 4), listOf(3..3)))
    }

    @Test
    fun `next free ordinal jumps over a huge reserved range instead of counting through it`() {
        val start = System.nanoTime()
        assertEquals(2000000001, Ordinals.nextFree(emptySet(), listOf(1..2000000000)))
        val elapsedMillis = (System.nanoTime() - start) / 1_000_000
        assertTrue(elapsedMillis < 1000, "took ${elapsedMillis}ms")
    }

    @Test
    fun `next free ordinal stops at the maximum instead of wrapping`() {
        assertEquals(Int.MAX_VALUE, Ordinals.nextFree(emptySet(), listOf(1..Int.MAX_VALUE)))
    }

    @Test
    fun `a duplicate ordinal's help skips a reserved candidate too`() {
        val r = analyze("schema a\nmodel R {\n  #1 x bool\n  #1 y bool\n  reserved #2\n}")
        assertEquals(
            "give each element its own ordinal; the next free one is #3",
            r.diagnostics.single { it.code.id == "SCH1019" }.help,
        )
    }

    @Test
    fun `the next free ordinal considers every explicit ordinal, not only those seen so far`() {
        val r = analyze("schema a\nmodel R { #1 a bool  #1 b bool  #2 c bool }")
        assertEquals(
            "give each element its own ordinal; the next free one is #3",
            r.diagnostics.single { it.code.id == "SCH1019" }.help,
        )
    }

    @Test
    fun `the next free ordinal is unaffected by scan order across a duplicate and a reservation`() {
        val r = analyze("schema a\nenum E { #1 x, #4 y, #4 z, #2 w\n reserved #3 }")
        assertEquals(
            "give each element its own ordinal; the next free one is #5",
            r.diagnostics.single { it.code.id == "SCH1019" }.help,
        )
    }

    /** The scan `nextFree` replaced: every step looks through all the ranges again. */
    private fun rescanning(used: Set<Int>, reserved: List<IntRange>): Int {
        var candidate = 1
        while (true) {
            val hit = reserved.firstOrNull { candidate in it }
            when {
                hit != null -> {
                    if (hit.last == Int.MAX_VALUE) return Int.MAX_VALUE
                    candidate = hit.last + 1
                }
                candidate in used -> {
                    if (candidate == Int.MAX_VALUE) return Int.MAX_VALUE
                    candidate++
                }
                else -> return candidate
            }
        }
    }

    @Test
    fun `the next free ordinal sweeps overlapping ranges to the same answer as a rescan`() {
        val random = Random(90)
        repeat(2000) {
            val ranges =
                List(random.nextInt(0, 7)) {
                    val from = random.nextInt(-2, 40)
                    val to =
                        if (random.nextInt(25) == 0) Int.MAX_VALUE
                        else from + random.nextInt(-2, 12)
                    from..to
                }
            val used = List(random.nextInt(0, 15)) { random.nextInt(1, 40) }.toSet()
            assertEquals(rescanning(used, ranges), Ordinals.nextFree(used, ranges), "$used $ranges")
        }
    }

    @Test
    fun `the next free ordinal steps over used ones that touch a reserved range`() {
        assertEquals(9, Ordinals.nextFree(setOf(1, 2, 8), listOf(3..5, 4..7)))
        assertEquals(1, Ordinals.nextFree(emptySet(), emptyList()))
        assertEquals(Int.MAX_VALUE, Ordinals.nextFree(setOf(1), listOf(2..Int.MAX_VALUE)))
    }
}
