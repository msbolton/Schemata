package io.schemata.cli

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText
import kotlin.test.fail

/**
 * Runs the CLI's `main` in a fresh JVM started in [dir]. A command looks for `schemata.toml` from
 * the working directory upward, and a test cannot change its own JVM's working directory, so this
 * is how a test puts a configuration file where discovery finds it.
 */
internal object SchemataProcess {
    data class Run(val statusCode: Int, val stdout: String, val stderr: String)

    fun run(dir: Path, vararg args: String): Run {
        val logs = Files.createTempDirectory("schemata-process")
        try {
            val stdout = logs.resolve("stdout")
            val stderr = logs.resolve("stderr")
            val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
            val command =
                listOf(
                    java,
                    "-cp",
                    System.getProperty("java.class.path"),
                    "io.schemata.cli.MainKt",
                ) + args
            val process =
                ProcessBuilder(command)
                    .directory(dir.toFile())
                    .redirectOutput(stdout.toFile())
                    .redirectError(stderr.toFile())
                    .start()
            if (!process.waitFor(120, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                fail("timed out after 120 s: ${args.joinToString(" ")}")
            }
            return Run(process.exitValue(), stdout.readText(), stderr.readText())
        } finally {
            logs.toFile().deleteRecursively()
        }
    }
}
