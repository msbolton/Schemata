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
        "customers.schemata" to "namespace shop.customers\nrecord Customer { id: uuid }"

    @Test
    fun `an unaliased import brings simple names into scope`() {
        val orders =
            "orders.schemata" to
                "namespace shop.orders\nimport shop.customers\nrecord Order { who: Customer }"
        val r = analyze(customers, orders)
        assertEquals(emptyList(), r.diagnostics)
        val order = r.schema!!.lookup(qn("shop.orders", "Order")) as RecordType
        assertEquals(Ref(qn("shop.customers", "Customer")), order.fields.single().type)
    }

    @Test
    fun `an aliased import contributes only through its alias`() {
        val orders =
            "orders.schemata" to
                "namespace shop.orders\nimport shop.customers as cust\nrecord Order { a: cust.Customer  b: Customer }"
        val r = analyze(customers, orders)
        assertNull(r.schema)
        assertEquals(listOf("orders.schemata:3:37 unknown type 'Customer'"), messages(r))
    }

    @Test
    fun `an import and a local declaration with the same name are ambiguous`() {
        val orders =
            "orders.schemata" to
                "namespace shop.orders\nimport shop.customers\nrecord Customer { x: bool }\nrecord Order { who: Customer }"
        val r = analyze(customers, orders)
        assertNull(r.schema)
        assertEquals(
            listOf(
                "orders.schemata:4:21 ambiguous type 'Customer': customers.schemata:2, orders.schemata:3"
            ),
            messages(r),
        )
    }

    @Test
    fun `two imports providing the same name are ambiguous and a qualified name resolves it`() {
        val other = "other.schemata" to "namespace other.customers\nrecord Customer { y: bool }"
        val orders =
            "orders.schemata" to
                "namespace shop.orders\nimport shop.customers\nimport other.customers\nrecord Order { a: Customer  b: other.customers.Customer }"
        val r = analyze(customers, other, orders)
        assertNull(r.schema)
        assertEquals(
            listOf(
                "orders.schemata:4:19 ambiguous type 'Customer': customers.schemata:2, other.schemata:2"
            ),
            messages(r),
        )
    }

    @Test
    fun `nested declarations shadow imports without ambiguity`() {
        val orders =
            "orders.schemata" to
                "namespace shop.orders\nimport shop.customers\nrecord Order {\n  who: Customer\n  record Customer { z: bool }\n}"
        val r = analyze(customers, orders)
        val order = r.schema!!.lookup(qn("shop.orders", "Order")) as RecordType
        assertEquals(Ref(qn("shop.orders", "Order", "Customer")), order.fields.single().type)
        assertEquals(listOf("orders.schemata:2:1 import 'shop.customers' is unused"), messages(r))
    }

    @Test
    fun `unknown and unused imports`() {
        val orders =
            "orders.schemata" to
                "namespace shop.orders\nimport shop.billing\nimport shop.customers\nrecord Order { x: bool }"
        val r = analyze(customers, orders)
        assertNull(r.schema)
        assertEquals(
            listOf(
                "orders.schemata:2:1 import 'shop.billing' does not name a namespace in this compilation",
                "orders.schemata:3:1 import 'shop.customers' is unused",
            ),
            messages(r),
        )
        assertEquals(listOf(Severity.ERROR, Severity.WARNING), r.diagnostics.map { it.severity })
    }

    @Test
    fun `aliases are substituted and remembered by name`() {
        val src =
            "namespace a\nalias Money = int64\nalias Tags = list<string>\nalias MaybeMoney = Money?\nrecord R {\n  total: Money\n  tags:  Tags\n  tip:   MaybeMoney\n  more:  Money?\n}"
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
    fun `alias cycles and double nullability are errors`() {
        val src =
            "namespace a\nalias A = B\nalias B = A\nalias N = string?\nrecord R { x: A  y: N? }"
        val r = analyze("a.schemata" to src)
        assertNull(r.schema)
        assertEquals(
            listOf(
                "a.schemata:3:11 alias 'A' refers to itself",
                "a.schemata:5:21 'N' is already nullable; remove the '?'",
            ),
            messages(r),
        )
    }
}
