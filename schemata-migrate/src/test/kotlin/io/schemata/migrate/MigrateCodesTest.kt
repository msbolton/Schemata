package io.schemata.migrate

import io.schemata.lang.Severity
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
