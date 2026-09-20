package io.schemata.target.sql

import io.schemata.core.ir.Builtin
import io.schemata.core.ir.Field
import io.schemata.core.ir.RecordType
import io.schemata.core.ir.Schema
import kotlin.test.Test
import kotlin.test.assertEquals

class SqlLoweringTest {
    private val schema =
        Schema(
            namespace = "shop.orders",
            records =
                listOf(
                    RecordType(
                        "OrderLine",
                        listOf(
                            Field(1, "id", Builtin.UUID, nullable = false),
                            Field(2, "note", Builtin.STRING, nullable = true),
                            Field(3, "quantity", Builtin.INT32, nullable = false),
                            Field(4, "gift", Builtin.BOOL, nullable = false),
                        ),
                    )
                ),
        )

    @Test
    fun `uses the last namespace segment as the schema name`() {
        assertEquals("orders", SqlLowering.lower(schema).model.schemaName)
    }

    @Test
    fun `names tables in lower_snake and keeps column names`() {
        val table = SqlLowering.lower(schema).model.tables.single()
        assertEquals("order_line", table.name)
        assertEquals(listOf("id", "note", "quantity", "gift"), table.columns.map { it.name })
    }

    @Test
    fun `maps scalars to dialect-neutral column types`() {
        val table = SqlLowering.lower(schema).model.tables.single()
        assertEquals(
            listOf(ColumnType.UUID, ColumnType.TEXT, ColumnType.INTEGER, ColumnType.BOOLEAN),
            table.columns.map { it.type },
        )
    }

    @Test
    fun `nullable becomes a nullable column`() {
        val table = SqlLowering.lower(schema).model.tables.single()
        assertEquals(listOf(false, true, false, false), table.columns.map { it.nullable })
    }

    @Test
    fun `flat scalar records lower without diagnostics`() {
        assertEquals(emptyList(), SqlLowering.lower(schema).diagnostics)
    }
}
