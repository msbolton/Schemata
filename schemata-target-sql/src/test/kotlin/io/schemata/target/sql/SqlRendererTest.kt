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

    @Test
    fun `renders the golden file with quoted identifiers at the lowered path`() {
        val out = SqlRenderer.render(RelationalModel(listOf(orders))).single()
        assertEquals("shop/orders.sql", out.path)
        Golden.assertMatches("user.sql", out.content)
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
