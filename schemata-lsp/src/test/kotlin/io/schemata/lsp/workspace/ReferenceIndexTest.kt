package io.schemata.lsp.workspace

import io.schemata.core.ir.QualifiedName
import io.schemata.lang.Parser
import io.schemata.lang.Span
import io.schemata.lang.ast.RecordDecl
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.io.TempDir

class ReferenceIndexTest {
    @TempDir lateinit var dir: Path

    private val customers = "schema shop.customers\nmodel Customer { #1 id uuid }\n"

    @Test
    fun `a type name goes to its declaration in another file`() {
        val f = Fixture(dir)
        val c = f.open("shop/customers.schemata", customers)
        val o =
            f.open(
                "shop/orders.schemata",
                "schema shop.orders\nimport shop.customers\nmodel Order { #1 who Customer }\n",
            )
        assertEquals(
            listOf(f.location(c, "Customer")),
            f.queries.definition(o, f.at(o, "Customer }")),
        )
    }

    @Test
    fun `a cursor just past the end of a name still finds it`() {
        val f = Fixture(dir)
        val c = f.open("shop/customers.schemata", customers)
        val o =
            f.open(
                "shop/orders.schemata",
                "schema shop.orders\nimport shop.customers\nmodel Order { #1 who Customer }\n",
            )
        assertEquals(
            listOf(f.location(c, "Customer")),
            f.queries.definition(o, f.at(o, "Customer }", offset = "Customer".length)),
        )
    }

    @Test
    fun `the alias of a qualified name goes to the import and the type to its declaration`() {
        val f = Fixture(dir)
        val c = f.open("shop/customers.schemata", customers)
        val o =
            f.open(
                "shop/orders.schemata",
                "schema shop.orders\nimport shop.customers as cust\n" +
                    "model Order { #1 who cust.Customer }\n",
            )
        assertEquals(
            listOf(f.location(o, "cust", occurrence = 1)),
            f.queries.definition(o, f.at(o, "cust.Customer")),
        )
        assertEquals(
            listOf(f.location(c, "Customer")),
            f.queries.definition(o, f.at(o, "cust.Customer", offset = 5)),
        )
    }

    @Test
    fun `an import goes to the namespace line of every file that declares it`() {
        val f = Fixture(dir)
        val c1 = f.open("shop/customers.schemata", customers)
        val c2 =
            f.open(
                "shop/customers_more.schemata",
                "schema shop.customers\nmodel Address { #1 city string }\n",
            )
        val o =
            f.open(
                "shop/orders.schemata",
                "schema shop.orders\nimport shop.customers\nmodel Order { #1 who Customer }\n",
            )
        assertEquals(
            listOf(f.location(c1, "shop.customers"), f.location(c2, "shop.customers")),
            f.queries.definition(o, f.at(o, "shop.customers")),
        )
    }

    @Test
    fun `a declaration in another file of the same namespace resolves without an import`() {
        val f = Fixture(dir)
        val a = f.open("m/a.schemata", "schema m\nmodel A { #1 b B }\n")
        val b = f.open("m/b.schemata", "schema m\nmodel B { #1 x int32 }\n")
        assertEquals(listOf(f.location(b, "B")), f.queries.definition(a, f.at(a, "B }")))
    }

    @Test
    fun `each segment of a nested name goes to its own declaration`() {
        val f = Fixture(dir)
        val a =
            f.open(
                "m/a.schemata",
                "schema m\nmodel Outer { #1 x int32\n  model Inner { #1 y int32 } }\n" +
                    "model Use { #1 i Outer.Inner }\n",
            )
        assertEquals(
            listOf(f.location(a, "Outer")),
            f.queries.definition(a, f.at(a, "Outer.Inner")),
        )
        assertEquals(
            listOf(f.location(a, "Inner")),
            f.queries.definition(a, f.at(a, "Outer.Inner", offset = 6)),
        )
    }

    @Test
    fun `the namespace prefix of a fully qualified name goes to the namespace`() {
        val f = Fixture(dir)
        val c = f.open("shop/customers.schemata", customers)
        val o =
            f.open(
                "shop/orders.schemata",
                "schema shop.orders\nimport shop.customers\n" +
                    "model Order { #1 who shop.customers.Customer }\n",
            )
        assertEquals(
            listOf(f.location(c, "shop.customers")),
            f.queries.definition(o, f.at(o, "shop.customers.Customer", offset = 6)),
        )
    }

