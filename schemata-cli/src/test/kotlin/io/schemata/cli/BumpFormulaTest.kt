package io.schemata.cli

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** The release workflow's formula bump rewrites exactly the version and the three checksums. */
class BumpFormulaTest {
    private val script = File("../scripts/bump-formula").canonicalFile
    private val fixtures = File("src/test/resources/tap")

    @TempDir lateinit var dir: File

    private fun run(vararg args: String): Pair<Int, String> {
        val p = ProcessBuilder(listOf(script.path) + args).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        return p.exitValue() to out
    }

    private fun copyOfFixture(name: String): File {
        val f = File(dir, name)
        File(fixtures, name).copyTo(f, overwrite = true)
        return f
    }

    @Test
    fun `rewrites the urls and the three checksums and nothing else`() {
        val f = copyOfFixture("schemata.rb")
        val (code, out) = run("0.4.0", File(fixtures, "SHA256SUMS").path, f.path)
        assertEquals(0, code, out)
        assertEquals("changed\n", out)
        assertEquals(File(fixtures, "expected.rb").readText(), f.readText())
    }

    @Test
    fun `a second run is a no-op`() {
        val f = copyOfFixture("schemata.rb")
        run("0.4.0", File(fixtures, "SHA256SUMS").path, f.path)
        val (code, out) = run("0.4.0", File(fixtures, "SHA256SUMS").path, f.path)
        assertEquals(0, code, out)
        assertEquals("unchanged\n", out)
    }

    @Test
    fun `a missing checksum line fails`() {
        val f = copyOfFixture("schemata.rb")
        val sums = File(dir, "SHA256SUMS")
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
        val f = copyOfFixture("schemata.rb")
        val edited =
            f.readText()
                .replace("v0.3.0/schemata-0.3.0-macos-x64", "v0.2.0/schemata-0.2.0-macos-x64")
        f.writeText(edited)
        val (code, out) = run("0.4.0", File(fixtures, "SHA256SUMS").path, f.path)
        assertEquals(1, code, out)
        assertTrue("0.2.0" in out || "macos-x64" in out, out)
        assertEquals(edited, f.readText())
    }

    @Test
    fun `checksums follow the platform in each url, not the line order`() {
        val f = copyOfFixture("reordered.rb")
        val (code, out) = run("0.4.0", File(fixtures, "SHA256SUMS").path, f.path)
        assertEquals(0, code, out)
        assertEquals("changed\n", out)
        val lines = f.readLines()
        val linuxUrl = lines.indexOfFirst { "linux-x64" in it && it.trimStart().startsWith("url ") }
        val armUrl = lines.indexOfFirst { "macos-arm64" in it && it.trimStart().startsWith("url ") }
        val x64Url = lines.indexOfFirst { "macos-x64" in it && it.trimStart().startsWith("url ") }
        assertEquals(
            "      sha256 \"1111111111111111111111111111111111111111111111111111111111111111\"",
            lines[linuxUrl + 1],
        )
        assertEquals(
            "      sha256 \"2222222222222222222222222222222222222222222222222222222222222222\"",
            lines[armUrl + 1],
        )
        assertEquals(
            "      sha256 \"3333333333333333333333333333333333333333333333333333333333333333\"",
            lines[x64Url + 1],
        )
    }
}
