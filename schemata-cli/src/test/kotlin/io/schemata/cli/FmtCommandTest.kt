package io.schemata.cli

import com.github.ajalt.clikt.testing.test
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
