package io.schemata.target.sql

import io.schemata.core.ir.Builtin
import io.schemata.core.ir.Field
import io.schemata.core.ir.Namespace
import io.schemata.core.ir.RecordType
import io.schemata.core.ir.Schema
import io.schemata.lang.Span
import kotlin.test.Test
import kotlin.test.assertEquals

class SqlLoweringTest {
    private fun at(file: String, line: Int) = Span(file, line, 1, line, 30)

    private fun field(ordinal: Int, name: String, type: Builtin, nullable: Boolean = false) =
        Field(ordinal, name, type, nullable, at("o.schemata", 10 + ordinal))

    private fun namespace(name: String, vararg records: RecordType, file: String = "o.schemata") =
        Namespace(name, records.toList(), at(file, 1))

    private fun record(name: String, vararg fields: Field, line: Int = 3) =
        RecordType(name, fields.toList(), at("o.schemata", line))

    private val orders =
        namespace(
            "shop.orders",
            record(
                "OrderLine",
                field(1, "id", Builtin.UUID),
                field(2, "note", Builtin.STRING, nullable = true),
                field(3, "quantity", Builtin.INT32),
                field(4, "gift", Builtin.BOOL),
            ),
        )

    @Test
    fun `emits one relational schema per namespace with path and last-segment name`() {
        val customers =
            namespace("shop.customers", record("Customer", field(1, "name", Builtin.STRING)))
        val schemas = SqlLowering.lower(Schema(listOf(customers, orders))).model.schemas
        assertEquals(
            listOf("shop/customers.sql" to "customers", "shop/orders.sql" to "orders"),
            schemas.map { it.path to it.schemaName },
        )
    }

    @Test
    fun `names tables in lower_snake and keeps column names`() {
        val table = SqlLowering.lower(Schema(listOf(orders))).model.schemas.single().tables.single()
        assertEquals("order_line", table.name)
        assertEquals(listOf("id", "note", "quantity", "gift"), table.columns.map { it.name })
    }

    @Test
    fun `maps scalars to dialect-neutral column types`() {
        val table = SqlLowering.lower(Schema(listOf(orders))).model.schemas.single().tables.single()
        assertEquals(
            listOf(ColumnType.UUID, ColumnType.TEXT, ColumnType.INTEGER, ColumnType.BOOLEAN),
            table.columns.map { it.type },
        )
    }

    @Test
    fun `nullable becomes a nullable column`() {
        val table = SqlLowering.lower(Schema(listOf(orders))).model.schemas.single().tables.single()
        assertEquals(listOf(false, true, false, false), table.columns.map { it.nullable })
    }

    @Test
    fun `flat scalar records lower without diagnostics`() {
        assertEquals(emptyList(), SqlLowering.lower(Schema(listOf(orders))).diagnostics)
    }

    @Test
    fun `diagnoses table-name collisions at the first colliding record`() {
        val ns =
            namespace(
                "shop.orders",
                record("Abc", field(1, "id", Builtin.UUID), line = 3),
                record("ABC", field(1, "id", Builtin.UUID), line = 4),
            )
        val d = SqlLowering.lower(Schema(listOf(ns))).diagnostics.single()
        assertEquals("records Abc and ABC both lower to table 'abc'", d.message)
        assertEquals(3, d.span.startLine)
    }

    @Test
    fun `diagnoses schema-name collisions across namespaces`() {
        val a =
            namespace("shop.orders", record("A", field(1, "id", Builtin.UUID)), file = "a.schemata")
        val b =
            namespace(
                "store.orders",
                record("B", field(1, "id", Builtin.UUID)),
                file = "b.schemata",
            )
        val d = SqlLowering.lower(Schema(listOf(a, b))).diagnostics.single()
        assertEquals(
            "namespaces shop.orders and store.orders both lower to schema 'orders'",
            d.message,
        )
        assertEquals("a.schemata", d.span.file)
    }

    @Test
    fun `distinct names yield no collision diagnostics`() {
        val ns =
            namespace(
                "shop.orders",
                record("Abc", field(1, "id", Builtin.UUID)),
                record("Def", field(1, "id", Builtin.UUID)),
            )
        assertEquals(emptyList(), SqlLowering.lower(Schema(listOf(ns))).diagnostics)
    }
}
