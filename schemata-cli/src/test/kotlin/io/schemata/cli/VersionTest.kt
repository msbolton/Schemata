package io.schemata.cli

import com.github.ajalt.clikt.testing.test
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VersionTest {
    // `0.0.0-unknown` is what a source tree without git (a release tarball) builds as; on a git
    // checkout the version is a tag or `0.0.0-dev+<sha>`.
    private val grammar =
        Regex("""\d+\.\d+\.\d+(-rc\.\d+)?(-dev\+[0-9a-f]{7})?(-dirty)?|0\.0\.0-unknown""")

    @Test
    fun `--version prints the program name and the version`() {
        val result = Schemata().test("--version")
        assertEquals(0, result.statusCode, result.stderr)
        val line = result.stdout.trim()
        assertTrue(line.startsWith("schemata "), line)
        assertTrue(grammar.matches(line.removePrefix("schemata ")), line)
    }

    @Test
    fun `the version constant is generated from the build and is what --version prints`() {
        assertTrue(grammar.matches(BuildVersion.VERSION), BuildVersion.VERSION)
        assertEquals(BuildVersion.VERSION, Version.current)
    }
}
