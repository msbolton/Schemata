package io.schemata.cli.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

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
