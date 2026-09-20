package io.schemata.cli

import io.schemata.core.CoreCodes
import io.schemata.lang.LangCodes
import io.schemata.target.proto.ProtoCodes
import io.schemata.target.sql.SqlCodes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The catalogs live in different modules; this is the one place that can see them all. */
class DiagnosticCodesTest {
    private val all = LangCodes.all + CoreCodes.all + ProtoCodes.all + SqlCodes.all

    @Test
    fun `every code id is unique across modules`() {
        val duplicates = all.groupBy { it.id }.filter { it.value.size > 1 }.keys
        assertEquals(emptySet(), duplicates)
    }

    @Test
    fun `every code id matches SCH followed by four digits`() {
        all.forEach { assertTrue(Regex("SCH\\d{4}").matches(it.id), it.id) }
    }

    @Test
    fun `ranges are respected by module`() {
        assertTrue(LangCodes.all.all { it.id.startsWith("SCH0") })
        assertTrue(CoreCodes.all.all { it.id.startsWith("SCH1") })
        assertTrue(ProtoCodes.all.all { it.id.startsWith("SCH20") })
        assertTrue(SqlCodes.all.all { it.id.startsWith("SCH21") })
    }
}
