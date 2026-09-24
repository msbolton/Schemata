package io.schemata.target.sql

import io.schemata.testkit.Golden
import kotlin.test.Test
import kotlin.test.assertEquals

class SqlRendererTest {
    // `user` is a reserved word in Postgres; the renderer must quote it.
    private val orders =
        RelationalSchema(
            path = "shop/orders.sql",
            schemaName = "orders",
            tables =
                listOf(
                    Table(
                        "user",
                        listOf(
                            Column("id", ColumnType.UUID, nullable = false),
                            Column("email", ColumnType.TEXT, nullable = true),
                            Column("name", ColumnType.TEXT, nullable = false),
                            Column("age", ColumnType.INTEGER, nullable = false),
                        ),
                    )
                ),
        )

    private val customers =
        RelationalSchema(
            path = "shop/customers.sql",
            schemaName = "customers",
            tables =
                listOf(Table("customer", listOf(Column("name", ColumnType.TEXT, nullable = false)))),
        )

    /** Every construct the renderer prints, in one file. */
    private val kitchen =
        RelationalSchema(
            path = "corpus/kitchen.sql",
            schemaName = "kitchen",
            tables =
                listOf(
                    Table(
                        name = "product",
                        columns =
                            listOf(
                                Column("id", ColumnType.UUID, nullable = false),
                                Column(
                                    "sku",
                                    ColumnType.VARCHAR(64),
                                    nullable = false,
                                    doc = "Stock keeping unit.",
                                ),
                                Column(
                                    "status",
                                    ColumnType.TEXT,
                                    nullable = false,
                                    default = "'pending'",
                                ),
                                Column("price", ColumnType.NUMERIC(19, 4), nullable = false),
                                Column(
                                    "stock",
                                    ColumnType.INTEGER,
                                    nullable = false,
                                    default = "0",
                                ),
                                Column("ratio", ColumnType.DOUBLE, nullable = true),
                                Column("tags", ColumnType.ARRAY(ColumnType.TEXT), nullable = true),
                                Column("meta", ColumnType.JSONB, nullable = true),
                                Column("created", ColumnType.TIMESTAMPTZ, nullable = false),
                                Column(
                                    "legacy",
                                    ColumnType.RAW("varchar(36)"),
                                    nullable = true,
                                    notes = listOf("uuid?"),
                                ),
                            ),
                        primaryKey = listOf("id"),
                        checks =
                            listOf(
                                Check(
                                    "ck_product_status_enum",
                                    "\"status\" IN ('pending', 'paid')",
                                ),
                                Check("ck_product_stock_min", "\"stock\" >= 0"),
                            ),
                        uniques = listOf(Unique("uq_product_sku", listOf("sku"))),
                        indexes = listOf(Index("ix_product_status", listOf("status"))),
                        doc = "A product for sale.",
                    ),
                    Table(
                        name = "order_line",
                        columns =
                            listOf(
                                Column("order_id", ColumnType.UUID, nullable = false),
                                Column("position", ColumnType.INTEGER, nullable = false),
                                Column("sku", ColumnType.TEXT, nullable = false),
                            ),
                        primaryKey = listOf("order_id", "position"),
                    ),
                    Table("empty", emptyList()),
                ),
            foreignKeys =
                listOf(
                    ForeignKey(
                        name = "fk_order_line_order_id",
                        table = "order_line",
                        columns = listOf("order_id"),
                        targetSchema = "kitchen",
                        targetTable = "product",
                        targetColumns = listOf("id"),
                        cascade = true,
                    )
                ),
        )

    @Test
    fun `renders the phase 1 golden unchanged`() {
        val out = SqlRenderer.render(RelationalModel(listOf(orders))).single()
        assertEquals("shop/orders.sql", out.path)
        Golden.assertMatches("user.sql", out.content)
    }

    @Test
    fun `renders every construct`() {
        val out = SqlRenderer.render(RelationalModel(listOf(kitchen))).single()
        assertEquals("corpus/kitchen.sql", out.path)
        Golden.assertMatches("kitchen.sql", out.content)
    }

    @Test
    fun `renders one output per schema in model order`() {
        val outs = SqlRenderer.render(RelationalModel(listOf(customers, orders)))
        assertEquals(listOf("shop/customers.sql", "shop/orders.sql"), outs.map { it.path })
    }

    @Test
    fun `target is named sql`() {
        assertEquals("sql", SqlTarget.name)
    }
}