    @Test
    fun `an enum default goes to the value, through an alias too`() {
        val f = Fixture(dir)
        val a =
            f.open(
                "m/a.schemata",
                "schema m\nenum Status { #1 pending, #2 paid }\nalias S = Status\n" +
                    "model R { #1 a Status = paid #2 b S = pending }\n",
            )
        assertEquals(
            listOf(f.location(a, "paid")),
            f.queries.definition(a, f.at(a, "= paid", offset = 2)),
        )
        assertEquals(
            listOf(f.location(a, "pending")),
            f.queries.definition(a, f.at(a, "= pending", offset = 2)),
        )
    }

    @Test
    fun `a field named in an annotation tuple goes to the field`() {
        val f = Fixture(dir)
        val a =
            f.open(
                "m/a.schemata",
                "schema m\n@sql(unique: (first, second))\n" +
                    "model R { #1 first int32 #2 second int32 }\n",
            )
        assertEquals(
            listOf(f.location(a, "second", occurrence = 1)),
            f.queries.definition(a, f.at(a, "second")),
        )
    }

    @Test
    fun `references list every use across the set and the declaration on request`() {
        val f = Fixture(dir)
        val c = f.open("shop/customers.schemata", customers)
        val o =
            f.open(
                "shop/orders.schemata",
                "schema shop.orders\nimport shop.customers\n" +
                    "model Order { #1 who Customer #2 also Customer[] }\n",
            )
        val uses = listOf(f.location(o, "Customer"), f.location(o, "Customer", occurrence = 1))
        assertEquals(uses, f.queries.references(c, f.at(c, "Customer"), includeDeclaration = false))
        assertEquals(
            listOf(f.location(c, "Customer")) + uses,
            f.queries.references(o, f.at(o, "Customer"), includeDeclaration = true),
        )
    }

    @Test
    fun `a union member is a reference to its declaration`() {
        val f = Fixture(dir)
        val a =
            f.open(
                "m/a.schemata",
                "schema m\nmodel Card {}\nmodel Cash {}\nunion Payment = #1 Card | #2 Cash\n",
            )
        assertEquals(listOf(f.location(a, "Card")), f.queries.definition(a, f.at(a, "Card |")))
        assertEquals(
            listOf(f.location(a, "Card", occurrence = 1)),
            f.queries.references(a, f.at(a, "Card"), includeDeclaration = false),
        )
    }

    @Test
    fun `a cursor on the dot of a nested name belongs to the segment before it`() {
        val f = Fixture(dir)
        val a =
            f.open(
                "m/a.schemata",
                "schema m\nmodel Outer { #1 x int32\n  model Inner { #1 y int32 } }\n" +
                    "model Use { #1 i Outer.Inner }\n",
            )
        assertEquals(
            listOf(f.location(a, "Outer")),
            f.queries.definition(a, f.at(a, "Outer.Inner", offset = 5)),
        )
    }

    @Test
    fun `a builtin, a keyword, and blank space have no definition`() {
        val f = Fixture(dir)
        val a = f.open("m/a.schemata", "schema m\nmodel R { #1 x int32 }\n")
        assertEquals(emptyList(), f.queries.definition(a, f.at(a, "int32")))
        assertEquals(emptyList(), f.queries.definition(a, f.at(a, "model")))
        assertEquals(emptyList(), f.queries.definition(a, TextPosition(40, 0)))
    }

    @Test
    fun `a file whose text does not parse answers nothing while others still answer`() {
        val f = Fixture(dir)
        val c = f.open("shop/customers.schemata", customers)
        val o =
            f.open(
                "shop/orders.schemata",
                "schema shop.orders\nimport shop.customers\nmodel Order { #1 who Customer }\n",
            )
        f.workspace.change(o, "schema shop.orders\nimport shop.customers\nmodel Order {")
        assertEquals(emptyList(), f.queries.definition(o, TextPosition(1, 8)))
        assertEquals(
            listOf(f.location(o, "Customer")),
            f.queries.references(c, f.at(c, "Customer"), includeDeclaration = false),
        )
    }

    @Test
    fun `a path the workspace has never seen answers nothing`() {
        val f = Fixture(dir)
        assertEquals(emptyList(), f.queries.definition("/nowhere/x.schemata", TextPosition(0, 0)))
    }

