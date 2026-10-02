package io.schemata.lsp.workspace

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class RenameTest {
    @TempDir lateinit var dir: Path

    private val customers = "namespace shop.customers\nrecord Customer { #1 id: uuid }\n"
    private val orders =
        "namespace shop.orders\nimport shop.customers\n" +
            "record Order { /* 😀 */ #1 who: Customer #2 all: list<Customer> }\n"

    /** Applies [edits] to [text], last edit first so earlier offsets stay valid. */
    private fun apply(text: String, edits: List<TextEdit>): String {
        val starts = listOf(0) + text.indices.filter { text[it] == '\n' }.map { it + 1 }
        fun offset(p: TextPosition) = starts[p.line] + p.character
        return edits
            .sortedByDescending { offset(it.range.start) }
            .fold(text) { acc, edit ->
                acc.replaceRange(offset(edit.range.start), offset(edit.range.end), edit.newText)
            }
    }

    private fun renameAndReanalyse(
        f: Fixture,
        path: String,
        position: TextPosition,
        newName: String,
    ): Map<String, String> {
        val result = assertIs<RenameResult.Edits>(f.queries.rename(path, position, newName))
        val after = result.edits.mapValues { (file, edits) -> apply(f.text(file), edits) }
        after.forEach { (file, text) -> f.workspace.change(file, text) }
        val analysis = f.workspace.analysis(f.workspace.keyOf(path))
        assertEquals(
            emptyList(),
            analysis.diagnostics.values.flatten().map { "${it.code.id} ${it.message}" },
        )
        return after
    }

    @Test
    fun `renaming a declaration rewrites it and every use, across files`() {
        val f = Fixture(dir)
        val c = f.open("shop/customers.schemata", customers)
        val o = f.open("shop/orders.schemata", orders)
        val after = renameAndReanalyse(f, o, f.at(o, "Customer"), "Client")
        assertEquals("namespace shop.customers\nrecord Client { #1 id: uuid }\n", after[c])
        assertEquals(
            "namespace shop.orders\nimport shop.customers\n" +
                "record Order { /* 😀 */ #1 who: Client #2 all: list<Client> }\n",
            after[o],
        )
    }

    @Test
    fun `an edit after an astral character counts it as two units`() {
        val f = Fixture(dir)
        f.open("shop/customers.schemata", customers)
        val o = f.open("shop/orders.schemata", orders)
        val edits =
            assertIs<RenameResult.Edits>(f.queries.rename(o, f.at(o, "Customer"), "Client"))
                .edits
                .getValue(o)
        assertEquals(f.range(o, "Customer"), edits.minBy { it.range.start.character }.range)
    }

    @Test
    fun `renaming a field rewrites its uses in an annotation tuple`() {
        val f = Fixture(dir)
        val a =
            f.open(
                "m/a.schemata",
                "namespace m\n@sql(unique = (first, second))\n" +
                    "record R { #1 first: int32 #2 second: int32 }\n",
            )
        val edits = assertIs<RenameResult.Edits>(f.queries.rename(a, f.at(a, "first: "), "primary"))
        assertEquals(
            "namespace m\n@sql(unique = (primary, second))\n" +
                "record R { #1 primary: int32 #2 second: int32 }\n",
            apply(f.text(a), edits.edits.getValue(a)),
        )
    }

    @Test
    fun `renaming an enum value rewrites the defaults that use it`() {
        val f = Fixture(dir)
        val a =
            f.open(
                "m/a.schemata",
                "namespace m\nenum Status { #1 pending, #2 paid }\n" +
                    "record R { #1 s: Status = pending }\n",
            )
        val after = renameAndReanalyse(f, a, f.at(a, "pending"), "open")
        assertEquals(
            "namespace m\nenum Status { #1 open, #2 paid }\nrecord R { #1 s: Status = open }\n",
            after[a],
        )
    }

    @Test
    fun `renaming an import alias stays inside its file`() {
        val f = Fixture(dir)
        f.open("shop/customers.schemata", customers)
        val o =
            f.open(
                "shop/orders.schemata",
                "namespace shop.orders\nimport shop.customers as cust\n" +
                    "record Order { #1 who: cust.Customer }\n",
            )
        val after = renameAndReanalyse(f, o, f.at(o, "cust.Customer"), "c")
        assertEquals(setOf(o), after.keys)
        assertEquals(
            "namespace shop.orders\nimport shop.customers as c\n" +
                "record Order { #1 who: c.Customer }\n",
            after[o],
        )
    }

    @Test
    fun `prepare returns the name's range and nothing for what cannot be renamed`() {
        val f = Fixture(dir)
        f.open("shop/customers.schemata", customers)
        val o = f.open("shop/orders.schemata", orders)
        assertEquals(f.range(o, "Customer"), f.queries.prepareRename(o, f.at(o, "Customer")))
        assertNull(f.queries.prepareRename(o, f.at(o, "shop.customers")))
        assertNull(f.queries.prepareRename(o, f.at(o, "list")))
        assertNull(f.queries.prepareRename(o, f.at(o, "record")))
    }

    private fun refusal(f: Fixture, path: String, position: TextPosition, name: String): String =
        assertIs<RenameResult.Refused>(f.queries.rename(path, position, name)).message

    @Test
    fun `rename refuses a name that is not an identifier, is a keyword, or collides`() {
        val f = Fixture(dir)
        f.open("shop/customers.schemata", customers)
        val o = f.open("shop/orders.schemata", orders)
        val at = f.at(o, "Order")
        assertEquals("'9lives' is not a valid name", refusal(f, o, at, "9lives"))
        assertEquals("'has space' is not a valid name", refusal(f, o, at, "has space"))
        assertEquals("'record' is a keyword", refusal(f, o, at, "record"))
        assertEquals(
            "'all' is already a field of shop.orders.Order",
            refusal(f, o, f.at(o, "who"), "all"),
        )
    }

    @Test
    fun `rename refuses a declaration name taken anywhere in the namespace`() {
        val f = Fixture(dir)
        val a = f.open("m/a.schemata", "namespace m\nrecord A { #1 x: int32 }\n")
        f.open("m/b.schemata", "namespace m\nrecord B { #1 x: int32 }\n")
        assertEquals("'B' is already declared in m", refusal(f, a, f.at(a, "A"), "B"))
    }

    @Test
    fun `rename refuses a namespace and a position with no symbol`() {
        val f = Fixture(dir)
        f.open("shop/customers.schemata", customers)
        val o = f.open("shop/orders.schemata", orders)
        assertEquals(
            "a namespace cannot be renamed; it is the name its files declare",
            refusal(f, o, f.at(o, "shop.customers"), "x"),
        )
        assertEquals("nothing to rename here", refusal(f, o, f.at(o, "record"), "x"))
    }

    @Test
    fun `rename refuses while any file of the set fails to parse`() {
        val f = Fixture(dir)
        val c = f.open("shop/customers.schemata", customers)
        val o = f.open("shop/orders.schemata", orders)
        f.workspace.change(c, "namespace shop.customers\nrecord Customer {")
        val message = refusal(f, o, f.at(o, "Order"), "Purchase")
        assertTrue(message.startsWith("fix the syntax errors in customers.schemata"), message)
    }

    @Test
    fun `a reserved name string and a target name override are left alone`() {
        val f = Fixture(dir)
        val a =
            f.open(
                "m/a.schemata",
                "namespace m\nrecord R {\n  @sql(column = \"note\") #1 note: string\n" +
                    "  reserved \"note\"\n}\n",
            )
        val edits = assertIs<RenameResult.Edits>(f.queries.rename(a, f.at(a, "note:"), "memo"))
        assertEquals(
            "namespace m\nrecord R {\n  @sql(column = \"note\") #1 memo: string\n" +
                "  reserved \"note\"\n}\n",
            apply(f.text(a), edits.edits.getValue(a)),
        )
    }
}
