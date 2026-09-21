package io.schemata.testkit

import java.io.File
import java.nio.file.Files

/**
 * Runs a real `protoc` over rendered output. Uses the binary the build resolved
 * (`-Dschemata.protoc`) and falls back to `protoc` on PATH for ad-hoc runs. The well-known types
 * generated output may import are shipped as resources and offered through a second proto path,
 * exactly as a consumer with the standard includes would see them.
 */
object Protoc {
    private val binary: String =
        System.getProperty("schemata.protoc")?.takeIf { File(it).canExecute() } ?: "protoc"

    private val wellKnownTypes =
        listOf("google/protobuf/timestamp.proto", "google/protobuf/duration.proto")

    /** @return null when protoc accepts every file; otherwise protoc's combined output. */
    fun compile(files: Map<String, String>): String? {
        val dir = Files.createTempDirectory("schemata-protoc").toFile()
        val include = Files.createTempDirectory("schemata-protoc-include").toFile()
        try {
            files.forEach { (path, content) -> write(dir, path, content) }
            wellKnownTypes.forEach { path ->
                val text =
                    Protoc::class.java.getResourceAsStream("/protoc-include/$path")?.use {
                        it.bufferedReader().readText()
                    } ?: error("testkit resource protoc-include/$path is missing")
                write(include, path, text)
            }
            val descriptor = File(dir, "out.desc")
            val command =
                listOf(
                    binary,
                    "--proto_path=${dir.absolutePath}",
                    "--proto_path=${include.absolutePath}",
                    "--descriptor_set_out=${descriptor.absolutePath}",
                ) + files.keys
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            return if (process.waitFor() == 0) null else output
        } finally {
            dir.deleteRecursively()
            include.deleteRecursively()
        }
    }

    private fun write(root: File, path: String, content: String) {
        File(root, path).apply {
            parentFile.mkdirs()
            writeText(content)
        }
    }
}
