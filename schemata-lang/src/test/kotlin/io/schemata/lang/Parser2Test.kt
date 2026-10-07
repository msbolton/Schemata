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
    fun `block attributes are flagged and leading ones are not`() {
        val m = model("schema s\n@deprecated(\"old\")\nmodel M {\n  a int32\n  @@id(a)\n}")
        assertEquals(listOf(false, true), m.annotations.map { it.block })
        val shape = model("schema s\nmodel M { s { a int32  @@unique(a) } }").fields.single()
        assertEquals(listOf(true), shape.type.inlineShape!!.annotations.map { it.block })
        val v1 =
            Parser.parse("namespace s\n@deprecated(\"old\")\nrecord R { a: int32 }", "t").file!!
        assertEquals(listOf(false), v1.declarations.single().annotations.map { it.block })
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

    @Test
    fun `an attribute on its own line below the header leads the next model`() {
        val f = parse("schema s\n@deprecated(\"x\")\nmodel M { a int32 }").file!!
        assertEquals(emptyList(), f.annotations)
        val m = f.declarations.single()
        assertEquals(listOf("deprecated"), m.annotations.map { it.name })
        assertEquals(Span("t.schemata", 2, 1, 3, 19), m.span)
    }

    @Test
    fun `an attribute on the header line stays on the header`() {
        val f =
            parse("schema s @sql(schema: \"x\")\n@proto(package: \"p\")\nmodel M { a int32 }")
                .file!!
        assertEquals(listOf("sql"), f.annotations.map { it.name })
        assertEquals(listOf("proto"), f.declarations.single().annotations.map { it.name })
    }

    @Test
    fun `an attribute on its own line between two fields leads the second`() {
        val (a, b) =
            model(
                    """
                schema s
                model M {
                  a int32 @index_hint
                  @deprecated("x")
                  b int32
                }
                """
                )
                .fields
        assertEquals(listOf("index_hint"), a.annotations.map { it.name })
        assertEquals(listOf("deprecated"), b.annotations.map { it.name })
        assertEquals(Span("t.schemata", 3, 3, 3, 21), a.span)
        assertEquals(Span("t.schemata", 4, 3, 5, 9), b.span)
    }

    @Test
    fun `an attribute after the last field with nothing to lead is an error`() {
        val r =
            parse("schema s\nmodel M {\n  a int32\n  @deprecated(\"x\")\n  @@sql(table: \"m\")\n}")
        assertNull(r.file)
        val d = r.diagnostics.single()
        assertEquals(LangCodes.SYNTAX, d.code)
        assertEquals("an attribute here has nothing to attach to", d.message)
        assertEquals(Span("t.schemata", 4, 3, 4, 18), d.span)
    }

    @Test
    fun `an attribute below the header and above an import is an error`() {
        val r = parse("schema s\n@deprecated(\"x\")\nimport t\nmodel M { a int32 }")
        assertNull(r.file)
        assertEquals(listOf(LangCodes.SYNTAX), r.diagnostics.map { it.code })
    }

    @Test
    fun `a multi-line inline shape is trailed on the line of its closing brace`() {
        val m =
            model(
                """
                schema s
                model M {
                  s {
                    a int32
                  } @deprecated("x")
                  @index_hint
                  enum E { a }
                }
                """
            )
        assertEquals(listOf("deprecated"), m.fields.single().annotations.map { it.name })
        assertEquals(listOf("index_hint"), m.nested.single().annotations.map { it.name })
    }

    @Test
    fun `attributes before a default stay on the field`() {
        val f =
            model("schema s\nmodel M {\n  a int32\n    @deprecated(\"x\") = 1\n}").fields.single()
        assertEquals(listOf("deprecated"), f.annotations.map { it.name })
    }

    @Test
    fun `an attribute leads the first member`() {
        val a = model("schema s\nmodel M {\n  @x\n  a int32\n}").fields.single()
        assertEquals(listOf("x"), a.annotations.map { it.name })
        assertEquals(Span("t.schemata", 3, 3, 4, 9), a.span)
    }

    @Test
    fun `doc then attribute then field parses with the attribute on the field`() {
        val (a, b) = model("schema s\nmodel M {\n  a int32\n  /// d\n  @x\n  b int32\n}").fields
        assertEquals(emptyList(), a.annotations)
        assertEquals(listOf("x"), b.annotations.map { it.name })
        assertEquals("d", b.doc)
        assertEquals(Span("t.schemata", 4, 3, 6, 9), b.span)
    }

    @Test
    fun `a doc comment between a carried attribute and its owner keeps the owner's span and attribute`() {
        val (a, b) =
            model("schema s\nmodel M {\n  a int32\n  @x\n  /// d\n  @y\n  b int32\n}").fields
        assertEquals(emptyList(), a.annotations)
        assertEquals(Span("t.schemata", 3, 3, 3, 9), a.span)
        assertEquals(listOf("x", "y"), b.annotations.map { it.name })
        assertEquals("d", b.doc)
        assertEquals(Span("t.schemata", 4, 3, 7, 9), b.span)
    }

    @Test
    fun `an inline shape in a union member or an alias is a syntax error`() {
        assertNull(parse("schema s\nmodel A { a int32 }\nunion U = A | { x int32 }").file)
        assertNull(parse("schema s\nalias S = { x int32 }").file)
        assertNull(parse("schema s\nalias E = enum { a b }").file)
        assertNull(parse("schema s\nmodel M { m map<string, { x int32 }> }").file)
    }

    @Test
    fun `an inline enum as a list element parses on a field`() {
        val t = model("schema s\nmodel M { tags enum { a b }[] }").fields.single().type
        assertEquals(listOf("a", "b"), t.inlineEnum!!.values.map { it.name })
        assertTrue(t.list && !t.nullable && !t.listNullable)
    }

    @Test
    fun `an attribute inside an enum body leads the next value`() {
        val e = parse("schema s\nenum E { a @x b }").file!!.declarations.single() as EnumDecl
        assertEquals(emptyList(), e.values[0].annotations)
        assertEquals(listOf("x"), e.values[1].annotations.map { it.name })
    }
}
