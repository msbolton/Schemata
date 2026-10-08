package io.schemata.cli

import com.github.ajalt.clikt.testing.test
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TargetsCommandTest {
    @Test
    fun `human format lists every target with its keys and codes`() {
        val result = TargetsCommand().test("")
        assertEquals(0, result.statusCode, result.stderr)
        assertTrue(result.stdout.contains("proto\n"), result.stdout)
        assertTrue(result.stdout.contains("sql\n"), result.stdout)
        assertTrue(result.stdout.contains("openapi\n"), result.stdout)
        assertTrue(result.stdout.contains("  SCH2601  error    semantic"), result.stdout)
        assertTrue(result.stdout.contains("  strategy"), result.stdout)
        assertTrue(result.stdout.contains("  SCH2001  warning  lossy"), result.stdout)
        assertTrue(result.stdout.contains("  SCH2110  error    semantic"), result.stdout)
    }

    @Test
    fun `json format is the targets document`() {
        val result = TargetsCommand().test("--format json")
        assertEquals(0, result.statusCode)
        assertTrue(result.stdout.startsWith("{\n  \"targets\": ["), result.stdout)
        assertTrue(result.stdout.contains("\"id\":\"SCH2105\""), result.stdout)
        assertTrue(result.stdout.contains("\"name\":\"openapi\""), result.stdout)
    }
}
