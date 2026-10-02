package io.schemata.cli

import io.schemata.lsp.server.SchemataServer
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.jar.JarFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The native image reaches the protocol types only through reflection (Gson reads and writes their
 * fields; lsp4j finds the request methods by annotation), so every class of the protocol jars and
 * of the server package is listed for it. The list is derived from the jars on the classpath and
 * compared with the checked-in file; run with SCHEMATA_GOLDEN_UPDATE=1 to rewrite the file after an
 * lsp4j upgrade or a new server class.
 */
class NativeMetadataTest {
    private val file =
        File(
            "src/main/resources/META-INF/native-image/io.schemata/schemata-cli/reflect-config.json"
        )

    /** Every class that lives where [anchor] was loaded from, under [prefix]. */
    private fun classesBeside(anchor: Class<*>, prefix: String): List<String> {
        val location = Paths.get(anchor.protectionDomain.codeSource.location.toURI())
        val entries: List<String> =
            if (Files.isDirectory(location)) {
                Files.walk(location).use { paths ->
                    paths
                        .filter { Files.isRegularFile(it) }
                        .map { location.relativize(it).toString().replace(File.separatorChar, '/') }
                        .toList()
                }
            } else {
                JarFile(location.toFile()).use { jar ->
                    jar.entries().asSequence().map { it.name }.toList()
                }
            }
        return entries
            .filter { it.endsWith(".class") && !it.endsWith("module-info.class") }
            .map { it.removeSuffix(".class").replace('/', '.') }
            .filter { it.startsWith(prefix) }
    }

    private fun expected(): String {
        val classes =
            (classesBeside(org.eclipse.lsp4j.Position::class.java, "org.eclipse.lsp4j.") +
                    classesBeside(
                        org.eclipse.lsp4j.jsonrpc.Launcher::class.java,
                        "org.eclipse.lsp4j.jsonrpc.",
                    ) +
                    classesBeside(SchemataServer::class.java, "io.schemata.lsp.server."))
                .distinct()
                .sorted()
        return classes.joinToString(",\n", "[\n", "\n]\n") {
            """  {"name": "$it", "allDeclaredConstructors": true, "allDeclaredMethods": true, """ +
                """"allDeclaredFields": true, "allPublicMethods": true}"""
        }
    }

    @Test
    fun `the checked-in reflection metadata lists every protocol and server class`() {
        val actual = expected()
        if (System.getenv("SCHEMATA_GOLDEN_UPDATE") == "1") {
            file.parentFile.mkdirs()
            file.writeText(actual)
            println("metadata: rewrote ${file.path}")
            return
        }
        if (!file.exists()) fail("${file.path} is missing; run with SCHEMATA_GOLDEN_UPDATE=1")
        assertEquals(
            file.readText(),
            actual,
            "${file.path} is stale; run with SCHEMATA_GOLDEN_UPDATE=1 to regenerate it",
        )
    }

    @Test
    fun `the metadata covers the protocol, the transport, and the server`() {
        val text = expected()
        listOf(
                "org.eclipse.lsp4j.PublishDiagnosticsParams",
                "org.eclipse.lsp4j.jsonrpc.messages.RequestMessage",
                "org.eclipse.lsp4j.services.LanguageClient",
                "io.schemata.lsp.server.SchemataServer",
            )
            .forEach { assertTrue("\"name\": \"$it\"" in text, "$it is not listed") }
    }
}
