package io.schemata.cli.diagnostics

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir

/** Every fixture renders exactly its `expected.txt`; `SCHEMATA_GOLDEN_UPDATE=1` rewrites them. */
class DiagnosticFixturesTest {
    private val update = System.getenv("SCHEMATA_GOLDEN_UPDATE") == "1"

    @TestFactory
    fun `fixtures render their expected report`(): List<DynamicTest> =
        Fixture.all().map { f ->
            DynamicTest.dynamicTest(f.name) {
                val actual = f.render()
                if (update) f.write(actual)
                assertEquals(
                    f.expected,
                    actual,
                    "${f.name}; run with SCHEMATA_GOLDEN_UPDATE=1 to accept",
                )
            }
        }

    /**
     * `SCH2401-sql-namespace` is the one fixture with a repeated block: its two clashing schemas
     * each derive the schema name `s` from the one file, and the importer reports each.
     */
    private val reportsTheSameBlockForEachSchema = setOf("SCH2401-sql-namespace")

    @Test
    fun `no fixture repeats a report block`() {
        val repeated =
            Fixture.all()
                .filter { it.name !in reportsTheSameBlockForEachSchema }
                .associate { it.name to Fixture.duplicateBlocks(it.expected) }
                .filterValues { it.isNotEmpty() }
        assertEquals(emptyMap(), repeated)
    }

    @Test
    fun `a repeated block is found and a distinct one is not`() {
        val a = "error[SCH1001]: x\n --> s:1:1\n  = help: h"
        val b = "error[SCH1001]: x\n --> s:2:1\n  = help: h"
        assertEquals(listOf(a), Fixture.duplicateBlocks("$a\n\n$a\n\n1 error"))
        assertEquals(emptyList(), Fixture.duplicateBlocks("$a\n\n$b\n\n2 errors"))
    }

    @Test
    fun `header options are matched whole`(@TempDir dir: File) {
        File(dir, "expected.txt").writeText("# targets=proto,sql strict\n")
        val fixture = Fixture(dir)
        assertTrue(fixture.strict)
        assertEquals(listOf("proto", "sql"), fixture.targets.map { it.name })
        assertFalse(fixture.isDiff || fixture.isMigrate || fixture.isUpgrade)
        File(dir, "expected.txt").writeText("# targets=proto\n")
        assertFalse(Fixture(dir).strict)
    }

    @Test
    fun `a header line with trailing text is rejected`() {
        Fixture.checkHeader("# targets=proto", "ok")
        Fixture.checkHeader("# diff=old,new targets=sql", "ok")
        Fixture.checkHeader("# migrate=old,new allow-destructive", "ok")
        assertFailsWith<IllegalArgumentException> {
            Fixture.checkHeader("# targets=proto and more text", "bad")
        }
        assertFailsWith<IllegalArgumentException> { Fixture.checkHeader("# targets=Proto", "bad") }
        assertFailsWith<IllegalArgumentException> { Fixture.checkHeader("# strictly", "bad") }
    }
}
