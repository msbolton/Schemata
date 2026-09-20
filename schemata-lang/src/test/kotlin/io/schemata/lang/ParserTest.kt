package io.schemata.lang

import io.schemata.lang.ast.AliasDecl
import io.schemata.lang.ast.AnnotationArg
import io.schemata.lang.ast.AnnotationValue
import io.schemata.lang.ast.EnumDecl
import io.schemata.lang.ast.Literal
import io.schemata.lang.ast.RecordDecl
import io.schemata.lang.ast.Refinement
import io.schemata.lang.ast.ReservedItem
import io.schemata.lang.ast.UnionDecl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParserTest {
    private val example =
        """
        /// Order management for the storefront.
        @sql(schema = "shop")
        namespace shop.orders

        import shop.customers
        import shop.billing as bill

        alias Email = string(max = 254, pattern = "^[^@]+@[^@]+$")
        alias Money = decimal(19, 4)

        enum Status { #1 pending, #2 paid, #3 shipped, #4 cancelled }

        record Card         { #1 last4: string(max = 4)  #2 brand: string(max = 32) }
        record BankTransfer { #1 iban: string(max = 34) }
        record Cash         {}

        union Payment = #1 Card | #2 BankTransfer | #3 Cash

        /// A customer's order. One row per checkout.
        record Order {
          @sql(key)
          #1 id:         uuid
          #2 customer:   Customer
          #3 status:     Status = pending
          #4 lines:      list<Line>(min = 1)
          #5 total:      Money
          #6 payment:    Payment
          @sql(strategy = embed)
          #7 shipping:   Address
          #8 placed_at:  instant
          #9 note:       string(max = 500)?
          @deprecated("use placed_at")
          #10 created:   instant?
          reserved #11, "legacy_ref"

          /// One purchasable item.
          record Line {
            #1 sku:      string(max = 64)
            #2 quantity: int32(min = 1)
            #3 price:    Money
          }

          record Address {
            #1 street:   string(max = 200)
            #2 city:     string(max = 100)
            #3 country:  string(min = 2, max = 2)
          }
        }
        """
            .trimIndent()

    private val file by lazy {
        val result = Parser.parse(example, "orders.schemata")
        assertEquals(emptyList(), result.diagnostics)
        assertNotNull(result.file)
    }

    private fun record(name: String) =
        file.declarations.filterIsInstance<RecordDecl>().first { it.name == name }

    @Test
    fun `file carries path, doc, annotations, namespace, imports`() {
        assertEquals("orders.schemata", file.path)
        assertEquals("Order management for the storefront.", file.doc)
        val ann = file.annotations.single()
        assertEquals("sql", ann.name)
        val arg = ann.args.single() as AnnotationArg.Named
        assertEquals("schema", arg.name)
        assertEquals(
            "shop",
            ((arg.value as AnnotationValue.Lit).literal as Literal.StringLit).value,
        )
        assertEquals("shop.orders", file.namespace.name)
        assertEquals(
            listOf("shop.customers" to null, "shop.billing" to "bill"),
            file.imports.map { it.namespace to it.alias },
        )
    }

    @Test
    fun `aliases keep refinements in order with their kind`() {
        val email = file.declarations.filterIsInstance<AliasDecl>().first { it.name == "Email" }
        assertEquals("string", email.type.name)
        val (max, pattern) = email.type.refinements.map { it as Refinement.Named }
        assertEquals("max", max.name)
        assertEquals(254L, (max.value as Literal.IntLit).value)
        assertEquals("pattern", pattern.name)
        val money = file.declarations.filterIsInstance<AliasDecl>().first { it.name == "Money" }
        assertEquals(
            listOf(19L, 4L),
            money.type.refinements.map {
                ((it as Refinement.Positional).value as Literal.IntLit).value
            },
        )
    }

    @Test
    fun `enums carry ordinals and values`() {
        val status = file.declarations.filterIsInstance<EnumDecl>().single()
        assertEquals(
            listOf("pending", "paid", "shipped", "cancelled"),
            status.values.map { it.name },
        )
        assertEquals(listOf(1, 2, 3, 4), status.values.map { it.ordinal })
    }

    @Test
    fun `unions carry ordinals and member types`() {
        val payment = file.declarations.filterIsInstance<UnionDecl>().single()
        assertEquals(listOf(1, 2, 3), payment.members.map { it.ordinal })
        assertEquals(listOf("Card", "BankTransfer", "Cash"), payment.members.map { it.type.name })
    }

    @Test
    fun `records carry fields with ordinals, defaults, generics, nullability, annotations, docs`() {
        val order = record("Order")
        assertEquals("A customer's order. One row per checkout.", order.doc)
        assertEquals((1..10).toList(), order.fields.map { it.ordinal })
        val id = order.fields[0]
        assertEquals(
            "key",
            (((id.annotations.single().args.single() as AnnotationArg.Positional).value
                        as AnnotationValue.Lit)
                    .literal as Literal.NameLit)
                .name,
        )
        val status = order.fields[2]
        assertEquals("pending", (status.default as Literal.NameLit).name)
        val lines = order.fields[3]
        assertEquals("list", lines.type.name)
        assertEquals("Line", lines.type.args.single().name)
        assertEquals("min", (lines.type.refinements.single() as Refinement.Named).name)
        val shipping = order.fields[6]
        val strategy = shipping.annotations.single().args.single() as AnnotationArg.Named
        assertEquals(
            "embed",
            ((strategy.value as AnnotationValue.Lit).literal as Literal.NameLit).name,
        )
        assertTrue(order.fields[8].type.nullable)
        assertEquals("deprecated", order.fields[9].annotations.single().name)
    }

    @Test
    fun `records carry nested declarations and reserved items`() {
        val order = record("Order")
        assertEquals(listOf("Line", "Address"), order.nested.map { it.name })
        assertEquals("One purchasable item.", order.nested[0].doc)
        val (ordinal, name) = order.reserved
        assertEquals(11, (ordinal as ReservedItem.Ordinals).from)
        assertEquals(11, ordinal.to)
        assertEquals("legacy_ref", (name as ReservedItem.Name).name)
        assertEquals(emptyList(), record("Cash").fields)
    }

    @Test
    fun `reserved ranges and string escapes`() {
        val f =
            Parser.parse("namespace a\nrecord R { s: string = \"a\\\"b\"\n reserved #5..#7 }", "t")
                .file!!
        val r = f.declarations.single() as RecordDecl
        assertEquals("a\"b", (r.fields.single().default as Literal.StringLit).value)
        val range = r.reserved.single() as ReservedItem.Ordinals
        assertEquals(5 to 7, range.from to range.to)
    }

    @Test
    fun `every node names the file in its span`() {
        val order = record("Order")
        assertEquals("orders.schemata", order.span.file)
        assertEquals(19, order.span.startLine) // the span starts at the doc comment
        assertEquals("orders.schemata", order.fields[0].type.span.file)
        assertEquals("orders.schemata", order.nested[0].span.file)
        assertEquals("orders.schemata", file.imports[0].span.file)
    }

    @Test
    fun `a future keyword is a reserved-keyword error and yields no file`() {
        val result = Parser.parse("namespace a\nservice Orders { }", "t.schemata")
        assertNull(result.file)
        val d = result.diagnostics.single()
        assertEquals(Category.SYNTAX, d.category)
        assertEquals("'service' is reserved for a future version of Schemata", d.message)
        assertEquals(Span("t.schemata", 2, 1, 2, 7), d.span)
    }

    @Test
    fun `returns no file when there are syntax errors`() {
        val result = Parser.parse("namespace a\nrecord User { id uuid }", "bad.schemata")
        assertNull(result.file)
        assertTrue(result.diagnostics.hasErrors)
    }
}
