package io.schemata.cli

import com.github.ajalt.clikt.testing.test
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class FmtCommandTest {
    @TempDir lateinit var dir: File

    private fun write(name: String, text: String) = File(dir, name).apply { writeText(text) }

    @Test
    fun `fmt rewrites a file in place and names it`() {
        val f = write("a.schemata", "schema t\nmodel R {\n#1 a bool\n}\n")
        val r = FmtCommand().test(listOf(f.path))
        assertEquals(0, r.statusCode, r.stderr)
        assertEquals("formatted ${f.path}\n", r.stdout)
        assertEquals("schema t\n\nmodel R { #1 a bool }\n", f.readText())
    }

    @Test
    fun `fmt leaves a formatted file alone and says nothing`() {
        val f = write("a.schemata", "schema t\n\nmodel R { #1 a bool }\n")
        val r = FmtCommand().test(listOf(f.path))
        assertEquals(0, r.statusCode)
        assertEquals("", r.stdout)
    }

    @Test
    fun `fmt --check prints a diff and exits 1 without writing`() {
        val f = write("a.schemata", "schema t\nmodel R {\n#1 a bool\n}\n")
        val r = FmtCommand().test(listOf("--check", f.path))
        assertEquals(1, r.statusCode)
        assertTrue(r.stdout.startsWith("--- ${f.path}\n+++ ${f.path}\n"), r.stdout)
        assertTrue("-model R {" in r.stdout && "+model R { #1 a bool }" in r.stdout, r.stdout)
        assertTrue(r.stdout.indexOf("-model R {") < r.stdout.indexOf("+model R {"), r.stdout)
        assertEquals("schema t\nmodel R {\n#1 a bool\n}\n", f.readText())
    }

    @Test
    fun `fmt --check exits 0 when nothing would change`() {
        write("a.schemata", "schema t\n\nmodel R { #1 a bool }\n")
        assertEquals(0, FmtCommand().test(listOf("--check", dir.path)).statusCode)
    }

    @Test
    fun `a parse error is reported and the file is untouched`() {
        val f = write("a.schemata", "schema t\nmodel {\n")
        val r = FmtCommand().test(listOf("--color", "never", f.path))
        assertEquals(1, r.statusCode)
        assertTrue("error[SCH0" in r.stderr, r.stderr)
        assertEquals("schema t\nmodel {\n", f.readText())
    }

    @Test
    fun `a parse error in one file does not stop the rest from being formatted`() {
        val a = write("a.schemata", "schema t\nmodel {\n")
        val b = write("b.schemata", "schema t\nmodel R {\n#1 a bool\n}\n")
        val r = FmtCommand().test(listOf("--color", "never", dir.path))
        assertEquals(1, r.statusCode)
        assertTrue("error[SCH0" in r.stderr, r.stderr)
        assertEquals("schema t\nmodel {\n", a.readText())
        assertEquals("schema t\n\nmodel R { #1 a bool }\n", b.readText())
        assertTrue("formatted ${b.path}" in r.stdout, r.stdout)

        write("a.schemata", "schema t\nmodel {\n")
        write("b.schemata", "schema t\nmodel R {\n#1 a bool\n}\n")
        val checked = FmtCommand().test(listOf("--check", "--color", "never", dir.path))
        assertEquals(1, checked.statusCode)
        assertTrue("error[SCH0" in checked.stderr, checked.stderr)
        assertTrue(
            "-model R {" in checked.stdout && "+model R { #1 a bool }" in checked.stdout,
            checked.stdout,
        )
    }

    @Test
    fun `a file that is not valid UTF-8 is reported and left byte for byte`() {
        val bytes =
            "schema t\n// caf".toByteArray() + 0xE9.toByte() + "\nmodel R {\n}\n".toByteArray()
        val f = File(dir, "a.schemata").apply { writeBytes(bytes) }
        val r = FmtCommand().test(listOf(f.path))
        assertEquals(1, r.statusCode)
        assertTrue("${f.path}: not valid UTF-8; left unchanged" in r.stderr, r.stderr)
        assertContentEquals(bytes, f.readBytes())
    }

    @Test
    fun `a CRLF file in canonical layout differs only in line endings and is rewritten to LF`() {
        val f = write("a.schemata", "schema t\r\n\r\nmodel R { #1 a bool }\r\n")
        val checked = FmtCommand().test(listOf("--check", f.path))
        assertEquals(1, checked.statusCode)
        assertEquals("--- ${f.path}\n+++ ${f.path}\n(line endings: CRLF -> LF)\n", checked.stdout)
        val r = FmtCommand().test(listOf(f.path))
        assertEquals(0, r.statusCode, r.stderr)
        assertEquals("schema t\n\nmodel R { #1 a bool }\n", f.readText())
    }

    @Test
    fun `a missing final newline shows in the diff`() {
        val f = write("a.schemata", "schema t\n\nmodel R { #1 a bool }")
        val r = FmtCommand().test(listOf("--check", f.path))
        assertEquals(1, r.statusCode)
        assertTrue(
            "-model R { #1 a bool }\n\\ No newline at end of file\n+model R { #1 a bool }\n" in
                r.stdout,
            r.stdout,
        )
    }

    @Test
    fun `under json the diff goes to stderr and stdout holds only json`() {
        write("a.schemata", "schema t\nmodel {\n")
        write("b.schemata", "schema t\nmodel R {\n#1 a bool\n}\n")
        val r = FmtCommand().test(listOf("--check", "--format", "json", dir.path))
        assertEquals(1, r.statusCode)
        assertTrue(r.stdout.trimStart().startsWith("{"), r.stdout)
        assertFalse("---" in r.stdout || "+model" in r.stdout, r.stdout)
        assertTrue("+model R { #1 a bool }" in r.stderr, r.stderr)
    }

    @Test
    fun `fmt takes no strict option`() {
        val f = write("a.schemata", "schema t\n\nmodel R { #1 a bool }\n")
        assertEquals(1, FmtCommand().test(listOf("--strict", f.path)).statusCode)
    }
}
