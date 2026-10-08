package io.schemata.cli

import com.github.ajalt.clikt.testing.test
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class DiffCommandTest {
    @TempDir lateinit var dir: File

    private fun side(name: String, text: String): File {
        val sideDir = File(dir, name).apply { mkdirs() }
        File(sideDir, "s.schemata").writeText(text)
        return sideDir
    }

    @Test
    fun `no changes exits 0`() {
        val schema = "schema s\n\nmodel R { #1 x int32 }\n"
        val old = side("old", schema)
        val new = side("new", schema)
        val r = DiffCommand().test("${old.path} ${new.path}")
        assertEquals(0, r.statusCode, r.stderr)
        assertTrue(r.stderr.contains("no changes"), r.stderr)
    }

    @Test
    fun `a compatible change exits 0 and prints the change line`() {
        val old = side("old", "schema s\n\nmodel R { #1 x int32 }\n")
        val new = side("new", "schema s\n\nmodel R { #1 x int32  #2 y int32? }\n")
        val r = DiffCommand().test("${old.path} ${new.path}")
        assertEquals(0, r.statusCode, r.stderr)
        assertTrue(r.stderr.contains("field 'y' added"), r.stderr)
    }

    @Test
    fun `a note exits 2`() {
        val old = side("old", "schema s\n\nmodel R {\n  #1 x int32\n  reserved #2, \"y\"\n}\n")
        val new = side("new", "schema s\n\nmodel R { #1 x int32 }\n")
        val r = DiffCommand().test("${old.path} ${new.path}")
        assertEquals(2, r.statusCode, r.stderr)
        assertTrue(r.stderr.contains("warning[SCH2502]"), r.stderr)
        assertTrue(r.stderr.contains("1 change"), r.stderr)
        assertTrue(r.stderr.contains("proto: 0 breaking, 1 note"), r.stderr)
        assertTrue(r.stderr.contains("sql: 0 breaking, 0 notes"), r.stderr)
        assertTrue(r.stderr.contains("xsd: 0 breaking, 0 notes"), r.stderr)
        assertTrue(r.stderr.contains("jsonschema: 0 breaking, 0 notes"), r.stderr)
    }

    @Test
    fun `a break exits 1`() {
        val old = side("old", "schema s\n\nmodel R { #1 x int32  #2 y int32 }\n")
        val new = side("new", "schema s\n\nmodel R { #1 x int32 }\n")
        val r = DiffCommand().test("${old.path} ${new.path}")
        assertEquals(1, r.statusCode, r.stderr)
        assertTrue(r.stderr.contains("error[SCH2501]"), r.stderr)
        assertTrue(r.stderr.contains("1 change"), r.stderr)
        assertTrue(r.stderr.contains("proto: 0 breaking, 1 note"), r.stderr)
        assertTrue(r.stderr.contains("sql: 1 breaking, 0 notes"), r.stderr)
        assertTrue(r.stderr.contains("xsd: 1 breaking, 0 notes"), r.stderr)
        assertTrue(r.stderr.contains("jsonschema: 1 breaking, 0 notes"), r.stderr)
    }

    @Test
    fun `strict on a note exits 1`() {
        val old = side("old", "schema s\n\nmodel R {\n  #1 x int32\n  reserved #2, \"y\"\n}\n")
        val new = side("new", "schema s\n\nmodel R { #1 x int32 }\n")
        val r = DiffCommand().test("--strict ${old.path} ${new.path}")
        assertEquals(1, r.statusCode, r.stderr)
        assertTrue(r.stderr.contains("[promoted]"), r.stderr)
    }

    @Test
    fun `target proto exits 0 on a change breaking only on sql`() {
        val old = side("old", "schema s\n\nmodel R { #1 x int32 @sql(column: \"foo\") }\n")
        val new = side("new", "schema s\n\nmodel R { #1 x int32 @sql(column: \"bar\") }\n")
        val r = DiffCommand().test("--target proto ${old.path} ${new.path}")
        assertEquals(0, r.statusCode, r.stderr)
    }

    @Test
    fun `a parse error in new exits 1 with SCH2503`() {
        val old = side("old", "schema s\n\nmodel R { #1 x int32 }\n")
        val new = side("new", "schema s\nmodel R { #1 x }\n")
        val r = DiffCommand().test("${old.path} ${new.path}")
        assertEquals(1, r.statusCode, r.stderr)
        assertTrue(r.stderr.contains("SCH2503"), r.stderr)
    }

    @Test
    fun `format json prints the comparison document`() {
        val old = side("old", "schema s\n\nmodel R { #1 x int32  #2 y int32 }\n")
        val new = side("new", "schema s\n\nmodel R { #1 x int32 }\n")
        val r = DiffCommand().test("--format json ${old.path} ${new.path}")
        assertEquals(1, r.statusCode, r.stderr)
        assertTrue(r.stdout.contains("\"exitCode\": 1"), r.stdout)
        assertTrue(r.stdout.contains("\"verdict\":\"breaking\""), r.stdout)
    }

    @Test
    fun `two sides sharing no namespace exit 1 with SCH2503`() {
        val old = side("old", "schema a\n\nmodel R { #1 x int32 }\n")
        val new = side("new", "schema b\n\nmodel R { #1 x int32 }\n")
        val r = DiffCommand().test("${old.path} ${new.path}")
        assertEquals(1, r.statusCode, r.stderr)
        assertTrue(r.stderr.contains("error[SCH2503]: OLD and NEW share no schema"), r.stderr)
        assertTrue(r.stderr.contains("diff two versions of the same schema set"), r.stderr)
    }

    @Test
    fun `format json on SCH2503 prints an empty diff document with its errors`() {
        val old = side("old", "schema s\n\nmodel R { #1 x int32 }\n")
        val new = side("new", "schema s\nmodel R { #1 x }\n")
        val r = DiffCommand().test("--format json --target proto,sql ${old.path} ${new.path}")
        assertEquals(1, r.statusCode, r.stderr)
        val lines = r.stdout.lines()
        assertEquals("  \"changes\": [],", lines[1], r.stdout)
        assertEquals(
            "  \"summary\": {\"proto\":{\"breaking\":0,\"notes\":0},\"sql\":{\"breaking\":0,\"notes\":0}},",
            lines[2],
            r.stdout,
        )
        assertEquals("  \"exitCode\": 1,", lines[3], r.stdout)
        assertTrue(lines[4].startsWith("  \"errors\": [{\"code\":\"SCH2503\""), r.stdout)
        assertTrue(lines[4].contains("\"message\":\"NEW: the schema set has 1 error\""), r.stdout)
        assertTrue(
            lines[4].contains("\"help\":\"fix the schema with check before diffing\""),
            r.stdout,
        )
        assertTrue(lines[4].contains("\"line\":2"), r.stdout)
    }

    @Test
    fun `target values are trimmed and repeated ones counted once`() {
        val old = side("old", "schema s\n\nmodel R { #1 x int32  #2 y int32 }\n")
        val new = side("new", "schema s\n\nmodel R { #1 x int32 }\n")
        val r = DiffCommand().test(listOf("--target", "sql, proto,sql", old.path, new.path))
        assertEquals(1, r.statusCode, r.stderr)
        assertTrue(r.stderr.contains("sql: breaking, proto: note"), r.stderr)
        assertEquals(1, Regex("^sql: 1 breaking", RegexOption.MULTILINE).findAll(r.stderr).count())
    }

    @Test
    fun `a member annotation change names the member in its line`() {
        val old = side("old", "schema s\n\nmodel R { #1 x int32 { id }  #2 y int32 }\n")
        val new = side("new", "schema s\n\nmodel R { #1 x int32  #2 y int32 { id } }\n")
        val r = DiffCommand().test("${old.path} ${new.path}")
        assertTrue(r.stderr.contains("field 'x': { id } removed"), r.stderr)
        assertTrue(r.stderr.contains("field 'y': { id } added"), r.stderr)
    }

    @Test
    fun `a change to a deprecated member says so in its line`() {
        val old =
            side(
                "old",
                "schema s\n" +
                    "\n" +
                    "model R { #1 x int32  #2 y int32? @deprecated  #3 z int32? @deprecated }\n",
            )
        val new = side("new", "schema s\n\nmodel R { #1 x int32  #2 w int32? @deprecated }\n")
        val r = DiffCommand().test("${old.path} ${new.path}")
        assertTrue(r.stderr.contains("deprecated field 'y' renamed to 'w'"), r.stderr)
        assertTrue(r.stderr.contains("deprecated field 'z' removed"), r.stderr)
    }

    @Test
    fun `a dotted namespace's annotation change groups under its own name`() {
        val old = side("old", "schema shop.orders\n\nmodel R { #1 x int32 }\n")
        val new =
            side(
                "new",
                "schema shop.orders @sql(schema: \"orders_v2\")\n\nmodel R { #1 x int32 }\n",
            )
        val r = DiffCommand().test("${old.path} ${new.path}")
        assertTrue(r.stderr.startsWith("shop.orders\n  @sql(schema) added"), r.stderr)
    }
}
