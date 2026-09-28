package io.schemata.cli

import com.github.ajalt.clikt.testing.test
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VersionTest {
    private val grammar =
        Regex("""\d+\.\d+\.\d+(-dev\+[0-9a-f]{7})?(-dirty)?|0\.0\.0-unknown|unknown""")

    @Test
    fun `--version prints the program name and the version`() {
        val result = Schemata().test("--version")
        assertEquals(0, result.statusCode, result.stderr)
        val line = result.stdout.trim()
        assertTrue(line.startsWith("schemata "), line)
        assertTrue(grammar.matches(line.removePrefix("schemata ")), line)
    }
}
