package io.schemata.migrate

import kotlin.test.Test
import kotlin.test.assertEquals

class MigrationRendererTest {
    private val base =
        """
        namespace s
        record Customer {
          @sql(key) #1 id: uuid
          #2 name: string(max = 100)
          #3 note: string?
        }
        """

    private fun sqlOf(old: String, new: String) = plan(old, new).map(MigrationRenderer::sql)

    @Test
    fun `each step spells its statement`() {
        assertEquals(
            listOf("ALTER TABLE \"s\".\"customer\" ADD COLUMN \"tier\" integer;"),
            sqlOf(base, base.replace("#3 note: string?", "#3 note: string?\n  #4 tier: int32?")),
        )
        assertEquals(
            listOf("ALTER TABLE \"s\".\"customer\" RENAME COLUMN \"note\" TO \"comment\";"),
            sqlOf(base, base.replace("#3 note:", "#3 comment:")),
        )
        assertEquals(
            listOf(
                "ALTER TABLE \"s\".\"customer\" ALTER COLUMN \"name\" TYPE varchar(200) USING \"name\"::varchar(200);"
            ),
            sqlOf(base, base.replace("string(max = 100)", "string(max = 200)")),
        )
        assertEquals(
            listOf(
                "ALTER TABLE \"s\".\"customer\" ALTER COLUMN \"note\" SET DEFAULT '';",
                "UPDATE \"s\".\"customer\" SET \"note\" = '' WHERE \"note\" IS NULL;",
                "ALTER TABLE \"s\".\"customer\" ALTER COLUMN \"note\" SET NOT NULL;",
            ),
            sqlOf(base, base.replace("#3 note: string?", "#3 note: string = \"\"")),
        )
        assertEquals(
            listOf("ALTER TABLE \"s\".\"customer\" DROP COLUMN \"note\";"),
            sqlOf(base, base.replace("  #3 note: string?\n", "")),
        )
    }

    @Test
    fun `drop table cascades and a foreign key drop is if-exists`() {
        val old =
            """
            namespace s
            record Customer { @sql(key) #1 id: uuid }
            record Order { @sql(key) #1 id: uuid  #2 customer: Customer? }
            """
        val new = old.replace("  #2 customer: Customer?", "")
        val sql = sqlOf(old, new)
        assertEquals(
            listOf(
                "ALTER TABLE \"s\".\"order\" DROP CONSTRAINT IF EXISTS \"fk_order_customer\";",
                "ALTER TABLE \"s\".\"order\" DROP COLUMN \"customer_id\";",
            ),
            sql,
        )
        val removed = "namespace s\nrecord Order { @sql(key) #1 id: uuid }\n"
        val dropped = sqlOf(old, removed)
        assertEquals(
            listOf(
                "ALTER TABLE \"s\".\"order\" DROP CONSTRAINT IF EXISTS \"fk_order_customer\";",
                "ALTER TABLE \"s\".\"order\" DROP COLUMN \"customer_id\";",
                "DROP TABLE \"s\".\"customer\" CASCADE;",
            ),
            dropped,
        )
    }

    @Test
    fun `a file wraps its steps in a transaction and marks a destructive step`() {
        val migration = Planner.plan(side(base), side(base.replace("  #3 note: string?\n", "")))
        assertEquals(
            """
            BEGIN;

            -- SCH2701: s.Customer.note: DROP COLUMN "note" loses every value the column holds
            ALTER TABLE "s"."customer" DROP COLUMN "note";

            COMMIT;
            """
                .trimIndent() + "\n",
            MigrationRenderer.render(migration.namespaces.single(), allowDestructive = true),
        )
        assertEquals(
            listOf("migrate/s.sql"),
            MigrationRenderer.files(migration, true).map { it.first },
        )
    }

    @Test
    fun `a created table renders as compile prints it followed by its comments`() {
        val new = base + "\n/// Tags.\nrecord Tag { @sql(key) #1 id: uuid }\n"
        val sql = sqlOf(base, new)
        assertEquals(2, sql.size)
        assert(sql[0].startsWith("CREATE TABLE \"s\".\"tag\" (\n")) { sql[0] }
        assertEquals("COMMENT ON TABLE \"s\".\"tag\" IS 'Tags.';", sql[1])
    }
}
