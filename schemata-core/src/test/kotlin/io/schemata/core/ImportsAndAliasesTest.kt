package io.schemata.core

import io.schemata.core.ir.Builtin
import io.schemata.core.ir.ListOf
import io.schemata.core.ir.QualifiedName
import io.schemata.core.ir.RecordType
import io.schemata.core.ir.Ref
import io.schemata.core.ir.Scalar
import io.schemata.lang.Parser
import io.schemata.lang.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ImportsAndAliasesTest {
    private fun analyze(vararg sources: Pair<String, String>): AnalysisResult =
        Analyzer.analyze(sources.map { (path, src) -> Parser.parse(src, path).file!! })

    private fun messages(r: AnalysisResult) =
        r.diagnostics.map {
            "${it.span.file}:${it.span.startLine}:${it.span.startColumn} ${it.message}"
        }

    private fun qn(ns: String, vararg path: String) = QualifiedName(ns, path.toList())

    private val customers =
        "customers.schemata" to "schema shop.customers\nmodel Customer { id uuid }"

    @Test
    fun `an unaliased import brings simple names into scope`() {
        val orders =
            "orders.schemata" to
                "schema shop.orders\nimport shop.customers\nmodel Order { who Customer }"
        val r = analyze(customers, orders)
        assertEquals(emptyList(), r.diagnostics)
        val order = r.schema!!.lookup(qn("shop.orders", "Order")) as RecordType
        assertEquals(Ref(qn("shop.customers", "Customer")), order.fields.single().type)
    }

    @Test
    fun `an aliased import contributes only through its alias`() {
        val orders =
            "orders.schemata" to
                "schema shop.orders\nimport shop.customers as cust\nmodel Order { a cust.Customer  b Customer }"
        val r = analyze(customers, orders)
        assertNull(r.schema)
        assertEquals(listOf("orders.schemata:3:34 unknown type 'Customer'"), messages(r))
    }

    @Test
    fun `an import and a local declaration with the same name are ambiguous`() {
        val orders =
            "orders.schemata" to
                "schema shop.orders\nimport shop.customers\nmodel Customer { x bool }\nmodel Order { who Customer }"
        val r = analyze(customers, orders)
        assertNull(r.schema)
        assertEquals(
            listOf(
                "orders.schemata:4:19 type 'Customer' is ambiguous; candidates: shop.customers.Customer, shop.orders.Customer"
            ),
            messages(r),
        )
    }

    @Test
    fun `two imports providing the same name are ambiguous and a qualified name resolves it`() {
        val other = "other.schemata" to "schema other.customers\nmodel Customer { y bool }"
        val orders =
            "orders.schemata" to
                "schema shop.orders\nimport shop.customers\nimport other.customers\nmodel Order { a Customer  b other.customers.Customer }"
        val r = analyze(customers, other, orders)
        assertNull(r.schema)
        assertEquals(
            listOf(
                "orders.schemata:4:17 type 'Customer' is ambiguous; candidates: shop.customers.Customer, other.customers.Customer"
            ),
            messages(r),
        )
    }

    @Test
    fun `nested declarations shadow imports without ambiguity`() {
        val orders =
            "orders.schemata" to
                "schema shop.orders\nimport shop.customers\nmodel Order {\n  who Customer\n  model Customer { z bool }\n}"
        val r = analyze(customers, orders)
        val order = r.schema!!.lookup(qn("shop.orders", "Order")) as RecordType
        assertEquals(Ref(qn("shop.orders", "Order", "Customer")), order.fields.single().type)
        assertEquals(listOf("orders.schemata:2:1 import 'shop.customers' is unused"), messages(r))
    }

    @Test
    fun `unknown and unused imports`() {
        val orders =
            "orders.schemata" to
                "schema shop.orders\nimport shop.billing\nimport shop.customers\nmodel Order { x bool }"
        val r = analyze(customers, orders)
        assertNull(r.schema)
        assertEquals(
            listOf(
                "orders.schemata:2:1 import 'shop.billing' does not name a schema in this compilation",
                "orders.schemata:3:1 import 'shop.customers' is unused",
            ),
            messages(r),
        )
        assertEquals(listOf(Severity.ERROR, Severity.WARNING), r.diagnostics.map { it.severity })
    }

    @Test
    fun `aliases are substituted and remembered by name`() {
        val src =
            "schema a\nalias Money = int64\nalias Tags = string[]\nalias MaybeMoney = Money?\nmodel R {\n  total Money\n  tags  Tags\n  tip   MaybeMoney\n  more  Money?\n}"
        val r = analyze("a.schemata" to src)
        assertEquals(emptyList(), r.diagnostics)
        val rec = r.schema!!.lookup(qn("a", "R")) as RecordType
        assertEquals(
            listOf(
                Scalar(Builtin.INT64),
                ListOf(Scalar(Builtin.STRING), nullableElement = false),
                Scalar(Builtin.INT64),
                Scalar(Builtin.INT64),
            ),
            rec.fields.map { it.type },
        )
        assertEquals(listOf(false, false, true, true), rec.fields.map { it.nullable })
        assertEquals(
            listOf("Money", "Tags", "MaybeMoney", "Money"),
            rec.fields.map { it.aliasName },
        )
        assertNotNull(r.schema!!.lookupOrNull(qn("a", "R")))
        assertNull(r.schema!!.lookupOrNull(qn("a", "Money")))
    }

    @Test
    fun `an import alias is lower_snake`() {
        val legacy = "legacy.schemata" to "schema shop.legacy\nmodel Address { old bool }"
        val orders =
            "orders.schemata" to
                "schema shop.orders\nimport shop.legacy as Order\nimport shop.customers as Bad__x\nmodel R { a Order.Address  b Bad__x.Customer }"
        val r = analyze(customers, legacy, orders)
        assertNull(r.schema)
        assertEquals(
            listOf(
                "SCH1045 orders.schemata:2:23 import alias 'Order' must be lower_snake; help: rename it `order`",
                "SCH1045 orders.schemata:3:26 import alias 'Bad__x' must be lower_snake; help: rename it `bad_x`",
            ),
            r.diagnostics.map {
                "${it.code.id} ${it.span.file}:${it.span.startLine}:${it.span.startColumn} ${it.message}; help: ${it.help}"
            },
        )
    }

    @Test
    fun `a repeated import or alias is an error`() {
        val legacy = "legacy.schemata" to "schema shop.legacy\nmodel Address { old bool }"
        val orders =
            "orders.schemata" to
                "schema shop.orders\nimport shop.customers\nimport shop.customers as cust\nimport shop.legacy as cust\nmodel R { a Customer  b cust.Customer }"
        val r = analyze(customers, legacy, orders)
        assertNull(r.schema)
        assertEquals(
            listOf(
                "SCH1046 orders.schemata:3:1 schema 'shop.customers' is imported more than once; help: keep one import of `shop.customers`",
                "SCH1046 orders.schemata:4:23 alias 'cust' is given to more than one import; help: give each import its own alias",
            ),
            r.diagnostics.map {
                "${it.code.id} ${it.span.file}:${it.span.startLine}:${it.span.startColumn} ${it.message}; help: ${it.help}"
            },
        )
    }

    @Test
    fun `a failed nested lookup through an import alias reports once`() {
        val orders =
            "orders.schemata" to
                "schema shop.orders\nimport shop.customers as cust\nmodel Order { x cust.Customer.Nope }"
        val r = analyze(customers, orders)
        assertEquals(
            listOf("orders.schemata:3:17 type 'Customer' has no nested type 'Nope'"),
            messages(r),
        )
    }

    @Test
    fun `alias cycles and double nullability are errors`() {
        val src = "schema a\nalias A = B\nalias B = A\nalias N = string?\nmodel R { x A  y N? }"
        val r = analyze("a.schemata" to src)
        assertNull(r.schema)
        assertEquals(
            listOf(
                "a.schemata:3:11 alias 'A' refers to itself",
                "a.schemata:5:18 'N' is already nullable",
            ),
            messages(r),
        )
    }
}
