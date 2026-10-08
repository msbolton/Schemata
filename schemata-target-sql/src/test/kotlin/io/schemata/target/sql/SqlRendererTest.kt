package io.schemata.target.sql

import io.schemata.core.ir.OnDelete
import io.schemata.core.ir.QualifiedName
import io.schemata.lang.Span
import io.schemata.testkit.Golden
import kotlin.test.Test
import kotlin.test.assertEquals

class SqlRendererTest {
    // The renderer ignores provenance, so the hand-built models give every table and column the
    // same placeholder.
    private val none = Span("test.schemata", 1, 1, 1, 1)

    private fun table(
        name: String,
        columns: List<Column>,
        primaryKey: List<String> = emptyList(),
        primaryKeyName: String? = null,
        checks: List<Check> = emptyList(),
        uniques: List<Unique> = emptyList(),
        indexes: List<Index> = emptyList(),
        doc: String? = null,
    ) =
        Table(
            name,
            columns,
            primaryKey,
            primaryKeyName,
            checks,
            uniques,
            indexes,
            doc,
            TableOrigin(QualifiedName("t", listOf("R"))),
            none,
        )

    private fun column(
        name: String,
        type: ColumnType,
        nullable: Boolean,
        default: String? = null,
        doc: String? = null,
        notes: List<String> = emptyList(),
    ) = Column(name, type, nullable, default, doc, notes, ColumnOrigin.Role("test"), none)

    // `user` is a reserved word in Postgres; the renderer must quote it.
    private val orders =
        RelationalSchema(
            path = "shop/orders.sql",
            schemaName = "orders",
            tables =
                listOf(
                    table(
                        "user",
                        listOf(
                            column("id", ColumnType.UUID, nullable = false),
                            column("email", ColumnType.TEXT, nullable = true),
                            column("name", ColumnType.TEXT, nullable = false),
                            column("age", ColumnType.INTEGER, nullable = false),
                        ),
                    )
                ),
        )

    private val customers =
        RelationalSchema(
            path = "shop/customers.sql",
            schemaName = "customers",
            tables =
                listOf(table("customer", listOf(column("name", ColumnType.TEXT, nullable = false)))),
        )

    /** Every construct the renderer prints, in one file. */
    private val kitchen =
        RelationalSchema(
            path = "corpus/kitchen.sql",
            schemaName = "kitchen",
            tables =
                listOf(
                    table(
                        name = "product",
                        columns =
                            listOf(
                                column("id", ColumnType.UUID, nullable = false),
                                column(
                                    "sku",
                                    ColumnType.VARCHAR(64),
                                    nullable = false,
                                    doc = "Stock keeping unit.",
                                ),
                                column(
                                    "status",
                                    ColumnType.TEXT,
                                    nullable = false,
                                    default = "'pending'",
                                ),
                                column("price", ColumnType.NUMERIC(19, 4), nullable = false),
                                column(
                                    "stock",
                                    ColumnType.INTEGER,
                                    nullable = false,
                                    default = "0",
                                ),
                                column("ratio", ColumnType.DOUBLE, nullable = true),
                                column("tags", ColumnType.ARRAY(ColumnType.TEXT), nullable = true),
                                column("meta", ColumnType.JSONB, nullable = true),
                                column("created", ColumnType.TIMESTAMPTZ, nullable = false),
                                column(
                                    "legacy",
                                    ColumnType.RAW("varchar(36)"),
                                    nullable = true,
                                    notes = listOf("uuid?"),
                                ),
                            ),
                        primaryKey = listOf("id"),
                        primaryKeyName = "pk_product",
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
                    table(
                        name = "order_line",
                        columns =
                            listOf(
                                column("order_id", ColumnType.UUID, nullable = false),
                                column("position", ColumnType.INTEGER, nullable = false),
                                column("sku", ColumnType.TEXT, nullable = false),
                            ),
                        primaryKey = listOf("order_id", "position"),
                        primaryKeyName = "pk_order_line",
                    ),
                    table("empty", emptyList()),
                ),
            foreignKeys =
                listOf(
                    ForeignKey(
                        name = "fk_order_line_order_id",
                        schema = "kitchen",
                        table = "order_line",
                        columns = listOf("order_id"),
                        targetSchema = "kitchen",
                        targetTable = "product",
                        targetColumns = listOf("id"),
                        onDelete = OnDelete.CASCADE,
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
