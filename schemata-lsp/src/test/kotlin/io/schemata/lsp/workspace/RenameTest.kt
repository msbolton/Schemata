package io.schemata.lsp.workspace

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class RenameTest {
    @TempDir lateinit var dir: Path

    private val customers = "schema shop.customers\nmodel Customer { #1 id uuid }\n"
    private val orders =
        "schema shop.orders\nimport shop.customers\n" +
            "model Order { /* 😀 */ #1 who Customer #2 all Customer[] }\n"

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
        assertEquals("schema shop.customers\nmodel Client { #1 id uuid }\n", after[c])
        assertEquals(
            "schema shop.orders\nimport shop.customers\n" +
                "model Order { /* 😀 */ #1 who Client #2 all Client[] }\n",
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
                "schema m\n@sql(unique: (first, second))\n" +
                    "model R { #1 first int32 #2 second int32 }\n",
            )
        val edits =
            assertIs<RenameResult.Edits>(f.queries.rename(a, f.at(a, "first int32"), "primary"))
        assertEquals(
            "schema m\n@sql(unique: (primary, second))\n" +
                "model R { #1 primary int32 #2 second int32 }\n",
            apply(f.text(a), edits.edits.getValue(a)),
        )
    }

    @Test
    fun `renaming an enum value rewrites the defaults that use it`() {
        val f = Fixture(dir)
        val a =
            f.open(
                "m/a.schemata",
                "schema m\nenum Status { #1 pending, #2 paid }\n" +
                    "model R { #1 s Status = pending }\n",
            )
        val after = renameAndReanalyse(f, a, f.at(a, "pending"), "open")
        assertEquals(
            "schema m\nenum Status { #1 open, #2 paid }\nmodel R { #1 s Status = open }\n",
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
                "schema shop.orders\nimport shop.customers as cust\n" +
                    "model Order { #1 who cust.Customer }\n",
            )
        val after = renameAndReanalyse(f, o, f.at(o, "cust.Customer"), "c")
        assertEquals(setOf(o), after.keys)
        assertEquals(
            "schema shop.orders\nimport shop.customers as c\n" +
                "model Order { #1 who c.Customer }\n",
            after[o],
        )
    }

    @Test
    fun `prepare returns the name's range and nothing for what cannot be renamed`() {
        val f = Fixture(dir)
        val c = f.open("shop/customers.schemata", customers)
        val o = f.open("shop/orders.schemata", orders)
        assertEquals(f.range(o, "Customer"), f.queries.prepareRename(o, f.at(o, "Customer")))
        assertNull(f.queries.prepareRename(o, f.at(o, "shop.customers")))
        assertNull(f.queries.prepareRename(c, f.at(c, "uuid")))
        assertNull(f.queries.prepareRename(o, f.at(o, "model")))
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
        assertEquals("'model' is a keyword", refusal(f, o, at, "model"))
        assertEquals(
            "'all' is already a field of shop.orders.Order",
            refusal(f, o, f.at(o, "who"), "all"),
        )
    }

    @Test
    fun `rename refuses a declaration name taken anywhere in the namespace`() {
        val f = Fixture(dir)
        val a = f.open("m/a.schemata", "schema m\nmodel A { #1 x int32 }\n")
        f.open("m/b.schemata", "schema m\nmodel B { #1 x int32 }\n")
        assertEquals("'B' is already declared in m", refusal(f, a, f.at(a, "A"), "B"))
    }

    @Test
    fun `rename refuses a namespace and a position with no symbol`() {
        val f = Fixture(dir)
        f.open("shop/customers.schemata", customers)
        val o = f.open("shop/orders.schemata", orders)
        assertEquals(
            "a schema cannot be renamed; it is the name its files declare",
            refusal(f, o, f.at(o, "shop.customers"), "x"),
        )
        assertEquals("nothing to rename here", refusal(f, o, f.at(o, "model"), "x"))
    }

    @Test
    fun `rename refuses while any file of the set fails to parse`() {
        val f = Fixture(dir)
        val c = f.open("shop/customers.schemata", customers)
        val o = f.open("shop/orders.schemata", orders)
        f.workspace.change(c, "schema shop.customers\nmodel Customer {")
        val message = refusal(f, o, f.at(o, "Order"), "Purchase")
        assertTrue(message.startsWith("fix the syntax errors in customers.schemata"), message)
    }

    private val nested =
        "schema m\nmodel Item { #1 x int32 }\n" +
            "model Order {\n  model Line { #1 y int32 }\n  #1 item Item\n  #2 line Line\n}\n"

    @Test
    fun `rename refuses a nested name that would capture a use of a top-level one`() {
        val f = Fixture(dir)
        val a = f.open("m/a.schemata", nested)
        assertEquals(
            "renaming to 'Item' would change what other names refer to",
            refusal(f, a, f.at(a, "Line {"), "Item"),
        )
    }

    @Test
    fun `rename refuses a top-level name that a nested declaration would capture`() {
        val f = Fixture(dir)
        val a = f.open("m/a.schemata", nested)
        assertEquals(
            "renaming to 'Line' would change what other names refer to",
            refusal(f, a, f.at(a, "Item {"), "Line"),
        )
    }

    @Test
    fun `rename refuses a name that an unaliased import makes ambiguous`() {
        val f = Fixture(dir)
        f.open("shop/customers.schemata", customers)
        val o =
            f.open(
                "shop/orders.schemata",
                "schema shop.orders\nimport shop.customers\n" +
                    "model Client { #1 id uuid }\n" +
                    "model Order { #1 who Customer #2 by Client }\n",
            )
        assertEquals(
            "renaming to 'Customer' would introduce errors",
            refusal(f, o, f.at(o, "Client {"), "Customer"),
        )
    }

    @Test
    fun `rename refuses an imported name that would make a local one ambiguous`() {
        val f = Fixture(dir)
        val c =
            f.open(
                "shop/customers.schemata",
                "schema shop.customers\nmodel Customer { #1 id uuid }\n" +
                    "model Foo { #1 id uuid }\n",
            )
        f.open(
            "shop/orders.schemata",
            "schema shop.orders\nimport shop.customers\n" +
                "model Order { #1 who Customer }\nmodel Book { #1 last Order }\n",
        )
        assertEquals(
            "renaming to 'Order' would introduce errors",
            refusal(f, c, f.at(c, "Foo"), "Order"),
        )
    }

    @Test
    fun `rename refuses a builtin type name, list, and map for a declaration`() {
        val f = Fixture(dir)
        f.open("shop/customers.schemata", customers)
        val o = f.open("shop/orders.schemata", orders)
        val at = f.at(o, "Order")
        assertEquals("'string' is a builtin type name", refusal(f, o, at, "string"))
        assertEquals("'list' is a builtin type name", refusal(f, o, at, "list"))
        assertEquals("'map' is a builtin type name", refusal(f, o, at, "map"))
    }

    @Test
    fun `rename refuses an import alias that a visible type name would capture`() {
        val f = Fixture(dir)
        f.open("shop/customers.schemata", customers)
        val o =
            f.open(
                "shop/orders.schemata",
                "schema shop.orders\nimport shop.customers as cust\n" +
                    "model Order { #1 who cust.Customer }\n",
            )
        assertEquals(
            "renaming to 'Order' would introduce errors",
            refusal(f, o, f.at(o, "cust.Customer"), "Order"),
        )
    }

    private fun leftBehind(type: String): String {
        val f = Fixture(dir)
        val c = f.open("shop/customers.schemata", customers)
        f.open(
            "shop/orders.schemata",
            "schema shop.orders\nimport shop.customers\n" +
                "model Order {\n  #1 who Customer\n  #2 odd $type\n}\n",
        )
        return refusal(f, c, f.at(c, "Customer"), "Client")
    }

    @Test
    fun `rename refuses while a map type that fails to resolve still names the symbol`() {
        assertEquals(
            "fix the type at orders.schemata:5 before renaming",
            leftBehind("map<Strng, Customer>"),
        )
    }

    @Test
    fun `rename refuses while a list with the wrong arity still names the symbol`() {
        assertEquals(
            "fix the type at orders.schemata:5 before renaming",
            leftBehind("list<Customer, int32>"),
        )
    }

    @Test
    fun `rename refuses while a type with arguments it cannot take names the symbol`() {
        assertEquals(
            "fix the type at orders.schemata:5 before renaming",
            leftBehind("Customer<int32>"),
        )
    }

    @Test
    fun `renaming an alias refuses while an unresolved type still starts with it`() {
        val f = Fixture(dir)
        f.open("shop/customers.schemata", customers)
        val o =
            f.open(
                "shop/orders.schemata",
                "schema shop.orders\nimport shop.customers as cust\n" +
                    "model Order {\n  #1 who cust.Customer\n  #2 odd list<cust.Customer, int32>\n}\n",
            )
        assertEquals(
            "fix the type at orders.schemata:5 before renaming",
            refusal(f, o, f.at(o, "cust.Customer"), "c"),
        )
    }

    @Test
    fun `renaming a union member rewrites the union`() {
        val f = Fixture(dir)
        val a =
            f.open(
                "m/a.schemata",
                "schema m\nmodel Card { #1 n string }\nmodel Cash {}\n" +
                    "union Payment = #1 Card | #2 Cash\n",
            )
        val after = renameAndReanalyse(f, a, f.at(a, "Card |"), "CreditCard")
        assertEquals(
            "schema m\nmodel CreditCard { #1 n string }\nmodel Cash {}\n" +
                "union Payment = #1 CreditCard | #2 Cash\n",
            after[a],
        )
    }

    @Test
    fun `renaming a map value and a list element rewrites both`() {
        val f = Fixture(dir)
        val a =
            f.open(
                "m/a.schemata",
                "schema m\nmodel Item { #1 n string }\n" +
                    "model Bag { #1 by_name map<string, Item> #2 all Item[] }\n",
            )
        val after = renameAndReanalyse(f, a, f.at(a, "Item {"), "Thing")
        assertEquals(
            "schema m\nmodel Thing { #1 n string }\n" +
                "model Bag { #1 by_name map<string, Thing> #2 all Thing[] }\n",
            after[a],
        )
    }

    @Test
    fun `renaming through an alias to an alias rewrites each link`() {
        val f = Fixture(dir)
        val text =
            "schema m\nmodel Item { #1 n string }\nalias First = Item\nalias Second = First\n" +
                "model Use { #1 a First #2 b Second }\n"
        val a = f.open("m/a.schemata", text)
        val once = renameAndReanalyse(f, a, f.at(a, "First ="), "Head").getValue(a)
        assertEquals(
            "schema m\nmodel Item { #1 n string }\nalias Head = Item\nalias Second = Head\n" +
                "model Use { #1 a Head #2 b Second }\n",
            once,
        )
        val g = Fixture(dir)
        val b = g.open("m/a.schemata", once)
        val twice = renameAndReanalyse(g, b, g.at(b, "Item {"), "Thing").getValue(b)
        assertEquals(
            "schema m\nmodel Thing { #1 n string }\nalias Head = Thing\nalias Second = Head\n" +
                "model Use { #1 a Head #2 b Second }\n",
            twice,
        )
    }

    @Test
    fun `renaming a declaration rewrites its fully qualified uses in another namespace`() {
        val f = Fixture(dir)
        val c = f.open("shop/customers.schemata", customers)
        val o =
            f.open(
                "shop/orders.schemata",
                "schema shop.orders\nmodel Order { #1 who shop.customers.Customer }\n",
            )
        val after = renameAndReanalyse(f, c, f.at(c, "Customer"), "Client")
        assertEquals("schema shop.orders\nmodel Order { #1 who shop.customers.Client }\n", after[o])
    }

    @Test
    fun `renaming an outer and an inner declaration rewrites a qualified nested use`() {
        val f = Fixture(dir)
        val text =
            "schema shop.orders\nmodel Order {\n  model Line { #1 n int32 }\n  #1 l Line\n}\n"
        val o = f.open("shop/orders.schemata", text)
        val b =
            f.open(
                "shop/billing.schemata",
                "schema shop.billing\nmodel Bill { #1 line shop.orders.Order.Line }\n",
            )
        val once = renameAndReanalyse(f, o, f.at(o, "Line {"), "Entry")
        assertEquals(
            "schema shop.billing\nmodel Bill { #1 line shop.orders.Order.Entry }\n",
            once[b],
        )
        val g = Fixture(dir)
        val o2 = g.open("shop/orders.schemata", once.getValue(o))
        val b2 = g.open("shop/billing.schemata", once.getValue(b))
        val twice = renameAndReanalyse(g, b2, g.at(b2, "Order."), "Purchase")
        assertEquals(
            "schema shop.billing\nmodel Bill { #1 line shop.orders.Purchase.Entry }\n",
            twice[b2],
        )
        assertEquals(
            "schema shop.orders\nmodel Purchase {\n  model Entry { #1 n int32 }\n  #1 l Entry\n}\n",
            twice[o2],
        )
    }

    @Test
    fun `a rename reads a closed file edited on disk before computing its edits`() {
        val f = Fixture(dir)
        val c = f.write("shop/customers.schemata", customers)
        val o = f.open("shop/orders.schemata", orders)
        f.workspace.analysis(f.workspace.keyOf(o))
        val moved = "schema shop.customers\n\n// moved down\nmodel Customer { #1 id uuid }\n"
        f.write("shop/customers.schemata", moved)
        val edits = assertIs<RenameResult.Edits>(f.queries.rename(o, f.at(o, "Customer"), "Client"))
        assertEquals(
            "schema shop.customers\n\n// moved down\nmodel Client { #1 id uuid }\n",
            apply(moved, edits.edits.getValue(c)),
        )
    }

    @Test
    fun `a rename refuses when a closed file it edits no longer matches the disk`() {
        val f = Fixture(dir)
        val c = f.write("shop/customers.schemata", customers)
        val o = f.open("shop/orders.schemata", orders)
        f.workspace.analysis(f.workspace.keyOf(o))
        // Same size and the same modification time: nothing short of reading it shows the edit.
        val file = Path.of(c)
        val stamp = Files.getLastModifiedTime(file)
        Files.writeString(file, customers.replace("id uuid", "ix uuid"))
        Files.setLastModifiedTime(file, stamp)
        assertEquals(
            "customers.schemata changed on disk; try again",
            refusal(f, o, f.at(o, "Customer"), "Client"),
        )
    }

    @Test
    fun `a reserved name string and a target name override are left alone`() {
        val f = Fixture(dir)
        val a =
            f.open(
                "m/a.schemata",
                "schema m\nmodel R {\n  @sql(column: \"note\") #1 note string\n" +
                    "  reserved \"note\"\n}\n",
            )
        val edits =
            assertIs<RenameResult.Edits>(f.queries.rename(a, f.at(a, "note string"), "memo"))
        assertEquals(
            "schema m\nmodel R {\n  @sql(column: \"note\") #1 memo string\n" +
                "  reserved \"note\"\n}\n",
            apply(f.text(a), edits.edits.getValue(a)),
        )
    }

    @Test
    fun `renaming a record used by operations updates every payload site`() {
        val f = Fixture(dir)
        val a = f.open("t/a.schemata", SERVICE_API)
        val edits =
            assertIs<RenameResult.Edits>(f.queries.rename(a, f.at(a, "Order {"), "Purchase")).edits
        assertEquals(3, edits.getValue(a).size)
        val after = renameAndReanalyse(f, a, f.at(a, "Order {"), "Purchase")
        assertEquals(
            SERVICE_API.replace("model Order", "model Purchase")
                .replace("Order  get", "Purchase  get")
                .replace("stream Order", "stream Purchase"),
            after[a],
        )
    }

    @Test
    fun `renaming an operation or a service follows the naming rules and collisions`() {
        val f = Fixture(dir)
        val a = f.open("t/a.schemata", SERVICE_API)
        val get = f.at(a, "get(")
        val orders = f.at(a, "Orders {")
        assertEquals("'list' is already an operation of t.Orders", refusal(f, a, get, "list"))
        assertEquals("operation name 'Get' must be lower_snake", refusal(f, a, get, "Get"))
        assertEquals("'null' is a keyword", refusal(f, a, get, "null"))
        assertEquals("'Order' is already declared in t", refusal(f, a, orders, "Order"))
        assertEquals(
            "service name 'purchases' must be UpperCamel",
            refusal(f, a, orders, "purchases"),
        )
        assertEquals("'Orders' is already declared in t", refusal(f, a, f.at(a, "Id"), "Orders"))
        assertEquals(
            SERVICE_API.replace("#1 get(", "#1 fetch("),
            renameAndReanalyse(f, a, get, "fetch")[a],
        )
        val g = Fixture(dir.resolve("other"))
        val b = g.open("t/a.schemata", SERVICE_API)
        assertEquals(
            SERVICE_API.replace("service Orders", "service Purchases"),
            renameAndReanalyse(g, b, g.at(b, "Orders {"), "Purchases")[b],
        )
    }

    @Test
    fun `an operation may take an HTTP verb as its name`() {
        val f = Fixture(dir)
        val a = f.open("t/a.schemata", SERVICE_API)
        assertEquals(
            SERVICE_API.replace("#2 list(", "#2 delete("),
            renameAndReanalyse(f, a, f.at(a, "list("), "delete")[a],
        )
    }

    @Test
    fun `a hoisted type cannot be renamed but its model can`() {
        val f = Fixture(dir)
        val a =
            f.open(
                "shop/a.schemata",
                "schema shop\nmodel Order {\n  #1 address { street string }\n}\n",
            )
        assertNull(f.queries.prepareRename(a, f.at(a, "{ street")))
        assertIs<RenameResult.Refused>(f.queries.rename(a, f.at(a, "{ street"), "Place"))
        assertIs<RenameResult.Edits>(f.queries.rename(a, f.at(a, "Order"), "Purchase"))
    }
}
