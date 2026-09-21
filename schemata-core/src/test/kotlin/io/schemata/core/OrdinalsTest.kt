package io.schemata.core

import io.schemata.core.ir.EnumType
import io.schemata.core.ir.QualifiedName
import io.schemata.core.ir.RecordType
import io.schemata.core.ir.Reserved
import io.schemata.core.ir.UnionType
import io.schemata.lang.Parser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
            "namespace a\nrecord R {\n  #4 d: bool\n  #2 b: bool\n  reserved #1, #5..#7, \"old\"\n}\nenum E { #10 x, #20 y\n reserved #15 }\nrecord C {}\nunion U = #3 C | #1 uuid"
        val r = analyze(src)
        assertEquals(emptyList(), r.diagnostics)
        val rec = r.schema!!.lookup(qn("R")) as RecordType
        assertEquals(listOf(4, 2), rec.fields.map { it.ordinal })
        assertEquals(Reserved(setOf(1, 5, 6, 7), setOf("old")), rec.reserved)
        val e = r.schema!!.lookup(qn("E")) as EnumType
        assertEquals(listOf(10, 20), e.values.map { it.ordinal })
        assertEquals(Reserved(setOf(15), emptySet()), e.reserved)
        val u = r.schema!!.lookup(qn("U")) as UnionType
        assertEquals(listOf(3, 1), u.members.map { it.ordinal })
    }

    @Test
    fun `implicit ordinals are declaration order`() {
        val rec =
            analyze("namespace a\nrecord R { a: bool  b: bool  c: bool }").schema!!.lookup(qn("R"))
                as RecordType
        assertEquals(listOf(1, 2, 3), rec.fields.map { it.ordinal })
    }

    @Test
    fun `mixing explicit and implicit is an error at the declaration name`() {
        val r = analyze("namespace a\nrecord R {\n  #1 a: bool\n  b: bool\n}\nenum E { #1 x, y }")
        assertNull(r.schema)
        assertEquals(
            listOf(
                "2:8 record 'R' mixes explicit and implicit ordinals",
                "6:6 enum 'E' mixes explicit and implicit ordinals",
            ),
            messages(r),
        )
    }

    @Test
    fun `strict mode rejects every implicit ordinal`() {
        val r =
            analyze(
                "namespace a\nrecord R { a: bool  b: bool }\nrecord C {}\nunion U = C | uuid",
                strict = true,
            )
        assertNull(r.schema)
        assertEquals(
            listOf(
                "2:12 field 'a' has no explicit ordinal (--strict)",
                "2:21 field 'b' has no explicit ordinal (--strict)",
                "4:11 member 'C' has no explicit ordinal (--strict)",
                "4:15 member 'uuid' has no explicit ordinal (--strict)",
            ),
            messages(r),
        )
        assertEquals(
            emptyList(),
            analyze("namespace a\nrecord R { #1 a: bool }", strict = true).diagnostics,
        )
    }

    @Test
    fun `duplicates, zero, and reserved conflicts point at the ordinal or name`() {
        val src =
            "namespace a\nrecord R {\n  #3 a: bool\n  #3 b: bool\n  #0 c: bool\n  #9 old: bool\n  #5 e: bool\n  reserved #5, \"old\", #7..#6\n}"
        val r = analyze(src)
        assertNull(r.schema)
        assertEquals(
            listOf(
                "8:23 reserved range #7..#6 is inverted",
                "4:3 ordinal #3 is used more than once in record 'R'",
                "5:3 ordinal #0 is not positive",
                "6:6 name 'old' is reserved in record 'R'",
                "7:3 ordinal #5 is reserved in record 'R'",
            ),
            messages(r),
        )
    }
}
