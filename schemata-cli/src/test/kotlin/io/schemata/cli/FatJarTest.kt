package io.schemata.cli

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import org.junit.jupiter.api.Assumptions.assumeTrue

/** The shaded jar runs the command surface end to end with `java -jar`. */
class FatJarTest {
    private val jar = System.getProperty("schemata.fatJar")?.let(::File)
    private val corpus = File("src/test/resources/corpus/worked-example")
    private val java = File(System.getProperty("java.home"), "bin/java")

    private fun run(vararg args: String): Triple<Int, String, String> =
        runProcess(listOf(java.path, "-jar", jar!!.path) + args, 60)

    /**
     * Runs [command] to completion. Both pipes are drained on their own threads, so neither can
     * fill and block the child, and the wait is the only thing on this thread: a child still
     * running after [timeoutSeconds] is killed and the test fails.
     */
    private fun runProcess(
        command: List<String>,
        timeoutSeconds: Long,
        onStart: (Process) -> Unit = {},
    ): Triple<Int, String, String> {
        val process = ProcessBuilder(command).redirectErrorStream(false).start()
        onStart(process)
        var out = ""
        var err = ""
        val drainOut = Thread { out = process.inputStream.bufferedReader().readText() }
        val drainErr = Thread { err = process.errorStream.bufferedReader().readText() }
        drainOut.start()
        drainErr.start()
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            drainOut.join(5000)
            drainErr.join(5000)
            fail("timed out after $timeoutSeconds s: ${command.joinToString(" ")}")
        }
        drainOut.join()
        drainErr.join()
        return Triple(process.exitValue(), out, err)
    }

    @Test
    fun `a child that outlives the limit is killed and the run fails with a timeout`() {
        assumeTrue(!System.getProperty("os.name").lowercase().contains("windows"), "needs sleep")
        var child: Process? = null
        val started = System.nanoTime()
        val failure =
            assertFailsWith<AssertionError> { runProcess(listOf("sleep", "30"), 1) { child = it } }
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertTrue(failure.message!!.startsWith("timed out after 1 s"), failure.message)
        // Once the child is killed its pipes close and the drains return at once; left running
        // it would hold them open for the full join wait.
        assertTrue(elapsedMillis < 5000, "the run took $elapsedMillis ms")
        assertTrue(child!!.waitFor(5, TimeUnit.SECONDS), "the child is still running")
        assertFalse(child!!.isAlive)
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
