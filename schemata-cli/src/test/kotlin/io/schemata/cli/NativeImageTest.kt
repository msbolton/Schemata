package io.schemata.cli

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * The native binary behaves exactly like the fat jar: same exit code, same stdout and stderr, same
 * files written, for every command the guide shows. Skipped unless the build was given
 * `-Pschemata.nativeBinary=<path>`.
 */
class NativeImageTest {
    private val jar = System.getProperty("schemata.fatJar")?.let(::File)
    private val binary = System.getProperty("schemata.nativeBinary")?.let(::File)
    private val version = System.getProperty("schemata.version")
    private val examples = File("../examples").canonicalFile

    private data class Run(
        val code: Int,
        val out: String,
        val err: String,
        val files: Map<String, String>,
    )

    private fun run(command: List<String>, args: List<String>): Run {
        val dir = Files.createTempDirectory("schemata-parity").toFile()
        val process =
            ProcessBuilder(command + args).directory(dir).redirectErrorStream(false).start()
        var err = ""
        val drain = Thread { err = process.errorStream.bufferedReader().readText() }
        drain.start()
        val out = process.inputStream.bufferedReader().readText()
        drain.join()
        assertTrue(process.waitFor(120, TimeUnit.SECONDS), "timed out: $args")
        val files =
            dir.walkTopDown()
                .filter { it.isFile }
                .associate { it.relativeTo(dir).invariantSeparatorsPath to it.readText() }
        dir.deleteRecursively()
        return Run(process.exitValue(), out, err, files)
    }

    private fun assertSame(vararg args: String) {
        val javaCmd =
            listOf(File(System.getProperty("java.home"), "bin/java").path, "-jar", jar!!.path)
        val fromJar = run(javaCmd, args.toList())
        val fromBinary = run(listOf(binary!!.path), args.toList())
        val what = args.joinToString(" ")
        assertEquals(fromJar.code, fromBinary.code, "exit code for: $what")
        assertEquals(fromJar.out, fromBinary.out, "stdout for: $what")
        assertEquals(fromJar.err, fromBinary.err, "stderr for: $what")
        assertEquals(fromJar.files.keys, fromBinary.files.keys, "files written for: $what")
        fromJar.files.forEach { (path, text) ->
            assertEquals(text, fromBinary.files.getValue(path), "content of $path for: $what")
        }
    }

    private fun ready() {
        assumeTrue(
            binary != null && binary.isFile,
            "no native binary given; pass -Pschemata.nativeBinary=<path>",
        )
        assumeTrue(jar != null && jar.isFile, "shadow jar not built")
    }

    @Test
    fun `--version matches the jar and the build version`() {
        ready()
        assertSame("--version")
        assertEquals("schemata $version\n", run(listOf(binary!!.path), listOf("--version")).out)
    }

    @Test
    fun `targets --format json matches the jar`() {
        ready()
        assertSame("targets", "--format", "json")
    }

    @Test
    fun `check with the human renderer matches the jar`() {
        ready()
        assertSame("check", "--format", "human", "--color", "never", File(examples, "shop").path)
    }

    @Test
    fun `compile to every target matches the jar on each example`() {
        ready()
        listOf("contacts", "shop", "ledger").forEach { name ->
            assertSame(
                "compile",
                "--format",
                "json",
                "--out",
                "out",
                "--target",
                "proto,sql,xsd",
                File(examples, name).path,
            )
        }
    }
}
