package io.schemata.cli

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The release workflow's formula bump rewrites exactly the version and the three checksums. */
class BumpFormulaTest {
    private val script = File("../scripts/bump-formula").canonicalFile
    private val fixtures = File("src/test/resources/tap")

    private fun run(vararg args: String): Pair<Int, String> {
        val p = ProcessBuilder(listOf(script.path) + args).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        return p.exitValue() to out
    }

    private fun copyOfFormula(): File {
        val f = Files.createTempFile("schemata", ".rb").toFile()
        File(fixtures, "schemata.rb").copyTo(f, overwrite = true)
        return f
    }

    @Test
    fun `rewrites the urls and the three checksums and nothing else`() {
        val f = copyOfFormula()
        val (code, out) = run("0.4.0", File(fixtures, "SHA256SUMS").path, f.path)
        assertEquals(0, code, out)
        assertEquals("changed\n", out)
        assertEquals(File(fixtures, "expected.rb").readText(), f.readText())
    }

    @Test
    fun `a second run is a no-op`() {
        val f = copyOfFormula()
        run("0.4.0", File(fixtures, "SHA256SUMS").path, f.path)
        val (code, out) = run("0.4.0", File(fixtures, "SHA256SUMS").path, f.path)
        assertEquals(0, code, out)
        assertEquals("unchanged\n", out)
    }

    @Test
    fun `a missing checksum line fails`() {
        val f = copyOfFormula()
        val sums = Files.createTempFile("sums", "").toFile()
        sums.writeText(
            File(fixtures, "SHA256SUMS")
                .readLines()
                .filterNot { "macos-x64" in it }
                .joinToString("\n", postfix = "\n")
        )
        val (code, out) = run("0.4.0", sums.path, f.path)
        assertEquals(1, code, out)
        assertTrue("schemata-0.4.0-macos-x64.tar.gz" in out, out)
        assertEquals(File(fixtures, "schemata.rb").readText(), f.readText())
    }

    @Test
    fun `url lines with different versions are refused`() {
        val f = copyOfFormula()
        val edited =
            f.readText()
                .replace("v0.3.0/schemata-0.3.0-macos-x64", "v0.2.0/schemata-0.2.0-macos-x64")
        f.writeText(edited)
        val (code, out) = run("0.4.0", File(fixtures, "SHA256SUMS").path, f.path)
        assertEquals(1, code, out)
        assertTrue("0.2.0" in out || "macos-x64" in out, out)
        assertEquals(edited, f.readText())
    }
}
