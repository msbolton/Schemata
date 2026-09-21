package io.schemata.cli

import io.schemata.core.Analyzer
import io.schemata.core.ir.Builtin
import io.schemata.core.ir.ListOf
import io.schemata.core.ir.MapOf
import io.schemata.core.ir.QualifiedName
import io.schemata.core.ir.RecordType
import io.schemata.core.ir.Ref
import io.schemata.core.ir.Scalar
import io.schemata.core.ir.UnionType
import io.schemata.lang.Parser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Plan B1's acceptance: the structural worked example analyzes clean; targets only report shapes
 * they cannot lower yet.
 */
class AcceptanceB1Test {
    private val orders =
        """
        /// Order management for the storefront.
        namespace shop.orders

        import shop.customers

        alias Money = int64

        enum Status { #1 pending, #2 paid, #3 shipped, #4 cancelled }

        record Card         { #1 last4: string  #2 brand: string }
        record BankTransfer { #1 iban: string }
        record Cash         {}

        union Payment = #1 Card | #2 BankTransfer | #3 Cash

        /// A customer's order. One row per checkout.
        record Order {
          #1 id:         uuid
          #2 customer:   Customer
          #3 status:     Status
          #4 lines:      list<Line>
          #5 total:      Money
          #6 payment:    Payment
          #7 shipping:   Address
          #8 placed_at:  instant
          #9 note:       string?
          #10 tags:      map<string, string>
          reserved #11, "legacy_ref"

          /// One purchasable item.
          record Line {
            #1 sku:      string
            #2 quantity: int32
            #3 price:    Money
          }

          record Address {
            #1 street:   string
            #2 city:     string
            #3 country:  string
          }
        }
        """
            .trimIndent()

    private val customers =
        "namespace shop.customers\n\nrecord Customer {\n  #1 id:   uuid\n  #2 name: string\n}"

    private fun qn(ns: String, vararg path: String) = QualifiedName(ns, path.toList())

    @Test
    fun `the structural worked example analyzes with zero diagnostics`() {
        val files =
            listOf(
                Parser.parse(orders, "orders.schemata").file!!,
                Parser.parse(customers, "customers.schemata").file!!,
            )
        val result = Analyzer.analyze(files)
        assertEquals(emptyList(), result.diagnostics)
        val schema = result.schema!!
        val order = schema.lookup(qn("shop.orders", "Order")) as RecordType
        assertEquals((1..10).toList(), order.fields.map { it.ordinal })
        assertEquals(Ref(qn("shop.customers", "Customer")), order.fields[1].type)
        assertEquals(Ref(qn("shop.orders", "Status")), order.fields[2].type)
        assertEquals(ListOf(Ref(qn("shop.orders", "Order", "Line")), false), order.fields[3].type)
        assertEquals(
            Scalar(Builtin.INT64) to "Money",
            order.fields[4].type to order.fields[4].aliasName,
        )
        assertEquals(Ref(qn("shop.orders", "Payment")), order.fields[5].type)
        assertEquals(
            MapOf(Scalar(Builtin.STRING), Scalar(Builtin.STRING), false),
            order.fields[9].type,
        )
        assertEquals(listOf(11..11), order.reserved.ordinals)
        assertEquals(listOf("Line", "Address"), order.nested.map { it.name })
        assertEquals("A customer's order. One row per checkout.", order.doc)
        val payment = schema.lookup(qn("shop.orders", "Payment")) as UnionType
        assertEquals(listOf(1, 2, 3), payment.members.map { it.ordinal })
    }

    @Test
    fun `targets report only the shapes they cannot lower yet`() {
        val result =
            Pipeline.compile(
                listOf(
                    SourceInput("orders.schemata", orders),
                    SourceInput("customers.schemata", customers),
                ),
                Pipeline.targets,
            )
        assertTrue(result.hasErrors)
        assertEquals(emptyList(), result.files)
        val codes = result.diagnostics.map { it.code.id }.toSet()
        assertEquals(setOf("SCH2001", "SCH2002", "SCH2103"), codes) // 2001: uuid lossy warnings
    }
}
