package io.schemata.cli

import com.fasterxml.jackson.databind.ObjectMapper
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
 * of the server package is listed for it. The configuration loader loads tomlj's generated lexer
 * and parser by name, so those two are listed as well. The list is derived from the jars on the
 * classpath and compared with the checked-in file; run with SCHEMATA_GOLDEN_UPDATE=1 to rewrite the
 * file after an lsp4j upgrade or a new server class.
 *
 * The OpenAPI importer's reader, swagger-parser on Jackson and snakeyaml, reaches classes by
 * reflection that no classpath walk can predict, so its metadata is traced by GraalVM's agent and
 * kept in a directory of its own, `schemata-import-openapi`, which `scripts/trace-openapi-metadata`
 * rewrites. That file is checked for what can go stale: every class it names must still exist, and
 * it must name nothing outside the reader's libraries, which the rest of the metadata covers.
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
                    classesBeside(SchemataServer::class.java, "io.schemata.lsp.server.") +
                    listOf("org.tomlj.internal.TomlLexer", "org.tomlj.internal.TomlParser"))
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

    private val traced =
        File(
            "src/main/resources/META-INF/native-image/io.schemata/schemata-import-openapi/" +
                "reflect-config.json"
        )

    private val tracedPrefixes =
        listOf("io.swagger.", "com.fasterxml.jackson.", "org.yaml.snakeyaml.")

    /**
     * The class an agent entry names: an array type, written `[Lcom.x.Y;` or `com.x.Y[]`, names its
     * element class.
     */
    private fun elementClass(name: String): String {
        var n = name
        while (n.endsWith("[]")) n = n.removeSuffix("[]")
        n = n.trimStart('[')
        if (n.startsWith("L") && n.endsWith(";")) n = n.substring(1, n.length - 1)
        return n
    }

    /**
     * Every class name the traced reflection metadata lists, from each entry's `name` or `type`.
     */
    private fun tracedClasses(): List<String> {
        if (!traced.isFile) fail("${traced.path} is missing; run scripts/trace-openapi-metadata")
        return ObjectMapper().readTree(traced).map { entry ->
            (entry.get("name") ?: entry.get("type"))?.asText()
                ?: fail("an entry of ${traced.path} names no class: $entry")
        }
    }

    @Test
    fun `the traced openapi metadata names only classes on the classpath`() {
        val names = tracedClasses()
        assertTrue(names.isNotEmpty(), "${traced.path} lists no class")
        val loader = NativeMetadataTest::class.java.classLoader
        val missing =
            names.filter { name ->
                runCatching { Class.forName(elementClass(name), false, loader) }.isFailure
            }
        assertEquals(
            emptyList(),
            missing,
            "${traced.path} names classes that no longer exist; " +
                "run scripts/trace-openapi-metadata after a swagger-parser or Jackson upgrade",
        )
    }

    @Test
    fun `the traced openapi metadata holds only swagger, jackson and snakeyaml entries`() {
        val stray =
            tracedClasses().filterNot { name ->
                tracedPrefixes.any { elementClass(name).startsWith(it) }
            }
        assertEquals(
            emptyList(),
            stray,
            "${traced.path} holds entries outside the reader's libraries; " +
                "regenerate it with scripts/trace-openapi-metadata, which filters them out",
        )
    }
}
