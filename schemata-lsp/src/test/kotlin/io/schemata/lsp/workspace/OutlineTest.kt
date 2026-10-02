package io.schemata.lsp.workspace

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class OutlineTest {
    @TempDir lateinit var dir: Path

    private val text =
        "namespace shop\n" +
            "record Order {\n  #1 id: uuid\n  record Line { #1 sku: string }\n}\n" +
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
        f.workspace.change(a, "namespace shop\nrecord Order {")
        assertEquals(emptyList(), f.queries.symbols(a))
        assertEquals(emptyList(), f.queries.symbols("/nowhere/x.schemata"))
    }
}
