package io.schemata.migrate

import io.schemata.lang.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MigrateCodesTest {
    private val base =
        """
        schema s
        model Customer {
          #1 id uuid { id }
          #2 note string?
        }
        """

    @Test
    fun `a destructive step is an error unless allowed and then a warning with the same id`() {
        val migration = Planner.plan(side(base), side(base.replace("  #2 note string?\n", "")))
        val blocked = MigrateCodes.diagnostics(migration, allowDestructive = false).single()
        val allowed = MigrateCodes.diagnostics(migration, allowDestructive = true).single()
        assertEquals("SCH2701", blocked.code.id)
        assertEquals(Severity.ERROR, blocked.severity)
        assertEquals(Severity.WARNING, allowed.severity)
        assertEquals(
            "s.Customer.note: DROP COLUMN \"note\" loses every value the column holds",
            blocked.message,
        )
        assertEquals(
            "rerun with --allow-destructive once the data is migrated or no longer needed",
            blocked.help,
        )
        assertEquals(4, blocked.span.startLine)
    }

    @Test
    fun `a step that may fail is a warning naming the backfill`() {
        val migration =
            Planner.plan(side(base), side(base.replace("#2 note string?", "#2 note string")))
        val d = MigrateCodes.diagnostics(migration, allowDestructive = false).single()
        assertEquals("SCH2702", d.code.id)
        assertEquals(
            "s.Customer.note: SET NOT NULL on \"note\" fails when a row holds NULL",
            d.message,
        )
        assertEquals(
            "run UPDATE \"s\".\"customer\" SET \"note\" = … WHERE \"note\" IS NULL before applying",
            d.help,
        )
    }

    @Test
    fun `a clean migration reports nothing`() {
        val migration =
            Planner.plan(
                side(base),
                side(base.replace("#2 note string?", "#2 note string?\n  #3 tier int32?")),
            )
        assertEquals(emptyList(), MigrateCodes.diagnostics(migration, allowDestructive = false))
    }

    @Test
    fun `a message names the statement and never repeats the full sql`() {
        val old =
            """
            schema s
            model Customer {
              #1 id uuid { id }
              #2 name string { max 100 }
              #3 note string?
              #4 age int64
            }
            model Gone { #1 id uuid { id } }
            """
        val new =
            """
            schema s
            model Customer {
              #1 id uuid { id }
              #2 name string { max 10 }
              #3 note string
              #4 age int32 { max 5 }
            }
            """
        val steps = Planner.plan(side(old), side(new)).steps.filter { it.risk != Risk.CLEAN }
        assertTrue(steps.size >= 4, steps.toString())
        steps.forEach { step ->
            val message = MigrateCodes.message(step)
            assertFalse("ALTER TABLE" in message || "DROP TABLE \"s\"." in message, message)
            assertFalse(message.endsWith(";"), message)
        }
    }

    @Test
    fun `a message for a clean step has no reason to give`() {
        val migration =
            Planner.plan(
                side(base),
                side(base.replace("#2 note string?", "#2 note string?\n  #3 tier int32?")),
            )
        assertFailsWith<IllegalStateException> { MigrateCodes.message(migration.steps.single()) }
    }

    @Test
    fun `a moved key's help says to populate the copies from the parent`() {
        val old =
            """
            schema s
            model Order {
              #1 id uuid { id }
              #2 code string { max 8 }
              #3 lines Line[]
              model Line { #1 sku string { max 8 } }
            }
            """
        val new =
            old.replace("#1 id uuid { id }", "#1 id uuid")
                .replace("#2 code string { max 8 }", "#2 code string { id, max 8 }")
        val help =
            MigrateCodes.diagnostics(Planner.plan(side(old), side(new)), false)
                .single { it.code.id == "SCH2701" }
                .help
        assertEquals(
            "populate \"order_lines\".\"order_code\" from the parent before the foreign keys return, then rerun with --allow-destructive",
            help,
        )
    }
}
