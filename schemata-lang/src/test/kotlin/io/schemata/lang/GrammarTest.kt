package io.schemata.lang

import io.schemata.lang.antlr.SchemataLexer
import io.schemata.lang.antlr.SchemataParser
import io.schemata.lang.internal.CollectingErrorListener
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream

class GrammarTest {
    /** The worked example from the language reference. */
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

    private fun parse(source: String): Pair<SchemataParser.FileContext, List<Diagnostic>> {
        val listener = CollectingErrorListener("test.schemata")
        val lexer =
            SchemataLexer(CharStreams.fromString(source)).apply {
                removeErrorListeners()
                addErrorListener(listener)
            }
        val parser =
            SchemataParser(CommonTokenStream(lexer)).apply {
                removeErrorListeners()
                addErrorListener(listener)
            }
        return parser.file() to listener.diagnostics
    }

    private fun tokens(source: String): List<Int> =
        SchemataLexer(CharStreams.fromString(source)).allTokens.map { it.type }

    @Test
    fun `parses the worked example with no diagnostics`() {
        val (tree, diagnostics) = parse(example)
        assertEquals(emptyList(), diagnostics)
        assertNotNull(tree.doc())
        assertEquals(1, tree.annotation().size)
        assertEquals(
            listOf("shop.customers", "shop.billing"),
            tree.importDecl().map { it.qualifiedName().text },
        )
        assertEquals("bill", tree.importDecl()[1].IDENT().text)
        val decls = tree.topLevel().map { it.declaration() }
        assertEquals(2, decls.count { it.aliasDecl() != null })
        assertEquals(1, decls.count { it.enumDecl() != null })
        assertEquals(4, decls.count { it.recordDecl() != null })
        assertEquals(1, decls.count { it.unionDecl() != null })
    }

    @Test
    fun `record members are fields, nested declarations, and reserved statements`() {
        val (tree, _) = parse(example)
        val order =
            tree
                .topLevel()
                .mapNotNull { it.declaration()?.recordDecl() }
                .first { it.IDENT().text == "Order" }
        val members = order.recordMember()
        assertEquals(10, members.count { it.field() != null })
        assertEquals(2, members.count { it.declaration() != null })
        assertEquals(1, members.count { it.reservedStmt() != null })
        val created = members.mapNotNull { it.field() }.first { it.IDENT().text == "created" }
        assertEquals("#10", created.ORDINAL().text)
        assertEquals(1, created.annotation().size)
        assertNotNull(created.typeExpr().QUESTION())
    }

    @Test
    fun `fields take defaults, refinements, and generic arguments`() {
        val (tree, diagnostics) =
            parse(
                "namespace a\nrecord R { s: Status = pending\n l: map<string, list<int32(min = 0)>>(max = 10) }"
            )
        assertEquals(emptyList(), diagnostics)
        val fields =
            tree.topLevel().single().declaration().recordDecl().recordMember().map { it.field() }
        assertEquals("pending", fields[0].literal().text)
        val map = fields[1].typeExpr()
        assertEquals(2, map.typeArgs().typeExpr().size)
        assertEquals("max=10", map.refinements().refinement().single().text)
        val inner = map.typeArgs().typeExpr()[1].typeArgs().typeExpr().single()
        assertEquals("min=0", inner.refinements().refinement().single().text)
    }

    @Test
    fun `refinements may be positional or named`() {
        val (tree, diagnostics) =
            parse("namespace a\nalias M = decimal(19, 4)\nalias S = string(max = 5)")
        assertEquals(emptyList(), diagnostics)
        val aliases = tree.topLevel().map { it.declaration().aliasDecl() }
        assertEquals(
            listOf("19", "4"),
            aliases[0].typeExpr().refinements().refinement().map { it.literal().text },
        )
        assertEquals("max", aliases[1].typeExpr().refinements().refinement().single().IDENT().text)
    }

