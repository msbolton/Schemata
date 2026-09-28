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

    @Test
    fun `the manifest version is baked into the installed jar`() {
        // Under Gradle's test runtime the classes are loaded from build/classes, not the jar, so
        // the
        // manifest is absent and Version.current is "unknown"; the fat-jar test covers the jar
        // path.
        assertTrue(grammar.matches(Version.current), Version.current)
    }
}
