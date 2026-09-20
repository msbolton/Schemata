package io.schemata.target.sql

import io.schemata.testkit.Golden
import kotlin.test.Test
import kotlin.test.assertEquals

class SqlRendererTest {
    // `user` is a reserved word in Postgres; the renderer must quote it.
    private val schema =
        RelationalSchema(
            "orders",
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

    @Test
    fun `renders the golden file with quoted identifiers`() {
        val out = SqlRenderer.render(schema).single()
        assertEquals("orders.sql", out.path)
        Golden.assertMatches("user.sql", out.content)
    }

    @Test
    fun `target exposes lower and render under the name sql`() {
        assertEquals("sql", SqlTarget.name)
    }
}
