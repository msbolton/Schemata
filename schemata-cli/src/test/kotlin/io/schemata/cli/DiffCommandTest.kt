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
        val schema = "namespace s\nrecord R { #1 x: int32 }\n"
        val old = side("old", schema)
        val new = side("new", schema)
        val r = DiffCommand().test("${old.path} ${new.path}")
        assertEquals(0, r.statusCode, r.stderr)
        assertTrue(r.stderr.contains("no changes"), r.stderr)
    }

    @Test
    fun `a compatible change exits 0 and prints the change line`() {
        val old = side("old", "namespace s\nrecord R { #1 x: int32 }\n")
        val new = side("new", "namespace s\nrecord R { #1 x: int32  #2 y: int32? }\n")
        val r = DiffCommand().test("${old.path} ${new.path}")
        assertEquals(0, r.statusCode, r.stderr)
        assertTrue(r.stderr.contains("field 'y' added"), r.stderr)
    }

    @Test
    fun `a note exits 2`() {
        val old = side("old", "namespace s\nrecord R { #1 x: int32  reserved #2, \"y\" }\n")
        val new = side("new", "namespace s\nrecord R { #1 x: int32 }\n")
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
        val old = side("old", "namespace s\nrecord R { #1 x: int32  #2 y: int32 }\n")
        val new = side("new", "namespace s\nrecord R { #1 x: int32 }\n")
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
        val old = side("old", "namespace s\nrecord R { #1 x: int32  reserved #2, \"y\" }\n")
        val new = side("new", "namespace s\nrecord R { #1 x: int32 }\n")
        val r = DiffCommand().test("--strict ${old.path} ${new.path}")
        assertEquals(1, r.statusCode, r.stderr)
        assertTrue(r.stderr.contains("[promoted]"), r.stderr)
    }

    @Test
    fun `target proto exits 0 on a change breaking only on sql`() {
        val old = side("old", "namespace s\nrecord R { @sql(column = \"foo\") #1 x: int32 }\n")
        val new = side("new", "namespace s\nrecord R { @sql(column = \"bar\") #1 x: int32 }\n")
        val r = DiffCommand().test("--target proto ${old.path} ${new.path}")
        assertEquals(0, r.statusCode, r.stderr)
    }

    @Test
    fun `a parse error in new exits 1 with SCH2503`() {
        val old = side("old", "namespace s\nrecord R { #1 x: int32 }\n")
        val new = side("new", "namespace s\nrecord R { #1 x: }\n")
        val r = DiffCommand().test("${old.path} ${new.path}")
        assertEquals(1, r.statusCode, r.stderr)
        assertTrue(r.stderr.contains("SCH2503"), r.stderr)
    }

    @Test
    fun `format json prints the comparison document`() {
        val old = side("old", "namespace s\nrecord R { #1 x: int32  #2 y: int32 }\n")
        val new = side("new", "namespace s\nrecord R { #1 x: int32 }\n")
        val r = DiffCommand().test("--format json ${old.path} ${new.path}")
        assertEquals(1, r.statusCode, r.stderr)
        assertTrue(r.stdout.contains("\"exitCode\": 1"), r.stdout)
        assertTrue(r.stdout.contains("\"verdict\":\"breaking\""), r.stdout)
    }
}
