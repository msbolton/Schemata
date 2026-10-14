package io.schemata.importer.openapi

import io.schemata.importer.ImportInput
import io.schemata.importer.ImportResult
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenApiImporterTest {
    private fun importText(vararg files: Pair<String, String>): ImportResult =
        OpenApiImporter.import(
            files.map { ImportInput(it.first, it.second.trimIndent(), relative = it.first) }
        )

    private fun messages(result: ImportResult) =
        result.diagnostics.map { "${it.code.id} ${it.message}" }

    private val shop =
        """
        openapi: 3.0.3
        info:
          title: shop
          version: 1.0.0
          description: The shop's API
        paths: {}
        """

    @Test
    fun `a document is one schema named by its path`() {
        val r = importText("shop.yaml" to shop)
        assertEquals(emptyList(), messages(r))
        assertEquals(listOf("shop.schemata"), r.files.map { it.path })
        assertEquals("/// The shop's API\nschema shop\n", r.files.single().content)
    }

    @Test
    fun `an openapi suffix before the extension is not part of the name`() {
        val r =
            importText(
                "storefront/cart.openapi.json" to
                    """{"openapi":"3.1.0","info":{"title":"storefront.cart","version":"1.0.0"},"paths":{}}"""
            )
        assertEquals(emptyList(), messages(r))
        assertEquals(listOf("storefront/cart.schemata"), r.files.map { it.path })
    }

    @Test
    fun `a file named on its own takes its stem and says so`() {
        val r =
            OpenApiImporter.import(
                listOf(
                    ImportInput(
                        "/tmp/specs/order-book.openapi.yaml",
                        shop.trimIndent().replace("title: shop", "title: order_book"),
                    )
                )
            )
        assertEquals(
            listOf(
                "SCH2402 /tmp/specs/order-book.openapi.yaml: schema name 'order_book' was " +
                    "derived from the file name"
            ),
            messages(r),
        )
        assertEquals(listOf("order_book.schemata"), r.files.map { it.path })
    }

    @Test
    fun `--namespace names the document`() {
        val r =
            OpenApiImporter.import(
                listOf(
                    ImportInput(
                        "/tmp/specs/order-book.yaml",
                        shop.trimIndent().replace("title: shop", "title: store.front"),
                    )
                ),
                namespace = "store.front",
            )
        assertEquals(emptyList(), messages(r))
        assertEquals(listOf("store/front.schemata"), r.files.map { it.path })
    }

    @Test
    fun `two documents with one schema name are SCH2401`() {
        val r =
            importText(
                "shop.yaml" to shop,
                "shop.json" to
                    """{"openapi":"3.1.0","info":{"title":"shop","version":"1.0.0"},"paths":{}}""",
            )
        assertEquals(
            listOf("SCH2401 shop.json: schema name 'shop' is taken by shop.yaml"),
            messages(r),
        )
        assertEquals(emptyList(), r.files)
    }

    @Test
    fun `the document's own messages arrive through the import`() {
        val r =
            importText(
                "shop.yaml" to shop.trimIndent().replace("paths: {}", "paths: {}\nwhatever: 1")
            )
        assertEquals(
            listOf("SCH2405 shop.yaml: attribute whatever is unexpected; ignored"),
            messages(r),
        )
        assertEquals(listOf("shop.schemata"), r.files.map { it.path })
    }

    @Test
    fun `an unreadable document writes nothing`() {
        val r = importText("shop.yaml" to shop, "broken.yaml" to "openapi: 3.0.3\ninfo: [x\n")
        assertTrue(
            messages(r).single().startsWith("SCH2401 broken.yaml: cannot parse: "),
            messages(r).toString(),
        )
        assertEquals(emptyList(), r.files)
    }

    @Test
    fun `a remote reference is refused without a fetch`() {
        // Loading Jackson, snakeyaml and swagger-parser takes a while the first time; it is not
        // what the time limit below is about.
        OpenApiImporter.isDocument("openapi: 3.0.3\n")
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            server.soTimeout = 200
            val listening =
                "http://127.0.0.1:${server.localPort}/common.yaml#/components/schemas/Money"
            val closed = "http://127.0.0.1:9/common.yaml#/components/schemas/Price"
            val text =
                """
                openapi: 3.0.3
                info:
                  title: shop
                  version: 1.0.0
                paths: {}
                components:
                  schemas:
                    Order:
                      type: object
                      properties:
                        money:
                          ${'$'}ref: "$listening"
                        price:
                          ${'$'}ref: "$closed"
                """
            val located = mutableListOf<String>()
            val started = System.nanoTime()
            val r =
                OpenApiImporter.import(
                    listOf(ImportInput("shop.yaml", text.trimIndent(), "shop.yaml"))
                ) {
                    located += it
                    null
                }
            val elapsed = Duration.ofNanos(System.nanoTime() - started)
            assertEquals(
                listOf(
                    "SCH2401 shop.yaml: #/components/schemas/Order/properties/money: reference " +
                        "'$listening' is a URL and is not fetched",
                    "SCH2401 shop.yaml: #/components/schemas/Order/properties/price: reference " +
                        "'$closed' is a URL and is not fetched",
                ),
                messages(r),
            )
            assertEquals(emptyList(), r.files)
            assertEquals(emptyList(), located)
            assertTrue(elapsed < Duration.ofSeconds(1), "the import took $elapsed")
            // Nothing connected to the server that would have answered.
            assertFailsWith<SocketTimeoutException> { server.accept() }
        }
    }

    @Test
    fun `isDocument knows an OpenAPI or Swagger document from other yaml and json`() {
        assertTrue(OpenApiImporter.isDocument("openapi: 3.1.0\ninfo: {}\n"))
        assertTrue(OpenApiImporter.isDocument("""{"openapi": "3.0.3"}"""))
        assertTrue(OpenApiImporter.isDocument("swagger: \"2.0\"\n"))
        assertFalse(OpenApiImporter.isDocument("""{"name": "shop", "version": "1.0.0"}"""))
        assertFalse(OpenApiImporter.isDocument("services:\n  db:\n    image: postgres\n"))
        assertFalse(OpenApiImporter.isDocument("- openapi\n- swagger\n"))
        assertFalse(OpenApiImporter.isDocument("a: [b\n"))
        assertFalse(OpenApiImporter.isDocument(""))
    }
}