    @Test
    fun `services define symbols and payload types are references`() {
        val f = Fixture(dir)
        val a = f.open("t/a.schemata", SERVICE_API)
        val index = f.workspace.analysis(f.workspace.keyOf(a)).index
        val orders = QualifiedName("t", listOf("Orders"))
        assertEquals(1, index.definitions(Symbol.Service(orders)).size)
        assertEquals(1, index.definitions(Symbol.Operation(orders, "get")).size)
        assertEquals(1, index.definitions(Symbol.Operation(orders, "list")).size)
        assertEquals(
            2,
            index.references(Symbol.Declaration(QualifiedName("t", listOf("Order")))).size,
        )
        assertEquals(listOf(f.location(a, "Id")), f.queries.definition(a, f.at(a, "Id)")))
        fun order(needle: String) = Location(a, TextRange(f.at(a, needle), f.at(a, needle, 0, 5)))
        assertEquals(
            listOf(order("Order {"), order("Order  get"), order("Order\n}")),
            f.queries.references(a, f.at(a, "stream Order", offset = 7), true),
        )
    }

    @Test
    fun `go to definition crosses a back-reference`() {
        val f = Fixture(dir)
        val a =
            f.open(
                "shop/a.schemata",
                "schema shop\n" +
                    "model Customer { #1 id uuid { id }  #2 orders Order[] @relation(customer) }\n" +
                    "model Order { #1 id uuid { id }  #2 customer Customer }\n",
            )
        assertEquals(
            listOf(f.location(a, "customer", occurrence = 1)),
            f.queries.definition(a, f.at(a, "customer)")),
        )
        assertEquals(
            listOf(f.location(a, "Customer", occurrence = 0)),
            f.queries.definition(a, f.at(a, "Customer }")),
        )
    }

    @Test
    fun `a generated field is not a symbol and a written one is, whatever its span`() {
        val f = Fixture(dir)
        val a = f.open("m/a.schemata", "schema m\nmodel R { #1 name string  @@timestamps }\n")
        val owner = QualifiedName("m", listOf("R"))
        val index = f.workspace.analysis(f.workspace.keyOf(a)).index
        assertEquals(1, index.definitions(Symbol.Field(owner, "name")).size)
        assertEquals(emptyList(), index.definitions(Symbol.Field(owner, "created_at")))
        assertEquals(emptyList(), index.definitions(Symbol.Field(owner, "updated_at")))
        assertEquals(
            listOf("name"),
            f.queries.symbols(a).single().children.single().children.map { it.name },
        )
    }

    @Test
    fun `a written field whose name span equals its span is still a symbol`() {
        val parsed =
            Parser.parse("schema m\nmodel R { #1 name string  @@timestamps }\n", "m.schemata")
        val file = parsed.file!!
        val record = file.declarations.single() as RecordDecl
        // The shape the old span comparison mistook for a generated field.
        val squeezed = record.fields.single().let { it.copy(span = it.nameSpan) }
        val index =
            IndexBuilder.build(
                listOf(file.copy(declarations = listOf(record.copy(fields = listOf(squeezed))))),
                Recorded(),
            )
        val owner = QualifiedName("m", listOf("R"))
        assertEquals(1, index.definitions(Symbol.Field(owner, "name")).size)
        assertEquals(emptyList(), index.definitions(Symbol.Field(owner, "created_at")))
    }

    @Test
    fun `lookup among many sites finds the first listed match and honours the slack`() {
        fun span(file: String, line: Int, from: Int, to: Int) = Span(file, line, from, line, to)
        val symbols = (0 until 3000).map { Symbol.Field(QualifiedName("m", listOf("R")), "f$it") }
        val sites =
            (0 until 3000).flatMap { i ->
                listOf(
                    Site(span("a", i + 1, 1, 5), symbols[i], definition = true),
                    Site(span("b", i + 1, 1, 5), symbols[i], definition = false),
                )
            } + Site(span("a", 10, 3, 12), Symbol.Namespace("wide"), definition = false)
        val listed = sites.shuffled(java.util.Random(7))
        val index = ReferenceIndex(listed, emptyList(), emptyMap())
        assertEquals(symbols[1499], index.at("a", 1500, 1)!!.symbol)
        assertEquals(true, index.at("a", 1500, 1)!!.definition)
        assertEquals(false, index.at("b", 1500, 5)!!.definition)
        // Just past the end counts only when nothing covers the position itself.
        assertEquals(symbols[2999], index.at("a", 3000, 6)!!.symbol)
        assertNull(index.at("a", 3000, 7))
        assertNull(index.at("c", 1, 1))
        assertNull(index.at("a", 3001, 1))
        // Of two sites that cover a position, the one listed first wins.
        assertEquals(
            listed.first {
                it.span.file == "a" && it.span.startLine == 10 && it.span.endColumn > 4
            },
            index.at("a", 10, 4),
        )
    }
}
