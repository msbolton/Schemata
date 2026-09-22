package io.schemata.core

import io.schemata.core.ir.Builtin
import io.schemata.core.ir.EnumType
import io.schemata.core.ir.ListOf
import io.schemata.core.ir.MapOf
import io.schemata.core.ir.QualifiedName
import io.schemata.core.ir.RecordType
import io.schemata.core.ir.Ref
import io.schemata.core.ir.Refinements
import io.schemata.core.ir.Scalar
import io.schemata.core.ir.StringValue
import io.schemata.core.ir.UnionType
import io.schemata.lang.Parser
import io.schemata.lang.ast.SourceFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnalyzerTest {
    private val fixture =
        """
        namespace shop.orders

        record User {
          id:    uuid
          email: string?
          name:  string
          age:   int32
        }
        """
            .trimIndent()

    private fun file(source: String, path: String = "test.schemata"): SourceFile =
        Parser.parse(source, path).file!!

    private fun analyze(vararg sources: Pair<String, String>): AnalysisResult =
        Analyzer.analyze(sources.map { (path, src) -> file(src, path) })

    private fun analyze(source: String): AnalysisResult = analyze("test.schemata" to source)

    private fun messages(result: AnalysisResult) =
        result.diagnostics.map { "${it.span.startLine}:${it.span.startColumn} ${it.message}" }

    private fun qn(ns: String, vararg path: String) = QualifiedName(ns, path.toList())

    @Test
    fun `lowers the fixture to IR with implicit ordinals and spans`() {
        val result = analyze(fixture)
        assertEquals(emptyList(), result.diagnostics)
        val ns = assertNotNull(result.schema).namespaces.single()
        assertEquals("shop.orders", ns.name)
        val user = ns.declarations.single() as RecordType
        assertEquals(qn("shop.orders", "User"), user.qualifiedName)
        assertEquals(3, user.span.startLine)
        assertEquals(listOf(1, 2, 3, 4), user.fields.map { it.ordinal })
        assertEquals(
            listOf(
                Scalar(Builtin.UUID),
                Scalar(Builtin.STRING),
                Scalar(Builtin.STRING),
                Scalar(Builtin.INT32),
            ),
            user.fields.map { it.type },
        )
        assertEquals(listOf(false, true, false, false), user.fields.map { it.nullable })
        assertEquals(listOf(4, 5, 6, 7), user.fields.map { it.span.startLine })
        assertTrue(user.fields.all { it.span.file == "test.schemata" })
    }

    @Test
    fun `is deterministic`() {
        assertEquals(analyze(fixture), analyze(fixture))
    }

    @Test
    fun `resolves same-namespace, nested, and qualified-nested references`() {
        val src =
            """
            namespace a
            record Order {
              shipping: Address
              line:     Line
              record Address { city: string }
              record Line { addr: Address  outer: Order.Address }
            }
            record Invoice { addr: Order.Address }
            """
                .trimIndent()
        val result = analyze(src)
        assertEquals(emptyList(), result.diagnostics)
        val schema = result.schema!!
        val order = schema.lookup(qn("a", "Order")) as RecordType
        assertEquals(Ref(qn("a", "Order", "Address")), order.fields[0].type)
        assertEquals(Ref(qn("a", "Order", "Line")), order.fields[1].type)
        assertEquals(listOf("Address", "Line"), order.nested.map { it.name })
        val line = schema.lookup(qn("a", "Order", "Line")) as RecordType
        assertEquals(Ref(qn("a", "Order", "Address")), line.fields[0].type)
        assertEquals(Ref(qn("a", "Order", "Address")), line.fields[1].type)
        val invoice = schema.lookup(qn("a", "Invoice")) as RecordType
        assertEquals(Ref(qn("a", "Order", "Address")), invoice.fields[0].type)
    }

    @Test
    fun `innermost nested declaration wins`() {
        val src =
            "namespace a\nrecord Tag { x: bool }\nrecord Post {\n  t: Tag\n  record Tag { y: bool }\n}"
        val post = analyze(src).schema!!.lookup(qn("a", "Post")) as RecordType
        assertEquals(Ref(qn("a", "Post", "Tag")), post.fields.single().type)
    }

    @Test
    fun `resolves fully qualified names across namespaces without an import`() {
        val a = "namespace shop.customers\nrecord Customer { id: uuid }"
        val b = "namespace shop.orders\nrecord Order { who: shop.customers.Customer }"
        val result = analyze("a.schemata" to a, "b.schemata" to b)
        assertEquals(emptyList(), result.diagnostics)
        val order = result.schema!!.lookup(qn("shop.orders", "Order")) as RecordType
        assertEquals(Ref(qn("shop.customers", "Customer")), order.fields.single().type)
    }

    @Test
    fun `lowers enums, unions, lists, and maps`() {
        val src =
            """
            namespace a
            enum Status { pending, paid }
            record Card { last4: string }
            union Payment = Card | uuid
            record Order {
              status: Status
              tags:   list<string?>
              extra:  map<string, Card?>
              pay:    Payment
            }
            """
                .trimIndent()
        val result = analyze(src)
        assertEquals(emptyList(), result.diagnostics)
        val schema = result.schema!!
        val status = schema.lookup(qn("a", "Status")) as EnumType
        assertEquals(
            listOf("pending" to 1, "paid" to 2),
            status.values.map { it.name to it.ordinal },
        )
        val payment = schema.lookup(qn("a", "Payment")) as UnionType
        assertEquals(
            listOf(Ref(qn("a", "Card")), Scalar(Builtin.UUID)),
            payment.members.map { it.type },
        )
        assertEquals(listOf(1, 2), payment.members.map { it.ordinal })
        val order = schema.lookup(qn("a", "Order")) as RecordType
        assertEquals(Ref(qn("a", "Status")), order.fields[0].type)
        assertEquals(ListOf(Scalar(Builtin.STRING), nullableElement = true), order.fields[1].type)
        assertEquals(
            MapOf(Scalar(Builtin.STRING), Ref(qn("a", "Card")), nullableValue = true),
            order.fields[2].type,
        )
    }

    @Test
    fun `lowers every builtin`() {
        // decimal always needs its precision and scale; every other builtin takes none here.
        val names =
            Builtin.entries.map { if (it == Builtin.DECIMAL) "decimal(19, 4)" else it.typeName }
        val src =
            "namespace a\nrecord R {\n" +
                names.mapIndexed { i, n -> "  f$i: $n" }.joinToString("\n") +
                "\n}"
        val result = analyze(src)
        assertEquals(emptyList(), result.diagnostics)
        val r = result.schema!!.lookup(qn("a", "R")) as RecordType
        assertEquals(
            Builtin.entries.map {
                if (it == Builtin.DECIMAL) Scalar(it, Refinements(precision = 19, scale = 4))
                else Scalar(it)
            },
            r.fields.map { it.type },
        )
    }

    @Test
    fun `merges files that share a namespace and orders namespaces by name`() {
        val b = "namespace shop.orders\nrecord Beta { x: bool }"
        val a = "namespace shop.orders\nrecord Alpha { x: bool }"
        val z = "namespace zoo\nrecord Z { x: bool }"
        val result = analyze("b.schemata" to b, "z.schemata" to z, "a.schemata" to a)
        assertEquals(emptyList(), result.diagnostics)
        assertEquals(listOf("shop.orders", "zoo"), result.schema!!.namespaces.map { it.name })
        val orders = result.schema!!.namespaces[0]
        assertEquals(listOf("Alpha", "Beta"), orders.declarations.map { it.name })
        assertEquals("a.schemata", orders.span.file)
    }

    @Test
    fun `reports duplicate declarations within and across files with the kind`() {
        val a = "namespace n\nrecord R { x: bool }\nenum R { a }"
        val b = "namespace n\n\nrecord R { y: bool }"
        val result = analyze("a.schemata" to a, "b.schemata" to b)
        assertNull(result.schema)
        assertEquals(
            listOf(
                "3:6 enum 'R' is declared more than once",
                "3:8 record 'R' is declared in both a.schemata:2 and b.schemata:3",
            ),
            messages(result),
        )
    }

    @Test
    fun `reports unknown and missing nested types at the name`() {
        val result =
            analyze(
                "namespace a\nrecord Order { record Line {} }\nrecord R { x: money  y: Order.Nope }"
            )
        assertNull(result.schema)
        assertEquals(
            listOf("3:15 unknown type 'money'", "3:25 type 'Order' has no nested type 'Nope'"),
            messages(result),
        )
    }

    @Test
    fun `a failed nested lookup through a qualified name reports once`() {
        val a = "a.schemata" to "namespace shop.customers\nrecord Customer { id: uuid }"
        val b =
            "b.schemata" to
                "namespace shop.orders\nrecord Order { x: shop.customers.Customer.Nope }"
        val result = analyze(a, b)
        assertNull(result.schema)
        assertEquals(listOf("2:19 type 'Customer' has no nested type 'Nope'"), messages(result))
    }

    @Test
    fun `validates generics`() {
        val src =
            "namespace a\nrecord C {}\nrecord R {\n  a: list<string, bool>\n  b: map<string>\n  c: C<bool>\n  d: map<C, bool>\n  e: map<string?, bool>\n}"
        val result = analyze(src)
        assertNull(result.schema)
        assertEquals(
            listOf(
                "4:6 list takes 1 type argument, got 2",
                "5:6 map takes 2 type arguments, got 1",
                "6:6 'C' is not generic",
                "7:10 map keys must be string, int32, or int64",
                "8:10 map keys may not be nullable",
            ),
            messages(result),
        )
    }

    @Test
    fun `validates enums and unions`() {
        val src =
            "namespace a\nenum E {}\nenum F { Bad, x, x }\nrecord C {}\nunion U = C | C | U | list<C>"
        val result = analyze(src)
        assertNull(result.schema)
        assertEquals(
            listOf(
                "2:6 enum 'E' has no values",
                "3:10 enum value 'Bad' must be lower_snake",
                "3:18 enum value 'x' is declared more than once in enum 'F'",
                "5:15 union member 'C' is repeated",
                "5:19 union 'U' may not contain itself",
                "5:23 union members must be named types or scalars",
            ),
            messages(result),
        )
    }

    @Test
    fun `enforces naming for every declaration kind`() {
        val result =
            analyze(
                "namespace a\nrecord bad_r { F: bool }\nenum bad_e { a }\nunion bad_u = bad_r\nalias bad_a = string"
            )
        assertNull(result.schema)
        assertEquals(
            listOf(
                "2:8 record name 'bad_r' must be UpperCamel",
                "2:16 field name 'F' must be lower_snake",
                "3:6 enum name 'bad_e' must be UpperCamel",
                "4:7 union name 'bad_u' must be UpperCamel",
                "5:7 alias name 'bad_a' must be UpperCamel",
            ),
            messages(result),
        )
    }

    @Test
    fun `enforces lower_snake namespace segments and duplicate fields`() {
        assertEquals(
            listOf("1:1 namespace segment 'Shop' must be lower_snake"),
            messages(analyze("namespace Shop.orders")),
        )
        assertEquals(
            listOf("2:21 field 'x' is declared more than once in record 'R'"),
            messages(analyze("namespace a\nrecord R { x: bool  x: bool }")),
        )
    }

    @Test
    fun `carries docs and defaults through unchanged`() {
        val src = "namespace a\n/// about R\nrecord R {\n  /// about x\n  x: string = \"v\"\n}"
        val r = analyze(src).schema!!.lookup(qn("a", "R")) as RecordType
        assertEquals("about R", r.doc)
        assertEquals("about x", r.fields.single().doc)
        assertEquals(StringValue("v"), r.fields.single().default)
    }
}
