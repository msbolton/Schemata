package io.schemata.cli

import io.schemata.core.AnalysisOptions
import io.schemata.core.Analyzer
import io.schemata.core.ir.AnnotationValue
import io.schemata.core.ir.Builtin
import io.schemata.core.ir.EnumRef
import io.schemata.core.ir.ListOf
import io.schemata.core.ir.QualifiedName
import io.schemata.core.ir.RecordType
import io.schemata.core.ir.Ref
import io.schemata.core.ir.Refinements
import io.schemata.core.ir.Scalar
import io.schemata.lang.Parser
import io.schemata.target.proto.ProtoTarget
import io.schemata.target.sql.SqlTarget
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The spec's worked example analyzes clean; targets only report what they cannot lower yet. */
class WorkedExampleTest {
    private val orders =
        """
        /// Order management for the storefront.
        @sql(schema = "shop")
        namespace shop.orders

        import shop.customers

        alias Email = string(max = 254, pattern = "^[^@]+@[^@]+$")
        alias Money = decimal(19, 4)

        enum Status { #1 pending, #2 paid, #3 shipped, #4 cancelled }

        record Card         { #1 last4: string(max = 4)  #2 brand: string(max = 32) }
        record BankTransfer { #1 iban: string(max = 34) }
        record Cash         {}

        union Payment = #1 Card | #2 BankTransfer | #3 Cash

        /// A customer's order. One row per checkout.
        record Order {
          @sql(key)
          #1 id:         uuid
          #2 customer:   Customer
          #3 status:     Status = pending
          #4 lines:      list<Line>(min = 1)
          #5 total:      Money
          #6 payment:    Payment
          @sql(strategy = embed)
          #7 shipping:   Address
          #8 placed_at:  instant
          #9 note:       string(max = 500)?
          @deprecated("use placed_at")
          #10 created:   instant?
          reserved #11, "legacy_ref"

          /// One purchasable item.
          record Line {
            #1 sku:      string(max = 64)
            #2 quantity: int32(min = 1)
            #3 price:    Money
          }

          record Address {
            #1 street:   string(max = 200)
            #2 city:     string(max = 100)
            #3 country:  string(min = 2, max = 2)
          }
        }
        """
            .trimIndent()

    private val customers =
        "namespace shop.customers\n\nrecord Customer {\n  @sql(key) #1 id:   uuid\n  #2 name: string(max = 100)\n}"

    private fun qn(ns: String, vararg path: String) = QualifiedName(ns, path.toList())

    @Test
    fun `the worked example analyzes with zero diagnostics`() {
        val files =
            listOf(
                Parser.parse(orders, "orders.schemata").file!!,
                Parser.parse(customers, "customers.schemata").file!!,
            )
        val result = Analyzer.analyze(files, AnalysisOptions(annotations = Pipeline.annotations))
        assertEquals(emptyList(), result.diagnostics)
        val schema = result.schema!!
        val ns = schema.namespaces.first { it.name == "shop.orders" }
        assertEquals(AnnotationValue.Str("shop"), ns.annotations["sql"]["schema"])
        val order = schema.lookup(qn("shop.orders", "Order")) as RecordType
        assertEquals((1..10).toList(), order.fields.map { it.ordinal })
        assertEquals(AnnotationValue.Flag, order.fields[0].annotations["sql"]["key"])
        assertEquals(EnumRef(qn("shop.orders", "Status"), "pending"), order.fields[2].default)
        assertEquals(
            ListOf(
                Ref(qn("shop.orders", "Order", "Line")),
                false,
                Refinements(min = BigDecimal.valueOf(1)),
            ),
            order.fields[3].type,
        )
        assertEquals(
            Scalar(Builtin.DECIMAL, Refinements(precision = 19, scale = 4)) to "Money",
            order.fields[4].type to order.fields[4].aliasName,
        )
        assertEquals(AnnotationValue.Name("embed"), order.fields[6].annotations["sql"]["strategy"])
        assertEquals(
            Scalar(Builtin.STRING, Refinements(max = BigDecimal.valueOf(500))) to true,
            order.fields[8].type to order.fields[8].nullable,
        )
        assertEquals(
            AnnotationValue.Str("use placed_at"),
            order.fields[9].annotations[""]["deprecated"],
        )
        val address = schema.lookup(qn("shop.orders", "Order", "Address")) as RecordType
        assertEquals(
            Scalar(
                Builtin.STRING,
                Refinements(min = BigDecimal.valueOf(2), max = BigDecimal.valueOf(2)),
            ),
            address.fields[2].type,
        )
    }

