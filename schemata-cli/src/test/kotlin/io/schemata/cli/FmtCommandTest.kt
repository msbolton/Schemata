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
        val f = write("a.schemata", "namespace t\nrecord R {\n#1 a: bool\n}\n")
        val r = FmtCommand().test(listOf(f.path))
        assertEquals(0, r.statusCode, r.stderr)
        assertEquals("formatted ${f.path}\n", r.stdout)
        assertEquals("namespace t\n\nrecord R { #1 a: bool }\n", f.readText())
    }

    @Test
    fun `fmt leaves a formatted file alone and says nothing`() {
        val f = write("a.schemata", "namespace t\n\nrecord R { #1 a: bool }\n")
        val r = FmtCommand().test(listOf(f.path))
        assertEquals(0, r.statusCode)
        assertEquals("", r.stdout)
    }

    @Test
    fun `fmt --check prints a diff and exits 1 without writing`() {
        val f = write("a.schemata", "namespace t\nrecord R {\n#1 a: bool\n}\n")
        val r = FmtCommand().test(listOf("--check", f.path))
        assertEquals(1, r.statusCode)
        assertTrue(r.stdout.startsWith("--- ${f.path}\n+++ ${f.path}\n"), r.stdout)
        assertTrue("-record R {" in r.stdout && "+record R { #1 a: bool }" in r.stdout, r.stdout)
        assertTrue(r.stdout.indexOf("-record R {") < r.stdout.indexOf("+record R {"), r.stdout)
        assertEquals("namespace t\nrecord R {\n#1 a: bool\n}\n", f.readText())
    }

    @Test
    fun `fmt --check exits 0 when nothing would change`() {
        write("a.schemata", "namespace t\n\nrecord R { #1 a: bool }\n")
        assertEquals(0, FmtCommand().test(listOf("--check", dir.path)).statusCode)
    }

    @Test
    fun `a parse error is reported and the file is untouched`() {
        val f = write("a.schemata", "namespace t\nrecord {\n")
        val r = FmtCommand().test(listOf("--color", "never", f.path))
        assertEquals(1, r.statusCode)
        assertTrue("error[SCH0" in r.stderr, r.stderr)
        assertEquals("namespace t\nrecord {\n", f.readText())
    }

    @Test
    fun `a parse error in one file does not stop the rest from being formatted`() {
        val a = write("a.schemata", "namespace t\nrecord {\n")
        val b = write("b.schemata", "namespace t\nrecord R {\n#1 a: bool\n}\n")
        val r = FmtCommand().test(listOf("--color", "never", dir.path))
        assertEquals(1, r.statusCode)
        assertTrue("error[SCH0" in r.stderr, r.stderr)
        assertEquals("namespace t\nrecord {\n", a.readText())
        assertEquals("namespace t\n\nrecord R { #1 a: bool }\n", b.readText())
        assertTrue("formatted ${b.path}" in r.stdout, r.stdout)

        write("a.schemata", "namespace t\nrecord {\n")
        write("b.schemata", "namespace t\nrecord R {\n#1 a: bool\n}\n")
        val checked = FmtCommand().test(listOf("--check", "--color", "never", dir.path))
        assertEquals(1, checked.statusCode)
        assertTrue("error[SCH0" in checked.stderr, checked.stderr)
        assertTrue(
            "-record R {" in checked.stdout && "+record R { #1 a: bool }" in checked.stdout,
            checked.stdout,
        )
    }

    @Test
    fun `a file that is not valid UTF-8 is reported and left byte for byte`() {
        val bytes =
            "namespace t\n// caf".toByteArray() + 0xE9.toByte() + "\nrecord R {\n}\n".toByteArray()
        val f = File(dir, "a.schemata").apply { writeBytes(bytes) }
        val r = FmtCommand().test(listOf(f.path))
        assertEquals(1, r.statusCode)
        assertTrue("${f.path}: not valid UTF-8; left unchanged" in r.stderr, r.stderr)
        assertContentEquals(bytes, f.readBytes())
    }

    @Test
    fun `a CRLF file in canonical layout differs only in line endings and is rewritten to LF`() {
        val f = write("a.schemata", "namespace t\r\n\r\nrecord R { #1 a: bool }\r\n")
        val checked = FmtCommand().test(listOf("--check", f.path))
        assertEquals(1, checked.statusCode)
        assertEquals("--- ${f.path}\n+++ ${f.path}\n(line endings: CRLF -> LF)\n", checked.stdout)
        val r = FmtCommand().test(listOf(f.path))
        assertEquals(0, r.statusCode, r.stderr)
        assertEquals("namespace t\n\nrecord R { #1 a: bool }\n", f.readText())
    }

    @Test
    fun `a missing final newline shows in the diff`() {
        val f = write("a.schemata", "namespace t\n\nrecord R { #1 a: bool }")
        val r = FmtCommand().test(listOf("--check", f.path))
        assertEquals(1, r.statusCode)
        assertTrue(
            "-record R { #1 a: bool }\n\\ No newline at end of file\n+record R { #1 a: bool }\n" in
                r.stdout,
            r.stdout,
        )
    }

    @Test
    fun `under json the diff goes to stderr and stdout holds only json`() {
        write("a.schemata", "namespace t\nrecord {\n")
        write("b.schemata", "namespace t\nrecord R {\n#1 a: bool\n}\n")
        val r = FmtCommand().test(listOf("--check", "--format", "json", dir.path))
        assertEquals(1, r.statusCode)
        assertTrue(r.stdout.trimStart().startsWith("{"), r.stdout)
        assertFalse("---" in r.stdout || "+record" in r.stdout, r.stdout)
        assertTrue("+record R { #1 a: bool }" in r.stderr, r.stderr)
    }

    @Test
    fun `fmt takes no strict option`() {
        val f = write("a.schemata", "namespace t\n\nrecord R { #1 a: bool }\n")
        assertEquals(1, FmtCommand().test(listOf("--strict", f.path)).statusCode)
    }
}
