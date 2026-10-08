package io.schemata.lsp.workspace

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class OutlineTest {
    @TempDir lateinit var dir: Path

    private val text =
        "schema shop\n" +
            "model Order {\n  #1 id uuid\n  model Line { #1 sku string }\n}\n" +
            "enum Status { #1 pending, #2 paid }\n" +
            "union Payment = #1 Order | #2 Status\n" +
            "alias Money = decimal(19, 4)\n"

    private fun shape(nodes: List<OutlineNode>, indent: String = ""): List<String> =
        nodes.flatMap { listOf("$indent${it.kind} ${it.name}") + shape(it.children, "$indent  ") }

    @Test
    fun `the outline nests declarations under the namespace and members under declarations`() {
        val f = Fixture(dir)
        val a = f.open("shop/a.schemata", text)
        assertEquals(
            listOf(
                "NAMESPACE shop",
                "  RECORD Order",
                "    FIELD id",
                "    RECORD Line",
                "      FIELD sku",
                "  ENUM Status",
                "    VALUE pending",
                "    VALUE paid",
                "  UNION Payment",
                "  ALIAS Money",
            ),
            shape(f.queries.symbols(a)),
        )
    }

    @Test
    fun `a record's fields and nested declarations appear in source order`() {
        val f = Fixture(dir)
        val a =
            f.open(
                "shop/a.schemata",
                "schema shop\nmodel Order {\n  model Line { #1 sku string }\n" +
                    "  #1 lines Line[]\n  enum Kind { #1 retail }\n  #2 kind Kind\n}\n",
            )
        assertEquals(
            listOf("Line", "lines", "Kind", "kind"),
            f.queries.symbols(a).single().children.single().children.map { it.name },
        )
    }

    @Test
    fun `every node's selection is its name and lies inside its range`() {
        val f = Fixture(dir)
        val a = f.open("shop/a.schemata", text)
        fun check(node: OutlineNode) {
            val (r, s) = node.range to node.selection
            val startsInside =
                s.start.line > r.start.line ||
                    (s.start.line == r.start.line && s.start.character >= r.start.character)
            val endsInside =
                s.end.line < r.end.line ||
                    (s.end.line == r.end.line && s.end.character <= r.end.character)
            assertTrue(startsInside && endsInside, "${node.name}: $s not inside $r")
            node.children.forEach(::check)
        }
        f.queries.symbols(a).forEach(::check)
        val order = f.queries.symbols(a).single().children.first()
        assertEquals(f.range(a, "Order"), order.selection)
    }

    @Test
    fun `a broken or unknown file has no outline`() {
        val f = Fixture(dir)
        val a = f.open("shop/a.schemata", text)
        f.workspace.change(a, "schema shop\nmodel Order {")
        assertEquals(emptyList(), f.queries.symbols(a))
        assertEquals(emptyList(), f.queries.symbols("/nowhere/x.schemata"))
    }

    @Test
    fun `services outline with their operations`() {
        val f = Fixture(dir)
        val a = f.open("t/a.schemata", SERVICE_API)
        val nodes = f.queries.symbols(a).single().children
        val service = nodes.single { it.kind == OutlineKind.SERVICE }
        assertEquals("Orders", service.name)
        assertEquals(f.range(a, "Orders", occurrence = 1), service.selection)
        assertEquals(
            listOf("get" to "(Id): Order", "list" to "(): stream Order"),
            service.children.map { it.name to it.detail },
        )
        assertEquals(
            listOf(OutlineKind.OPERATION, OutlineKind.OPERATION),
            service.children.map { it.kind },
        )
        assertEquals(listOf("Id", "Order", "Orders"), nodes.map { it.name })
    }

    @Test
    fun `a service between declarations keeps its place`() {
        val f = Fixture(dir)
        val a =
            f.open(
                "t/a.schemata",
                "schema t\nmodel A { #1 x int32 }\n" +
                    "service S { #1 put(stream A)  put \"/a\" }\n" +
                    "model B { #1 y A[] { minItems 1 } }\n" +
                    "service T { #1 find(B): A[]? }\n",
            )
        assertEquals(
            listOf("A", "S", "B", "T"),
            f.queries.symbols(a).single().children.map { it.name },
        )
        assertEquals(
            listOf("(stream A)", "(B): A[]?"),
            f.queries
                .symbols(a)
                .single()
                .children
                .flatMap { n -> n.children.map { it.detail } }
                .filterNotNull(),
        )
    }

    @Test
    fun `outline lists hoisted types under their model`() {
        val f = Fixture(dir)
        val a =
            f.open(
                "shop/a.schemata",
                "schema shop\nmodel Order {\n  #1 address { street string }\n" +
                    "  #2 kind enum { retail, wholesale }\n}\n",
            )
        assertEquals(
            listOf(
                "NAMESPACE shop",
                "  RECORD Order",
                "    FIELD address",
                "    RECORD OrderAddress",
                "      FIELD street",
                "    FIELD kind",
                "    ENUM OrderKind",
                "      VALUE retail",
                "      VALUE wholesale",
            ),
            shape(f.queries.symbols(a)),
        )
    }
}
