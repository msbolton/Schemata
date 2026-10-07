package io.schemata.cli

import com.github.ajalt.clikt.testing.test
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class UpgradeCommandTest {
    @TempDir lateinit var dir: File

    private fun write(name: String, text: String) = File(dir, name).apply { writeText(text) }

    private val v1 = "namespace t\nrecord R {\n@sql(key) #1 a: uuid\n#2 b: string(max = 5)\n}\n"
    private val v2 = "schema t\n\nmodel R { #1 a uuid { id }  #2 b string { max 5 } }\n"

    @Test
    fun `upgrade rewrites a 1 file in place and names it`() {
        val f = write("a.schemata", v1)
        val r = UpgradeCommand().test(listOf(f.path))
        assertEquals(0, r.statusCode, r.stderr)
        assertEquals("upgraded ${f.path}\n", r.stdout)
        assertEquals(v2, f.readText())
    }

    @Test
    fun `upgrade --check prints a diff and exits 1 without writing`() {
        val f = write("a.schemata", v1)
        val r = UpgradeCommand().test(listOf("--check", f.path))
        assertEquals(1, r.statusCode)
        assertTrue(r.stdout.startsWith("--- ${f.path}\n+++ ${f.path}\n"), r.stdout)
        assertTrue("-namespace t" in r.stdout && "+schema t" in r.stdout, r.stdout)
        assertEquals(v1, f.readText())
    }

    @Test
    fun `a 2 file is left untouched and not named`() {
        val text = "schema t\nmodel R {   a int32 }\n"
        val f = write("a.schemata", text)
        val r = UpgradeCommand().test(listOf(f.path))
        assertEquals(0, r.statusCode, r.stderr)
        assertEquals("", r.stdout)
        assertEquals(text, f.readText())
        assertEquals(0, UpgradeCommand().test(listOf("--check", f.path)).statusCode)
    }

    @Test
    fun `a file that is neither fails with the parse diagnostic and the rest still upgrade`() {
        val bad = write("a.schemata", "namespace t\nrecord {\n")
        val good = write("b.schemata", v1)
        val r = UpgradeCommand().test(listOf("--color", "never", dir.path))
        assertEquals(1, r.statusCode)
        assertTrue("error[SCH0001]" in r.stderr, r.stderr)
        assertEquals("namespace t\nrecord {\n", bad.readText())
        assertEquals(v2, good.readText())
        assertTrue("upgraded ${good.path}" in r.stdout, r.stdout)
    }

    @Test
    fun `a 1 name that is a 2 keyword is renamed with its sql name kept`() {
        val f = write("a.schemata", "namespace t\nrecord R { model: string }\n")
        val r = UpgradeCommand().test(listOf(f.path))
        assertEquals(0, r.statusCode, r.stderr)
        assertEquals(
            "schema t\n\nmodel R { model_ string @sql(column: \"model\") }\n",
            f.readText(),
        )
    }

    @Test
    fun `under json the diff goes to stderr and stdout holds only json`() {
        write("a.schemata", "namespace t\nrecord {\n")
        write("b.schemata", v1)
        val r = UpgradeCommand().test(listOf("--check", "--format", "json", dir.path))
        assertEquals(1, r.statusCode)
        assertTrue(r.stdout.trimStart().startsWith("{"), r.stdout)
        assertFalse("+schema t" in r.stdout, r.stdout)
        assertTrue("+schema t" in r.stderr, r.stderr)
    }
}
