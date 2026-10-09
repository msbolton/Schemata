package io.schemata.lsp.workspace

import io.schemata.core.ir.Builtin
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.io.TempDir

class HoverTest {
    @TempDir lateinit var dir: Path

    private val text =
        "/// The storefront.\nschema shop\n\n" +
            "/// A customer's order.\n/// One row per checkout.\n" +
            "model Order {\n" +
            "  #1 status  Status = pending\n" +
            "  /// Free text.\n  #2 note    string? { max 500 }\n" +
            "  #3 total   Money\n}\n\n" +
            "enum Status {\n  /// Not yet paid.\n  #1 pending,\n  #2 paid\n}\n\n" +
            "alias Money = decimal(19,   4)\n"

    private fun block(signature: String, doc: String? = null) =
        "```schemata\n$signature\n```" + (doc?.let { "\n\n$it" } ?: "")

    @Test
    fun `a declaration shows its kind, qualified name, and doc comment`() {
        val f = Fixture(dir)
        val a = f.open("shop/a.schemata", text)
        val hover = f.queries.hover(a, f.at(a, "Order"))!!
        assertEquals(
            block("model shop.Order", "A customer's order.\nOne row per checkout."),
            hover.markdown,
        )
        assertEquals(f.range(a, "Order"), hover.range)
    }

    @Test
    fun `a field shows the rest of its declaration with whitespace collapsed`() {
        val f = Fixture(dir)
        val a = f.open("shop/a.schemata", text)
        assertEquals(
            block("field shop.Order.note string? { max 500 }", "Free text."),
            f.queries.hover(a, f.at(a, "note"))!!.markdown,
        )
        assertEquals(
            block("field shop.Order.status Status = pending"),
            f.queries.hover(a, f.at(a, "status"))!!.markdown,
        )
    }

    @Test
    fun `a type reference shows the declaration it names`() {
        val f = Fixture(dir)
        val a = f.open("shop/a.schemata", text)
        assertEquals(
            block("alias shop.Money = decimal(19, 4)"),
            f.queries.hover(a, f.at(a, "Money"))!!.markdown,
        )
        assertEquals(
            block("enum shop.Status"),
            f.queries.hover(a, f.at(a, "Status = pending"))!!.markdown,
        )
    }

    @Test
    fun `an enum value shows its owner and doc`() {
        val f = Fixture(dir)
        val a = f.open("shop/a.schemata", text)
        assertEquals(
            block("value shop.Status.pending", "Not yet paid."),
            f.queries.hover(a, f.at(a, "= pending", offset = 2))!!.markdown,
        )
    }

    @Test
    fun `a namespace shows the file doc and an import alias shows its import`() {
        val f = Fixture(dir)
        f.open("shop/a.schemata", text)
        val b =
            f.open("shop/b.schemata", "schema shop.b\nimport shop as s\nmodel R { #1 o s.Order }\n")
        assertEquals(
            block("schema shop", "The storefront."),
            f.queries.hover(b, f.at(b, "import shop", offset = 7))!!.markdown,
        )
        assertEquals(block("import shop as s"), f.queries.hover(b, f.at(b, "s.Order"))!!.markdown)
    }

    @Test
    fun `a builtin type shows its one-line description`() {
        val f = Fixture(dir)
        val a = f.open("shop/a.schemata", text)
        assertEquals(
            block("string") + "\n\nUnicode text; options: min, max (length), match",
            f.queries.hover(a, f.at(a, "string?"))!!.markdown,
        )
    }

    @Test
    fun `there is no hover on a keyword, an ordinal, or a broken file`() {
        val f = Fixture(dir)
        val a = f.open("shop/a.schemata", text)
        assertNull(f.queries.hover(a, f.at(a, "model")))
        assertNull(f.queries.hover(a, f.at(a, "#1")))
        f.workspace.change(a, "schema shop\nmodel Order {")
        assertNull(f.queries.hover(a, TextPosition(1, 8)))
    }

    @Test
    fun `every builtin type and both collections have a description`() {
        val names = Builtin.entries.map { it.typeName } + listOf("list", "map")
        assertEquals(names.toSet(), BuiltinDocs.text.keys)
    }

    @Test
    fun `hover on a service and an operation`() {
        val f = Fixture(dir)
        val a = f.open("t/a.schemata", SERVICE_API)
        assertEquals(
            block("service t.Orders", "Orders."),
            f.queries.hover(a, f.at(a, "Orders {"))!!.markdown,
        )
        val get = f.queries.hover(a, f.at(a, "get("))!!
        assertEquals(block("#1 get(Id): Order  get \"/orders/{id}\"", "Fetch."), get.markdown)
        assertEquals(f.range(a, "get"), get.range)
        assertEquals(
            block("#2 list(): stream Order"),
            f.queries.hover(a, f.at(a, "list("))!!.markdown,
        )
    }

    @Test
    fun `an operation hover prints its payload types as the formatter does`() {
        val f = Fixture(dir)
        val a =
            f.open(
                "t/a.schemata",
                "schema t\nmodel A { #1 x int32 }\n" +
                    "service S {\n  put( stream   A ) :  A [ ]\n" +
                    "    put   \"/a\"\n}\n",
            )
        assertEquals(
            block("put(stream A): A[]  put \"/a\""),
            f.queries.hover(a, f.at(a, "put("))!!.markdown,
        )
    }

    @Test
    fun `a builtin written as a payload shows its description`() {
        val f = Fixture(dir)
        val a = f.open("t/a.schemata", "schema t\nservice S { #1 get(uuid) }\n")
        assertEquals(
            block("uuid") + "\n\na universally unique identifier",
            f.queries.hover(a, f.at(a, "uuid"))!!.markdown,
        )
    }

    @Test
    fun `hover on an inline shape names its hoisted type`() {
        val f = Fixture(dir)
        val a =
            f.open(
                "shop/a.schemata",
                "schema shop\nmodel Order {\n  #1 address { street string }\n}\n",
            )
        assertEquals(
            block("model shop.Order.OrderAddress"),
            f.queries.hover(a, f.at(a, "{ street"))!!.markdown,
        )
    }

    @Test
    fun `hover on a back-reference field shows its target and its relation`() {
        val f = Fixture(dir)
        val a =
            f.open(
                "shop/a.schemata",
                "schema shop\n" +
                    "model Customer { #1 id uuid { id }  " +
                    "#2 orders Order[] @relation(customer) }\n" +
                    "model Order { #1 id uuid { id }  #2 customer Customer @relation(onDelete: cascade) }\n",
            )
        assertEquals(
            block("field shop.Customer.orders Order[] @relation(customer)"),
            f.queries.hover(a, f.at(a, "orders"))!!.markdown,
        )
        assertEquals(
            block("field shop.Order.customer Customer @relation(onDelete: cascade)"),
            f.queries.hover(a, f.at(a, "customer)"))!!.markdown,
        )
        assertEquals(block("model shop.Order"), f.queries.hover(a, f.at(a, "Order[]"))!!.markdown)
    }

    @Test
    fun `an operation hover pads the ordinal to the widest one in its service as the formatter does`() {
        val f = Fixture(dir)
        val ops = (1..10).joinToString("\n") { "  #$it op$it()" }
        val a = f.open("t/a.schemata", "schema t\nservice S {\n$ops\n}\n")
        assertEquals(block("#1  op1()"), f.queries.hover(a, f.at(a, "op1("))!!.markdown)
        assertEquals(block("#10 op10()"), f.queries.hover(a, f.at(a, "op10("))!!.markdown)
    }
}
