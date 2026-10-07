package io.schemata.target.sql

import io.schemata.core.ir.QualifiedName
import io.schemata.lang.Span
import kotlin.test.Test
import kotlin.test.assertEquals

class DdlTest {
    private val span = Span("test.schemata", 1, 1, 1, 1)

    private val column =
        Column(
            name = "last4",
            type = ColumnType.VARCHAR(4),
            nullable = false,
            default = "'0000'",
            origin = ColumnOrigin.Role("test"),
            span = span,
        )

    @Test
    fun `a column definition spells type nullability and default`() {
        assertEquals("varchar(4) NOT NULL DEFAULT '0000'", Ddl.columnDefinition(column))
    }

    @Test
    fun `create table lists columns then constraints and ends the block`() {
        val table =
            Table(
                name = "card",
                columns = listOf(column),
                primaryKey = listOf("last4"),
                primaryKeyName = "pk_card",
                checks = listOf(Check("ck_card_last4_max", "char_length(\"last4\") <= 4")),
                origin = TableOrigin(QualifiedName("s", listOf("Card"))),
                span = span,
            )
        assertEquals(
            """
            CREATE TABLE "s"."card" (
              "last4" varchar(4) NOT NULL DEFAULT '0000',
              CONSTRAINT "pk_card" PRIMARY KEY ("last4"),
              CONSTRAINT "ck_card_last4_max" CHECK (char_length("last4") <= 4)
            );
            """
                .trimIndent() + "\n",
            Ddl.createTable("s", table),
        )
    }

    @Test
    fun `a null comment removes the comment`() {
        assertEquals(
            "COMMENT ON COLUMN \"s\".\"card\".\"last4\" IS NULL;",
            Ddl.commentOnColumn("s", "card", "last4", null),
        )
        assertEquals(
            "COMMENT ON TABLE \"s\".\"card\" IS 'A ''card''.';",
            Ddl.commentOnTable("s", "card", "A 'card'."),
        )
    }

    @Test
    fun `a foreign key statement is the one compile prints`() {
        val fk =
            ForeignKey(
                "fk_order_customer",
                "shop",
                "order",
                listOf("customer_id"),
                "customers",
                "customer",
                listOf("id"),
                cascade = false,
            )
        assertEquals(
            "ALTER TABLE \"shop\".\"order\" ADD CONSTRAINT \"fk_order_customer\" FOREIGN KEY (\"customer_id\") REFERENCES \"customers\".\"customer\" (\"id\");",
            Ddl.addForeignKey(fk),
        )
    }
}
