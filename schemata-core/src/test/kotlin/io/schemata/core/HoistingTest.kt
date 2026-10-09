package io.schemata.core

import io.schemata.core.ir.Builtin
import io.schemata.core.ir.EnumRef
import io.schemata.core.ir.EnumType
import io.schemata.core.ir.QualifiedName
import io.schemata.core.ir.RecordType
import io.schemata.core.ir.Ref
import io.schemata.core.ir.Scalar
import io.schemata.core.ir.keyFields
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HoistingTest {
    @Test
    fun `an inline enum hoists to a nested enum named after model and field`() {
        val r = analyze("schema s\nmodel Order { status enum { pending paid } = pending }")
        val order = r.schema!!.lookup(QualifiedName("s", listOf("Order"))) as RecordType
        val status = order.nested.single() as EnumType
        assertEquals("OrderStatus", status.name)
        assertEquals(
            QualifiedName("s", listOf("Order", "OrderStatus")),
            (order.fields[0].type as Ref).target,
        )
        assertEquals(
            EnumRef(QualifiedName("s", listOf("Order", "OrderStatus")), "pending"),
            order.fields[0].default,
        )
    }

    @Test
    fun `nested inline shapes hoist with chained names`() {
        val r =
            analyze(
                "schema s\nmodel Order { address { geo { lat float64  lng float64 }  city string } }"
            )
        val order = r.schema!!.lookup(QualifiedName("s", listOf("Order"))) as RecordType
        val address = order.nested.single() as RecordType
        assertEquals("OrderAddress", address.name)
        assertEquals("OrderAddressGeo", (address.nested.single() as RecordType).name)
    }

    @Test
    fun `name overrides the hoisted name and a collision is SCH1053`() {
        val ok = analyze("schema s\nmodel Order { shipping { street string } @name(\"Address\") }")
        assertEquals(
            "Address",
            ((ok.schema!!.lookup(QualifiedName("s", listOf("Order"))) as RecordType)
                    .nested
                    .single())
                .name,
        )
        val bad =
            analyze("schema s\nmodel Order { status enum { a }  model OrderStatus { x int32 } }")
        assertEquals(listOf("SCH1053"), bad.diagnostics.map { it.code.id })
    }

    @Test
    fun `timestamps append two fields with the next ordinals`() {
        val r = analyze("schema s\nmodel M { #1 id uuid { id }  #2 name string  @@timestamps }")
        val m = r.schema!!.lookup(QualifiedName("s", listOf("M"))) as RecordType
        assertEquals(listOf("id", "name", "created_at", "updated_at"), m.fields.map { it.name })
        assertEquals(listOf(1, 2, 3, 4), m.fields.map { it.ordinal })
        assertEquals(Builtin.INSTANT, (m.fields[2].type as Scalar).builtin)
        assertFalse(m.fields[2].nullable)
        assertTrue(m.fields[3].nullable)
    }

    private fun codes(text: String) = analyze(text).diagnostics.map { it.code.id }

    private fun model(r: AnalysisResult, vararg path: String) =
        r.schema!!.lookup(QualifiedName("s", path.toList())) as RecordType

    @Test
    fun `timestamps take implicit positions when the fields have no ordinals`() {
        val r = analyze("schema s\nmodel M { a int32  @@timestamps }")
        assertEquals(emptyList(), r.diagnostics)
        assertEquals(listOf(1, 2, 3), model(r, "M").fields.map { it.ordinal })
    }

    @Test
    fun `timestamps step over reserved ordinals`() {
        val r = analyze("schema s\nmodel M { #1 a int32  reserved #2..#3, #5  @@timestamps }")
        assertEquals(listOf("SCH1054"), r.diagnostics.map { it.code.id })
        assertEquals(listOf(1, 4, 6), model(r, "M").fields.map { it.ordinal })
    }

    @Test
    fun `timestamps take no arguments`() {
        assertEquals(listOf("SCH1018"), codes("schema s\nmodel M { a int32  @@timestamps(x) }"))
    }

    @Test
    fun `an ordinal on an attribute other than timestamps is SCH1018`() {
        assertEquals(listOf("SCH1018"), codes("schema s\nmodel M { @deprecated(#3) #1 a int32 }"))
    }

    @Test
    fun `an inline enum as a list element hoists and the field stays a list`() {
        val r = analyze("schema s\nmodel Post { tags enum { news sport }[] }")
        val tags = model(r, "Post").fields[0].type as io.schemata.core.ir.ListOf
        assertEquals(QualifiedName("s", listOf("Post", "PostTags")), (tags.element as Ref).target)
    }

    @Test
    fun `an inline shape keeps its own block attributes`() {
        val r = analyze("schema s\nmodel Order { line { sku string  @@timestamps } }")
        assertEquals(
            listOf("sku", "created_at", "updated_at"),
            model(r, "Order", "OrderLine").fields.map { it.name },
        )
    }

    @Test
    fun `name on a field without an inline type is SCH1017`() {
        assertEquals(listOf("SCH1017"), codes("schema s\nmodel M { a int32 @name(\"X\") }"))
    }

    @Test
    fun `two inline types given one name collide`() {
        val text = "schema s\nmodel M { a { x int32 } @name(\"P\")\n b { y int32 } @name(\"P\") }"
        assertEquals(listOf("SCH1053"), codes(text))
        val message = analyze(text).diagnostics.single().message
        assertTrue("which model 'M' hoists from field 'a'" in message, message)
        assertFalse("declares" in message, message)
    }

    @Test
    fun `a declared twin of a hoisted name still reads as declared`() {
        val r =
            analyze("schema s\nmodel Order { status enum { a }  model OrderStatus { x int32 } }")
        assertTrue("which model 'Order' already declares" in r.diagnostics.single().message)
    }

    @Test
    fun `a hoisted name from an enclosing model reads as named not declared`() {
        val text =
            "schema s\nmodel Outer {\n  inner Inner\n  kind enum { a }  @name(\"InnerKind\")\n" +
                "  model Inner { kind enum { a b } }\n}"
        val message = analyze(text).diagnostics.single().message
        assertTrue("which model 'Outer' also names" in message, message)
    }

    @Test
    fun `a single-at id on a model is an unknown annotation, not a key`() {
        val r = analyze("schema s\n@id(a)\nmodel M { a int32 }")
        assertEquals(listOf("SCH1015"), r.diagnostics.map { it.code.id })
        assertEquals(listOf("SCH1015"), codes("schema s\n@timestamps\nmodel M { a int32 }"))
    }

    @Test
    fun `timestamps after implicit ordinals step over reserved ones`() {
        val r = analyze("schema s\nmodel M { a int32  b int32  reserved #3, #5  @@timestamps }")
        assertEquals(emptyList(), r.diagnostics)
        assertEquals(listOf(1, 2, 4, 6), model(r, "M").fields.map { it.ordinal })
    }

    @Test
    fun `an id inside an inline shape is SCH1049`() {
        val r =
            analyze(
                "schema s\nmodel Order { #1 id uuid { id }  #2 shipping { code string { id }  street string } }"
            )
        assertEquals(listOf("SCH1049"), r.diagnostics.map { it.code.id })
        assertEquals("declare a nested model to give it a key", r.diagnostics.single().help)
    }

    @Test
    fun `a block id inside an inline shape is SCH1049`() {
        val r =
            analyze(
                "schema s\nmodel Order {\n  #1 id uuid { id }\n  #2 shipping { a string  b string  @@id(a, b) }\n}"
            )
        assertEquals(listOf("SCH1049"), r.diagnostics.map { it.code.id })
        assertEquals("declare a nested model to give it a key", r.diagnostics.single().help)
    }

    @Test
    fun `an id inside a list of inline shapes is SCH1049`() {
        assertEquals(
            listOf("SCH1049"),
            codes(
                "schema s\nmodel Order { #1 id uuid { id }  #2 lines { sku string { id }  qty int32 }[] }"
            ),
        )
        assertEquals(
            listOf("SCH1049"),
            codes(
                "schema s\nmodel Order {\n  #1 id uuid { id }\n  #2 lines { sku string  qty int32  @@id(sku) }[]\n}"
            ),
        )
    }

    @Test
    fun `a list of inline shapes stays composition`() {
        val r =
            analyze(
                "schema s\nmodel Order { #1 id uuid { id }  #2 lines { sku string  qty int32 }[] }"
            )
        assertEquals(emptyList(), r.diagnostics)
        val lines = model(r, "Order").fields[1].type as io.schemata.core.ir.ListOf
        assertEquals(
            QualifiedName("s", listOf("Order", "OrderLines")),
            (lines.element as Ref).target,
        )
        assertTrue(model(r, "Order", "OrderLines").keyFields().isEmpty())
    }

    @Test
    fun `a nullable inline shape hoists and stays nullable`() {
        val r = analyze("schema s\nmodel Order { shipping { street string }? }")
        assertEquals(emptyList(), r.diagnostics)
        val field = model(r, "Order").fields.single()
        assertTrue(field.nullable)
        assertEquals(
            QualifiedName("s", listOf("Order", "OrderShipping")),
            (field.type as Ref).target,
        )
    }

    @Test
    fun `a hoisted name that hides a top-level declaration is SCH1053`() {
        val r =
            analyze(
                "schema s\nenum OrderStatus { a b }\nmodel Order { status enum { x y }  prev OrderStatus }"
            )
        assertEquals(listOf("SCH1053"), r.diagnostics.map { it.code.id })
        assertEquals("name it with @name(\"…\")", r.diagnostics.single().help)
    }

    @Test
    fun `a hoisted name that hides an outer model's declaration is SCH1053`() {
        val text =
            "schema s\nmodel Outer {\n  inner Inner\n  model InnerKind { x int32 }\n" +
                "  model Inner { kind enum { a b } }\n}"
        assertEquals(listOf("SCH1053"), codes(text))
    }

    @Test
    fun `a hoisted name named apart from a top-level one is fine`() {
        val r =
            analyze(
                "schema s\nenum OrderStatus { a b }\nmodel Order { status enum { x y } @name(\"OrderState\")  prev OrderStatus }"
            )
        assertEquals(emptyList(), r.diagnostics)
    }
}
