package io.schemata.cli

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/** The shaded jar runs the command surface end to end with `java -jar`. */
class FatJarTest {
    private val jar = System.getProperty("schemata.fatJar")?.let(::File)
    private val corpus = File("src/test/resources/corpus/worked-example")
    private val java = File(System.getProperty("java.home"), "bin/java")

    private fun run(vararg args: String): Triple<Int, String, String> {
        val process =
            ProcessBuilder(listOf(java.path, "-jar", jar!!.path) + args)
                .redirectErrorStream(false)
                .start()
        // Drain stderr on its own thread so a full stderr pipe can't block this
        // thread's stdout read, which would otherwise deadlock before the timeout.
        var err = ""
        val stderrDrain = Thread { err = process.errorStream.bufferedReader().readText() }
        stderrDrain.start()
        val out = process.inputStream.bufferedReader().readText()
        stderrDrain.join()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "timed out")
        return Triple(process.exitValue(), out, err)
    }

    @Test
    fun `check --format json on the worked example exits 2 with one document`() {
        assumeTrue(
            jar != null && jar.isFile,
            "shadow jar not built; run ./gradlew :schemata-cli:shadowJar",
        )
        val (code, out, err) = run("check", "--format", "json", corpus.path)
        assertEquals(2, code, err)
        assertEquals("", err)
        assertTrue(out.startsWith("{\n  \"diagnostics\": ["), out)
        assertTrue(out.contains("\"exitCode\": 2"), out)
    }

    @Test
    fun `--version prints the build version`() {
        assumeTrue(jar != null && jar.isFile)
        val (code, out, _) = run("--version")
        assertEquals(0, code)
        assertTrue(
            Regex(
                    """schemata (\d+\.\d+\.\d+(-rc\.\d+)?(-dev\+[0-9a-f]{7})?(-dirty)?|0\.0\.0-unknown)\n"""
                )
                .matches(out),
            out,
        )
    }
}
