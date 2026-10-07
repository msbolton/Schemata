package io.schemata.core

import io.schemata.core.ir.ListOf
import io.schemata.core.ir.QualifiedName
import io.schemata.core.ir.RecordType
import io.schemata.core.ir.Scalar
import io.schemata.core.ir.UnionType
import io.schemata.core.ir.keyFields
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OptionsTest {
    @Test
    fun `min max and match lower to refinements`() {
        val r =
            analyze2(
                "schema s\nmodel M { name string { min 2, max 100, match \"^[a-z]+$\" }  age int32 { min 0, max 150 } }"
            )
        val m = r.schema!!.lookup(QualifiedName("s", listOf("M"))) as RecordType
        val name = (m.fields[0].type as Scalar).refinements
        assertEquals(BigDecimal(2), name.min)
        assertEquals(BigDecimal(100), name.max)
        assertEquals("^[a-z]+$", name.pattern)
        assertEquals(BigDecimal(150), (m.fields[1].type as Scalar).refinements.max)
    }

    @Test
    fun `list options split between the list and its elements`() {
        val r = analyze2("schema s\nmodel M { tags string[] { minItems 1, maxItems 10, max 20 } }")
        val t =
            (r.schema!!.lookup(QualifiedName("s", listOf("M"))) as RecordType).fields[0].type
                as ListOf
        assertEquals(BigDecimal(1), t.refinements.min)
        assertEquals(BigDecimal(10), t.refinements.max)
        assertEquals(BigDecimal(20), (t.element as Scalar).refinements.max)
    }

    @Test
    fun `id unique and index become field flags and a composite key keeps its order`() {
        val r =
            analyze2(
                "schema s\nmodel M { a uuid { id }  b string { unique }  c int32 { index }  @@id(b, a) }"
            )
        val m = r.schema!!.lookup(QualifiedName("s", listOf("M"))) as RecordType
        assertTrue(m.fields[0].key)
        assertTrue(m.fields[1].unique)
        assertTrue(m.fields[2].index)
        assertEquals(listOf("b", "a"), m.compositeKey)
        assertEquals(listOf("b", "a"), m.keyFields().map { it.name })
    }

    @Test
    fun `an option the type cannot carry is SCH1049`() {
        val r =
            analyze2(
                "schema s\nmodel M { n int32 { match \"x\" }  tags string[] { id }  s string { minItems 1 } }"
            )
        assertEquals(listOf("SCH1049", "SCH1049", "SCH1049"), r.diagnostics.map { it.code.id })
    }

    @Test
    fun `decimal keeps precision and scale as its type`() {
        val r = analyze2("schema s\nmodel M { total decimal(19, 4) { max 1000 } }")
        val s =
            (r.schema!!.lookup(QualifiedName("s", listOf("M"))) as RecordType).fields[0].type
                as Scalar
        assertEquals(19, s.refinements.precision)
        assertEquals(4, s.refinements.scale)
        assertEquals(BigDecimal(1000), s.refinements.max)
    }

    private fun codes(text: String) = analyze2(text).diagnostics.map { it.code.id }

    private fun model(r: AnalysisResult, name: String = "M") =
        r.schema!!.lookup(QualifiedName("s", listOf(name))) as RecordType

    @Test
    fun `model lists keep their field names in order`() {
        val r =
            analyze2(
                "schema s\nmodel M { a int32  b int32  c int32  @@unique(b, a)  @@index(c)  @@index(a, c) }"
            )
        assertEquals(emptyList(), r.diagnostics)
        assertEquals(listOf(listOf("b", "a")), model(r).uniques)
        assertEquals(listOf(listOf("c"), listOf("a", "c")), model(r).indexes)
        assertEquals(emptyList(), model(r).keyFields())
    }

    @Test
    fun `flagged fields form the key in declaration order without a model key`() {
        val r = analyze2("schema s\nmodel M { b string { id }  x int32  a uuid { id } }")
        assertEquals(listOf("b", "a"), model(r).keyFields().map { it.name })
    }

    @Test
    fun `a model list naming no field or an unknown one is SCH1018`() {
        assertEquals(listOf("SCH1018"), codes("schema s\nmodel M { a int32  @@id(a, z) }"))
        assertEquals(listOf("SCH1018"), codes("schema s\nmodel M { a int32  @@unique() }"))
        assertEquals(listOf("SCH1018"), codes("schema s\nmodel M { a int32  @@index(a, a) }"))
        assertEquals(listOf("SCH1036"), codes("schema s\nmodel M { a int32  @@id(a)  @@id(a) }"))
    }

    @Test
    fun `option values are checked like refinements`() {
        assertEquals(listOf("SCH1038"), codes("schema s\nmodel M { n int32 { max 1.5 } }"))
        assertEquals(listOf("SCH1038"), codes("schema s\nmodel M { n int32 { min 5, max 1 } }"))
        assertEquals(listOf("SCH1038"), codes("schema s\nmodel M { s string { match \"(\" } }"))
        assertEquals(listOf("SCH1038"), codes("schema s\nmodel M { s string { max -1 } }"))
        assertEquals(listOf("SCH1038"), codes("schema s\nmodel M { a uuid { id 1 } }"))
        assertEquals(listOf("SCH1041"), codes("schema s\nmodel M { s string { max 1, max 2 } }"))
        assertEquals(listOf("SCH1049"), codes("schema s\nmodel M { s string { nope } }"))
    }

    @Test
    fun `a default is judged against the bounds its options set`() {
        assertEquals(listOf("SCH1043"), codes("schema s\nmodel M { n int32 { max 10 } = 11 }"))
    }

    @Test
    fun `flags fit scalars enums and references but not collections`() {
        val text =
            "schema s\nenum E { a }\nmodel R { k uuid { id } }\n" +
                "model M { e E { id }  r R { unique }  m map<string, int32> { index }  l R[] { embed } }"
        assertEquals(listOf("SCH1049"), codes(text))
        assertEquals(
            listOf("SCH1049"),
            codes("schema s\nmodel R { k uuid { id } }\nmodel M { r R { id } }"),
        )
    }

    @Test
    fun `unique and index fit records unions and inline shapes but not lists or maps`() {
        val r =
            analyze2(
                "schema s\nmodel R { k uuid { id } }\nmodel C { x int32 }\nunion U = R | C\n" +
                    "model M { r R { unique }  c C { index }  u U { unique, index }  a { x int32 } { unique } }"
            )
        assertEquals(emptyList(), r.diagnostics)
        val m = model(r)
        assertEquals(listOf(true, false, true, true), m.fields.map { it.unique })
        assertEquals(listOf(false, true, true, false), m.fields.map { it.index })
        assertEquals(
            listOf("SCH1049", "SCH1049", "SCH1049"),
            codes(
                "schema s\nmodel R { k uuid { id } }\n" +
                    "model M { l R[] { unique }  m map<string, int32> { index }  s string[] { unique } }"
            ),
        )
    }

    @Test
    fun `union member options lower to the member's refinements`() {
        val r =
            analyze2("schema s\nunion U = #1 string { max 5, match \"^a\" } | #2 int32 { min 0 }")
        assertEquals(emptyList(), r.diagnostics)
        val u = r.schema!!.lookup(QualifiedName("s", listOf("U"))) as UnionType
        val s = (u.members[0].type as Scalar).refinements
        assertEquals(BigDecimal(5), s.max)
        assertEquals("^a", s.pattern)
        assertEquals(BigDecimal.ZERO, (u.members[1].type as Scalar).refinements.min)
        assertEquals(listOf("SCH1049"), codes("schema s\nunion U = string { id } | int32"))
        assertEquals(listOf("SCH1049"), codes("schema s\nunion U = string { minItems 1 } | int32"))
    }

    @Test
    fun `bytes take a length and uuid takes no bound`() {
        val r = analyze2("schema s\nmodel M { b bytes { max 16 } }")
        assertEquals(BigDecimal(16), (model(r).fields[0].type as Scalar).refinements.max)
        assertEquals(listOf("SCH1049"), codes("schema s\nmodel M { u uuid { max 1 } }"))
    }

    @Test
    fun `options on a type argument bound that argument and flags there are SCH1049`() {
        val r =
            analyze2(
                "schema s\nmodel M { m map<string { max 10 }, int32 { min 0 }> { maxItems 5 } }"
            )
        assertEquals(emptyList(), r.diagnostics)
        val m = model(r).fields[0].type as io.schemata.core.ir.MapOf
        assertEquals(BigDecimal(10), (m.key as Scalar).refinements.max)
        assertEquals(BigDecimal(0), (m.value as Scalar).refinements.min)
        assertEquals(BigDecimal(5), m.refinements.max)
        assertEquals(listOf("SCH1049"), codes("schema s\nmodel M { m map<string { id }, int32> }"))
        assertEquals(
            listOf("SCH1049"),
            codes("schema s\nmodel M { m map<string, int32> { max 3 } }"),
        )
    }

    @Test
    fun `options on an alias apply at every use and a use may not bound it again`() {
        val r =
            analyze2(
                "schema s\nalias Email = string { max 254 }\nmodel M { e Email  f Email { unique } }"
            )
        assertEquals(emptyList(), r.diagnostics)
        assertEquals(BigDecimal(254), (model(r).fields[0].type as Scalar).refinements.max)
        assertTrue(model(r).fields[1].unique)
        assertEquals(
            listOf("SCH1049"),
            codes("schema s\nalias Email = string { max 254 }\nmodel M { e Email { max 10 } }"),
        )
    }
}
