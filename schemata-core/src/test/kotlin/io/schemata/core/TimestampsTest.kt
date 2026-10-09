package io.schemata.core

import io.schemata.core.ir.QualifiedName
import io.schemata.core.ir.RecordType
import kotlin.test.Test
import kotlin.test.assertEquals

class TimestampsTest {
    private fun fields(text: String) =
        (analyze(text).schema!!.lookup(QualifiedName("s", listOf("M"))) as RecordType).fields

    @Test
    fun `pinned timestamps take the written ordinals`() {
        val f =
            fields(
                "schema s\nmodel M { #1 id uuid { id }  #2 name string  @@timestamps(#11, #12) }"
            )
        assertEquals(listOf(1, 2, 11, 12), f.map { it.ordinal })
        assertEquals(listOf("id", "name", "created_at", "updated_at"), f.map { it.name })
    }

    @Test
    fun `pinned timestamps on an implicit-ordinal model keep their numbers`() {
        val r = analyze("schema s\nmodel M { id uuid { id }  name string  @@timestamps(#11, #12) }")
        assertEquals(emptyList(), r.diagnostics.map { it.code.id })
        val f = (r.schema!!.lookup(QualifiedName("s", listOf("M"))) as RecordType).fields
        assertEquals(listOf(1, 2, 11, 12), f.map { it.ordinal })
    }

    @Test
    fun `pinned timestamps must be two distinct ordinals in any order`() {
        val desc = analyze("schema s\nmodel M { #1 id uuid { id }  @@timestamps(#12, #11) }")
        assertEquals(emptyList(), desc.diagnostics.map { it.code.id })
        assertEquals(
            listOf(1, 12, 11),
            (desc.schema!!.lookup(QualifiedName("s", listOf("M"))) as RecordType).fields.map {
                it.ordinal
            },
        )
        val same = analyze("schema s\nmodel M { #1 id uuid { id }  @@timestamps(#11, #11) }")
        assertEquals(listOf("SCH1019"), same.diagnostics.map { it.code.id })
        val one = analyze("schema s\nmodel M { #1 id uuid { id }  @@timestamps(#11) }")
        assertEquals(listOf("SCH1018"), one.diagnostics.map { it.code.id })
    }

    @Test
    fun `a pinned ordinal that is taken or reserved is an error`() {
        val taken =
            analyze("schema s\nmodel M { #1 id uuid { id }  #11 x int32  @@timestamps(#11, #12) }")
        assertEquals(listOf("SCH1019"), taken.diagnostics.map { it.code.id })
        val reserved =
            analyze("schema s\nmodel M { #1 id uuid { id }  reserved #12  @@timestamps(#11, #12) }")
        assertEquals(listOf("SCH1020"), reserved.diagnostics.map { it.code.id })
    }

    @Test
    fun `unpinned timestamps warn only under explicit ordinals`() {
        val explicit = analyze("schema s\nmodel M { #1 id uuid { id }  @@timestamps }")
        assertEquals(listOf("SCH1054"), explicit.diagnostics.map { it.code.id })
        assertEquals("write `@@timestamps(#2, #3)`", explicit.diagnostics[0].help)
        val implicit = analyze("schema s\nmodel M { id uuid { id }  @@timestamps }")
        assertEquals(emptyList(), implicit.diagnostics.map { it.code.id })
    }

    @Test
    fun `an empty model with timestamps gets no warning`() {
        assertEquals(
            emptyList(),
            analyze("schema s\nmodel Marker { @@timestamps }").diagnostics.map { it.code.id },
        )
    }

    private fun codes(text: String) = analyze(text).diagnostics.map { it.code.id }

    @Test
    fun `a reserved pin on an implicit model is reported once with a right help`() {
        val r =
            analyze("schema s\nmodel M { id uuid { id }  reserved #11  @@timestamps(#11, #12) }")
        assertEquals(listOf("SCH1020"), r.diagnostics.map { it.code.id })
        assertEquals("pick another ordinal; the next free one is #2", r.diagnostics[0].help)
    }

    @Test
    fun `implicit model pins are checked against positions and each other`() {
        val m = "schema s\nmodel M { id uuid { id }  name string  "
        assertEquals(listOf("SCH1019"), codes(m + "@@timestamps(#2, #12) }"))
        assertEquals(listOf("SCH1035"), codes(m + "@@timestamps(#0, #12) }"))
        assertEquals(listOf("SCH1019"), codes(m + "@@timestamps(#11, #11) }"))
    }

    @Test
    fun `a repeated well-formed timestamps is only a duplicate annotation`() {
        assertEquals(
            listOf("SCH1036"),
            codes(
                "schema s\nmodel M { #1 id uuid { id }  @@timestamps(#11, #12)  @@timestamps(#13, #14) }"
            ),
        )
    }
}
