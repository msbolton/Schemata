package io.schemata.target.sql

import io.schemata.core.AnalysisOptions
import io.schemata.core.Analyzer
import io.schemata.core.annotations.AnnotationRegistry
import io.schemata.core.annotations.CoreAnnotations
import io.schemata.core.ir.QualifiedName
import io.schemata.lang.Parser
import io.schemata.target.sql.ColumnOrigin.FieldPath
import io.schemata.target.sql.ColumnOrigin.Role
import io.schemata.target.sql.OriginStep.FieldOrdinal
import io.schemata.target.sql.OriginStep.MemberOrdinal
import kotlin.test.Test
import kotlin.test.assertEquals

/** Every table and column says which record and field chain produced it, whatever it is named. */
class ProvenanceTest {
    private val source =
        """
        namespace s

        record Card { #1 last4: string(max = 4) }

        enum Status { #1 open, #2 closed }

        union Payment = #1 Card | #2 string | #3 Status | #4 Customer

        record Address { #1 street: string #2 city: string }

        record Customer { @sql(key) #2 id: uuid }

        record Order {
          @sql(key) #1 id: uuid
          @sql(column = "buyer") #2 customer: Customer
          #3 payment: Payment
          @sql(strategy = embed) #4 shipping: Address
          #5 lines: list<Line>
          #6 tags: list<string>
          @sql(strategy = table) #7 notes: map<string, string>
          @sql(strategy = table) #8 stops: map<string, Address>
          #9 watchers: list<Customer>

          record Line { #1 sku: string #2 qty: int32 #3 parts: list<Part> }
          record Part { #1 name: string }
        }
        """
            .trimIndent()

    private val model: RelationalModel =
        SqlLowering.lower(
                Analyzer.analyze(
                        listOf(Parser.parse(source, "s.schemata").file!!),
                        AnalysisOptions(
                            annotations =
                                AnnotationRegistry(CoreAnnotations.specs + SqlAnnotations.specs)
                        ),
                    )
                    .schema!!
            )
            .model

    private val order = QualifiedName("s", listOf("Order"))

    private fun table(name: String) = model.schemas.single().tables.single { it.name == name }

    private fun origin(table: String, column: String) =
        table(table).columns.single { it.name == column }.origin

    @Test
    fun `a record's table is its own origin and a child table carries the field path`() {
        assertEquals(TableOrigin(order), table("order").origin)
        assertEquals(TableOrigin(order, listOf(FieldOrdinal(5))), table("order_lines").origin)
        assertEquals(TableOrigin(order, listOf(FieldOrdinal(7))), table("order_notes").origin)
    }

    @Test
    fun `a nested child table keeps its parent's path and no two tables share an origin`() {
        assertEquals(
            TableOrigin(order, listOf(FieldOrdinal(5), FieldOrdinal(3))),
            table("order_lines_parts").origin,
        )
        val origins = model.schemas.single().tables.map { it.origin }
        assertEquals(origins.size, origins.toSet().size)
    }

    @Test
    fun `a scalar column is its field and a renamed column keeps the ordinal`() {
        assertEquals(FieldPath(listOf(FieldOrdinal(1))), origin("order", "id"))
        assertEquals(FieldPath(listOf(FieldOrdinal(2)), "k2"), origin("order", "buyer_id"))
        assertEquals(FieldPath(listOf(FieldOrdinal(6))), origin("order", "tags"))
    }

    @Test
    fun `embedded and variant columns chain through the field and the member`() {
        assertEquals(
            FieldPath(listOf(FieldOrdinal(4), FieldOrdinal(1))),
            origin("order", "shipping_street"),
        )
        assertEquals(FieldPath(listOf(FieldOrdinal(3)), "kind"), origin("order", "payment_kind"))
        assertEquals(
            FieldPath(listOf(FieldOrdinal(3), MemberOrdinal(1), FieldOrdinal(1))),
            origin("order", "payment_card_last4"),
        )
        assertEquals(
            FieldPath(listOf(FieldOrdinal(3), MemberOrdinal(2))),
            origin("order", "payment_string"),
        )
        assertEquals(
            FieldPath(listOf(FieldOrdinal(3), MemberOrdinal(3))),
            origin("order", "payment_status"),
        )
        assertEquals(
            FieldPath(listOf(FieldOrdinal(3), MemberOrdinal(4)), "k2"),
            origin("order", "payment_customer_id"),
        )
    }

    @Test
    fun `a child table's synthetic columns are roles and its element columns are fields`() {
        assertEquals(Role("parent:1"), origin("order_lines", "order_id"))
        assertEquals(Role("position"), origin("order_lines", "position"))
        assertEquals(FieldPath(listOf(FieldOrdinal(1))), origin("order_lines", "sku"))
        assertEquals(Role("key"), origin("order_notes", "key"))
        assertEquals(Role("value"), origin("order_notes", "value"))
    }

    @Test
    fun `a grandchild's parent columns copy the child's key by identity`() {
        assertEquals(Role("parent:1"), origin("order_lines_parts", "order_lines_order_id"))
        assertEquals(Role("parent:position"), origin("order_lines_parts", "order_lines_position"))
    }

    @Test
    fun `a map of records embeds its value under the synthetic field`() {
        assertEquals(Role("key"), origin("order_stops", "key"))
        assertEquals(
            FieldPath(listOf(FieldOrdinal(0), FieldOrdinal(1))),
            origin("order_stops", "value_street"),
        )
    }

    @Test
    fun `a keyed element's reference columns copy its key field by ordinal`() {
        assertEquals(FieldPath(listOf(FieldOrdinal(0)), "k2"), origin("order_watchers", "value_id"))
    }

    @Test
    fun `spans point at the declaring name`() {
        assertEquals(13, table("order").span.startLine)
        assertEquals(15, table("order").columns.single { it.name == "buyer_id" }.span.startLine)
        assertEquals(18, table("order_lines").span.startLine)
    }
}
