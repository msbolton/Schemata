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
        "/// The storefront.\nnamespace shop\n\n" +
            "/// A customer's order.\n/// One row per checkout.\n" +
            "record Order {\n" +
            "  #1 status:  Status = pending\n" +
            "  /// Free text.\n  #2 note:    string(max = 500)?\n" +
            "  #3 total:   Money\n}\n\n" +
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
            block("record shop.Order", "A customer's order.\nOne row per checkout."),
            hover.markdown,
        )
        assertEquals(f.range(a, "Order"), hover.range)
    }

    @Test
    fun `a field shows the rest of its declaration with whitespace collapsed`() {
        val f = Fixture(dir)
        val a = f.open("shop/a.schemata", text)
        assertEquals(
            block("field shop.Order.note: string(max = 500)?", "Free text."),
            f.queries.hover(a, f.at(a, "note"))!!.markdown,
        )
        assertEquals(
            block("field shop.Order.status: Status = pending"),
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
            f.open(
                "shop/b.schemata",
                "namespace shop.b\nimport shop as s\nrecord R { #1 o: s.Order }\n",
            )
        assertEquals(
            block("namespace shop", "The storefront."),
            f.queries.hover(b, f.at(b, "import shop", offset = 7))!!.markdown,
        )
        assertEquals(block("import shop as s"), f.queries.hover(b, f.at(b, "s.Order"))!!.markdown)
    }

    @Test
    fun `a builtin type shows its one-line description`() {
        val f = Fixture(dir)
        val a = f.open("shop/a.schemata", text)
        assertEquals(
            block("string") + "\n\nUnicode text; refinements: min, max (length), pattern",
            f.queries.hover(a, f.at(a, "string(max"))!!.markdown,
        )
    }

    @Test
    fun `there is no hover on a keyword, an ordinal, or a broken file`() {
        val f = Fixture(dir)
        val a = f.open("shop/a.schemata", text)
        assertNull(f.queries.hover(a, f.at(a, "record")))
        assertNull(f.queries.hover(a, f.at(a, "#1")))
        f.workspace.change(a, "namespace shop\nrecord Order {")
        assertNull(f.queries.hover(a, TextPosition(1, 8)))
    }

    @Test
    fun `every builtin type and both collections have a description`() {
        val names = Builtin.entries.map { it.typeName } + listOf("list", "map")
        assertEquals(names.toSet(), BuiltinDocs.text.keys)
    }
}
