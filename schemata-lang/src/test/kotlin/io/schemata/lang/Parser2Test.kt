package io.schemata.lang

import io.schemata.lang.ast.AliasDecl
import io.schemata.lang.ast.AnnotationArg
import io.schemata.lang.ast.AnnotationValue
import io.schemata.lang.ast.EnumDecl
import io.schemata.lang.ast.Literal
import io.schemata.lang.ast.RecordDecl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Parser2Test {
    private fun parse(text: String) = Parser.parse2(text.trimIndent(), "t.schemata")

    private fun model(text: String): RecordDecl {
        val r = parse(text)
        assertNotNull(r.file, r.diagnostics.joinToString("\n") { it.message })
        return r.file!!.declarations.filterIsInstance<RecordDecl>().single()
    }

    @Test
    fun `a schema header carries its attributes and a model its block attributes`() {
        val r =
            parse(
                """
            schema shop.orders @sql(schema: "shop") @proto(package: "shop.v1")
            model Order {
              id uuid { id }
              @@sql(table: "orders")
            }
            """
            )
        val f = r.file!!
        assertEquals("shop.orders", f.namespace.name)
        assertEquals(listOf("sql", "proto"), f.annotations.map { it.name })
        val m = f.declarations.single() as RecordDecl
        assertEquals("Order", m.name)
        assertEquals(listOf("sql"), m.annotations.map { it.name })
        assertEquals(
            "table",
            (m.annotations[0].args[0] as io.schemata.lang.ast.AnnotationArg.Named).name,
        )
    }

    @Test
    fun `a field is name type options attributes default`() {
        val m =
            model(
                """
            schema s
            model M {
              #3 note string? { max 500 } @deprecated("why") = "x"
            }
            """
            )
        val f = m.fields.single()
        assertEquals(3, f.ordinal)
        assertEquals("note", f.name)
        assertEquals("string", f.type.name)
        assertTrue(f.type.nullable)
        assertEquals(listOf("max"), f.options.map { it.name })
        assertEquals(500L, (f.options[0].value as Literal.IntLit).value)
        assertEquals(listOf("deprecated"), f.annotations.map { it.name })
        assertEquals("x", (f.default as Literal.StringLit).value)
    }

    @Test
    fun `lists are postfix with element and list nullability`() {
        val m = model("schema s\nmodel M { a string[]  b string?[]  c string[]?  d string?[]? }")
        val (a, b, c, d) = m.fields
        assertTrue(a.type.list && !a.type.nullable && !a.type.listNullable)
        assertTrue(b.type.list && b.type.nullable && !b.type.listNullable)
        assertTrue(c.type.list && !c.type.nullable && c.type.listNullable)
        assertTrue(d.type.list && d.type.nullable && d.type.listNullable)
    }

    @Test
    fun `decimal keeps its precision and scale and a map key may carry options`() {
        val m =
            model("schema s\nmodel M { total decimal(19, 4)  tags map<string { max 10 }, int32> }")
        assertEquals(2, m.fields[0].type.refinements.size)
        assertEquals(listOf("max"), m.fields[1].type.args[0].options.map { it.name })
    }

    @Test
    fun `inline enums and shapes parse in type position`() {
        val m =
            model(
                """
            schema s
            model Order {
              status enum { pending paid } = pending
              shipping { street string { max 200 }  city string }
            }
            """
            )
        val status = m.fields[0].type.inlineEnum
        assertNotNull(status)
        assertEquals(listOf("pending", "paid"), status.values.map { it.name })
        val shipping = m.fields[1].type.inlineShape
        assertNotNull(shipping)
        assertEquals(listOf("street", "city"), shipping.fields.map { it.name })
        assertNull(m.fields[1].type.inlineEnum)
    }

    @Test
    fun `option names are legal field names`() {
        val m = model("schema s\nmodel M { index int32 { index }  min int32  id uuid { id } }")
        assertEquals(listOf("index", "min", "id"), m.fields.map { it.name })
        assertEquals(listOf("index"), m.fields[0].options.map { it.name })
    }

    @Test
    fun `the 1x surface does not parse as 2`() {
        val r = parse("namespace s\nrecord R { #1 x: int32 }")
        assertNull(r.file)
    }

    @Test
    fun `services keep the 1x body with colon arguments`() {
        val r =
            parse(
                """
            schema s
            model Id { id uuid { id } }
            service Orders { get(Id): Id  get "/orders/{id}" }
            """
            )
        assertEquals("get", r.file!!.services.single().operations.single().name)
    }

    @Test
    fun `bare option names side by side are separate flags`() {
        val f =
            model("schema s\nmodel M { id uuid { id unique }  n int32 { min 1, max 9 } }").fields
        assertEquals(listOf("id", "unique"), f[0].options.map { it.name })
        assertTrue(f[0].options.all { it.value == null })
        assertEquals(listOf(1L, 9L), f[1].options.map { (it.value as Literal.IntLit).value })
    }

    @Test
    fun `a match option keeps its regular expression as written`() {
        val r = parse("schema s\nmodel M { code string { match \"^\\d+$\" } }")
        assertEquals(emptyList(), r.diagnostics)
        val option = (r.file!!.declarations.single() as RecordDecl).fields.single().options.single()
        assertEquals("^\\d+$", (option.value as Literal.StringLit).value)
    }

    @Test
    fun `options after an alias travel with its type`() {
        val r = parse("schema s\nalias Email = string { max 254 }")
        val alias = r.file!!.declarations.single() as AliasDecl
        assertEquals(listOf("max"), alias.type.options.map { it.name })
    }

    @Test
    fun `enum values may still be separated by commas`() {
        val r = parse("schema s\nenum Status { pending, paid  shipped }")
        val e = r.file!!.declarations.single() as EnumDecl
        assertEquals(listOf("pending", "paid", "shipped"), e.values.map { it.name })
    }

    @Test
    fun `block attributes follow the leading ones and take bare names`() {
        val m =
            model(
                """
            schema s
            /// M
            @deprecated("old")
            model M {
              a int32
              b int32
              @@id(a, b)
            }
            """
            )
        assertEquals(listOf("deprecated", "id"), m.annotations.map { it.name })
        val names =
            m.annotations[1].args.map {
                (((it as AnnotationArg.Positional).value as AnnotationValue.Lit).literal
                        as Literal.NameLit)
                    .name
            }
        assertEquals(listOf("a", "b"), names)
    }

    @Test
    fun `an inline shape keeps its own block attributes and nested members`() {
        val m = model("schema s\nmodel M { s { a int32  b int32  reserved #9  @@unique(a, b) } }")
        val shape = m.fields.single().type.inlineShape!!
        assertEquals("", shape.name)
        assertEquals(listOf("unique"), shape.annotations.map { it.name })
        assertEquals(1, shape.reserved.size)
        assertEquals("", m.fields.single().type.name)
    }

    @Test
    fun `an inline enum may be a nullable list`() {
        val t = model("schema s\nmodel M { tags enum { a b }?[]? }").fields.single().type
        assertNotNull(t.inlineEnum)
        assertTrue(t.list && t.nullable && t.listNullable)
    }

    @Test
    fun `the schema span covers the keyword and the name but not the attributes`() {
        val f = parse("schema shop.orders @sql(schema: \"shop\")").file!!
        assertEquals(Span("t.schemata", 1, 1, 1, 18), f.namespace.span)
        assertEquals(Span("t.schemata", 1, 8, 1, 18), f.namespace.nameSpan)
    }

    @Test
    fun `a doc comment ends the header so the next attribute is the declaration's`() {
        val f = parse("schema s @a\n/// d\n@b model M {}").file!!
        assertEquals(listOf("a"), f.annotations.map { it.name })
        assertEquals(listOf("b"), f.declarations.single().annotations.map { it.name })
    }

    @Test
    fun `operation is reserved for a future version`() {
        val r = parse("schema s\noperation Foo {}")
        assertNull(r.file)
        assertEquals(listOf(LangCodes.RESERVED_KEYWORD), r.diagnostics.map { it.code })
    }

    @Test
    fun `a 1x refinement is a syntax error`() {
        assertNull(parse("schema s\nmodel M { name string(max = 5) }").file)
    }
}