    @Test
    fun `annotations take flags, positional literals, named values, and tuples`() {
        val (tree, diagnostics) =
            parse(
                "namespace a\n@sql(key) @deprecated(\"x\") @sql(strategy = embed, type = \"citext\") @sql(key = (a, b))\nrecord R { x: bool }"
            )
        assertEquals(emptyList(), diagnostics)
        val annotations = tree.topLevel().single().declaration().recordDecl().annotation()
        assertEquals(4, annotations.size)
        assertEquals("key", annotations[0].annotationArg().single().literal().text)
        assertEquals("\"x\"", annotations[1].annotationArg().single().literal().text)
        assertEquals(
            listOf("strategy", "type"),
            annotations[2].annotationArg().map { it.IDENT().text },
        )
        assertEquals(
            listOf("a", "b"),
            annotations[3].annotationArg().single().annotationValue().IDENT().map { it.text },
        )
    }

    @Test
    fun `enum values may be separated by commas or newlines`() {
        val (tree, diagnostics) = parse("namespace a\nenum E { a, b\n c\n d, }")
        assertEquals(emptyList(), diagnostics)
        assertEquals(
            listOf("a", "b", "c", "d"),
            tree.topLevel().single().declaration().enumDecl().enumValue().map { it.IDENT().text },
        )
    }

    @Test
    fun `union members carry optional ordinals`() {
        val (tree, diagnostics) = parse("namespace a\nunion U = #1 A | B | #3 uuid")
        assertEquals(emptyList(), diagnostics)
        val members = tree.topLevel().single().declaration().unionDecl().unionMember()
        assertEquals(listOf("#1", null, "#3"), members.map { it.ORDINAL()?.text })
        assertEquals(listOf("A", "B", "uuid"), members.map { it.typeExpr().qualifiedName().text })
    }

    @Test
    fun `reserved statements take ordinals, ranges, and names`() {
        val (tree, diagnostics) = parse("namespace a\nrecord R { reserved #3, #5..#7, \"old\" }")
        assertEquals(emptyList(), diagnostics)
        val items =
            tree
                .topLevel()
                .single()
                .declaration()
                .recordDecl()
                .recordMember()
                .single()
                .reservedStmt()
                .reservedItem()
        assertEquals(3, items.size)
        assertNotNull(items[1].RANGE())
        assertEquals("\"old\"", items[2].STRING_LITERAL().text)
    }

    @Test
    fun `doc comments are tokens that attach to the next declarable`() {
        val (tree, diagnostics) =
            parse("namespace a\n/// about R\n/// more\nrecord R {\n  /// about x\n  x: bool\n}")
        assertEquals(emptyList(), diagnostics)
        val record = tree.topLevel().single().declaration().recordDecl()
        assertEquals(2, record.doc().DOC_COMMENT().size)
        assertNotNull(record.recordMember().single().field().doc())
    }

    @Test
    fun `a dangling doc comment is a syntax error`() {
        val (_, diagnostics) = parse("namespace a\nrecord R { x: bool }\n/// nothing follows")
        assertTrue(diagnostics.hasErrors)
        assertEquals(3, diagnostics.first().span.startLine)
    }

    @Test
    fun `triple slash lexes as DOC_COMMENT, double slash is skipped`() {
        assertEquals(listOf(SchemataLexer.DOC_COMMENT), tokens("/// doc"))
        assertEquals(emptyList(), tokens("// plain"))
    }

    @Test
    fun `future keywords parse to a reservedFutureDecl node`() {
        val (tree, diagnostics) =
            parse("namespace a\nservice Orders { operation place(PlaceOrder): Order }")
        assertEquals(emptyList(), diagnostics)
        assertNotNull(tree.topLevel().single().reservedFutureDecl())
        assertNull(tree.topLevel().single().declaration())
    }

    @Test
    fun `reports a missing colon with its file and position`() {
        val (_, diagnostics) = parse("namespace a\nrecord User { id uuid }")
        val d = diagnostics.first()
        assertEquals(Category.SYNTAX, d.category)
        assertEquals("test.schemata", d.span.file)
        assertEquals(2, d.span.startLine)
        assertEquals(18, d.span.startColumn)
        assertEquals(21, d.span.endColumn)
    }

    @Test
    fun `rejects a keyword used as a record name`() {
        val (_, diagnostics) = parse("namespace a\nrecord record { x: bool }")
        assertTrue(diagnostics.hasErrors)
    }
}