    @Test
    fun `sql compiles the worked example with warnings only`() {
        val result =
            Pipeline.compile(
                listOf(
                    SourceInput("orders.schemata", orders),
                    SourceInput("customers.schemata", customers),
                ),
                listOf(SqlTarget),
            )
        assertFalse(result.hasErrors)
        assertEquals(
            listOf("shop/customers.sql", "shop/orders.sql"),
            result.files.map { it.file.path },
        )
        assertEquals(
            listOf(
                "orders.schemata:24 SCH2105 field 'Order.lines': refinements on list<Line>(min = 1) are not enforced by Postgres"
            ),
            result.diagnostics.map {
                "${it.span.file}:${it.span.startLine} ${it.code.id} ${it.message}"
            },
        )
    }

    @Test
    fun `both targets compile the worked example with warnings only`() {
        val result =
            Pipeline.compile(
                listOf(
                    SourceInput("orders.schemata", orders),
                    SourceInput("customers.schemata", customers),
                ),
                Pipeline.targets,
            )
        assertFalse(result.hasErrors)
        assertEquals(4, result.files.size)
        assertEquals(setOf("SCH2001", "SCH2105"), result.diagnostics.map { it.code.id }.toSet())
    }

    @Test
    fun `proto compiles the worked example with warnings only`() {
        val result =
            Pipeline.compile(
                listOf(
                    SourceInput("orders.schemata", orders),
                    SourceInput("customers.schemata", customers),
                ),
                listOf(ProtoTarget),
            )
        assertFalse(result.hasErrors)
        assertEquals(
            listOf("shop/customers.proto", "shop/orders.proto"),
            result.files.map { it.file.path },
        )
        assertEquals(
            listOf(
                "customers.schemata:4 field 'Customer.id': uuid has no Protobuf representation; lowered to string",
                "customers.schemata:5 field 'Customer.name': refinements on string(max = 100) are not enforced by Protobuf",
                "orders.schemata:10 enum 'Status': proto3 requires a zero value; synthesized STATUS_UNSPECIFIED = 0",
                "orders.schemata:12 field 'Card.last4': refinements on string(max = 4) are not enforced by Protobuf",
                "orders.schemata:12 field 'Card.brand': refinements on string(max = 32) are not enforced by Protobuf",
                "orders.schemata:13 field 'BankTransfer.iban': refinements on string(max = 34) are not enforced by Protobuf",
                "orders.schemata:20 field 'Order.id': uuid has no Protobuf representation; lowered to string",
                "orders.schemata:23 field 'Order.status': default pending is not carried by proto3",
                "orders.schemata:24 field 'Order.lines': refinements on list<Line>(min = 1) are not enforced by Protobuf",
                "orders.schemata:25 field 'Order.total': decimal has no Protobuf representation; lowered to string",
                "orders.schemata:30 field 'Order.note': refinements on string(max = 500) are not enforced by Protobuf",
                "orders.schemata:37 field 'Line.sku': refinements on string(max = 64) are not enforced by Protobuf",
                "orders.schemata:38 field 'Line.quantity': refinements on int32(min = 1) are not enforced by Protobuf",
                "orders.schemata:39 field 'Line.price': decimal has no Protobuf representation; lowered to string",
                "orders.schemata:43 field 'Address.street': refinements on string(max = 200) are not enforced by Protobuf",
                "orders.schemata:44 field 'Address.city': refinements on string(max = 100) are not enforced by Protobuf",
                "orders.schemata:45 field 'Address.country': refinements on string(min = 2, max = 2) are not enforced by Protobuf",
            ),
            result.diagnostics.map { "${it.span.file}:${it.span.startLine} ${it.message}" },
        )
        assertTrue(result.diagnostics.all { it.code.id == "SCH2001" })
    }
}
