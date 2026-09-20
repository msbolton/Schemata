package io.schemata.testkit

import java.io.File
import java.nio.file.Files

/**
 * Runs a real `protoc` over rendered output. Uses the binary the build resolved
 * (`-Dschemata.protoc`) and falls back to `protoc` on PATH for ad-hoc runs.
 */
object Protoc {
    private val binary: String =
        System.getProperty("schemata.protoc")?.takeIf { File(it).canExecute() } ?: "protoc"

    /** @return null when protoc accepts every file; otherwise protoc's combined output. */
    fun compile(files: Map<String, String>): String? {
        val dir = Files.createTempDirectory("schemata-protoc").toFile()
        try {
            files.forEach { (path, content) ->
                File(dir, path).apply {
                    parentFile.mkdirs()
                    writeText(content)
                }
            }
            val descriptor = File(dir, "out.desc")
            val command =
                listOf(
                    binary,
                    "--proto_path=${dir.absolutePath}",
                    "--descriptor_set_out=${descriptor.absolutePath}",
                ) + files.keys
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            return if (process.waitFor() == 0) null else output
        } finally {
            dir.deleteRecursively()
        }
    }
}
