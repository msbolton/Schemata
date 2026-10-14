package io.schemata.importer.openapi

import io.schemata.importer.ImportInput
import io.schemata.lang.Diagnostic
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RefsTest {
    private val diagnostics = mutableListOf<Diagnostic>()
    private val report = Report(diagnostics)

    private fun doc(path: String, text: String): OpenApiDoc =
        assertIs<ReadOutcome.Read>(OpenApiReader.read(ImportInput(path, text.trimIndent(), path)))
            .doc

    private fun messages() = diagnostics.map { "${it.code.id} ${it.message}" }

    private val shop =
        """
        openapi: 3.1.0
        info:
          title: Shop
          version: 1.0.0
        paths:
          /orders:
            get:
              responses:
                "200":
                  description: ok
        components:
          schemas:
            Order:
              type: object
              properties:
                total:
                  ${'$'}ref: "#/components/schemas/Money"
            Money:
              type: string
        """

    @Test
    fun `a reference inside the document names its component`() {
        val d = doc("api/shop.yaml", shop)
        val refs = Refs(mapOf(d.path to d), { null }, report)
        val target =
            assertNotNull(
                refs.resolve(
                    d,
                    Pointer.ROOT / "components" / "schemas" / "Order" / "properties" / "total",
                    "#/components/schemas/Money",
                )
            )
        assertEquals(d, target.doc)
        assertEquals("#/components/schemas/Money", target.pointer.text)
        assertEquals("schemas", target.section)
        assertEquals("Money", target.name)
        assertEquals("string", target.node.get("type").asText())
        assertEquals(emptyList(), messages())
    }

    @Test
    fun `an escaped pointer below a component names no component`() {
        val d = doc("shop.yaml", shop)
        val target =
            assertNotNull(
                Refs(mapOf(d.path to d), { null }, report)
                    .resolve(d, Pointer.ROOT, "#/paths/~1orders/get")
            )
        assertNull(target.section)
        assertNull(target.name)
        assertEquals(listOf("paths", "/orders", "get"), target.pointer.segments)
    }

    @Test
    fun `a sibling file is located beside the document once and read as a fragment`() {
        val d = doc("api/shop.yaml", shop)
        val located = mutableListOf<String>()
        val refs =
            Refs(
                mapOf(d.path to d),
                { path ->
                    located += path
                    if (path == "common/money.yaml")
                        ImportInput(
                            path,
                            "components:\n  schemas:\n    Money:\n      type: string\n",
                        )
                    else null
                },
                report,
            )
        val at = Pointer.ROOT / "components" / "schemas" / "Order"
        val first =
            assertNotNull(refs.resolve(d, at, "../common/money.yaml#/components/schemas/Money"))
        val again =
            assertNotNull(
                refs.resolve(d, at / "x", "../common/money.yaml#/components/schemas/Money")
            )
        val whole = assertNotNull(refs.resolve(d, at / "y", "../common/money.yaml"))
        assertEquals("common/money.yaml", first.doc.path)
        assertEquals("3.1.0", first.doc.version)
        assertEquals("Money", first.name)
        assertEquals(first.doc, again.doc)
        assertEquals("#", whole.pointer.text)
        assertEquals(listOf("common/money.yaml"), located)
        assertEquals(emptyList(), messages())
    }

    @Test
    fun `a sibling among the inputs is not located`() {
        val d = doc("api/shop.yaml", shop)
        val common = doc("api/common.yaml", shop.replace("Money:", "Price:"))
        val refs = Refs(mapOf(d.path to d, common.path to common), { error("located $it") }, report)
        val target =
            assertNotNull(refs.resolve(d, Pointer.ROOT, "common.yaml#/components/schemas/Price"))
        assertEquals(common, target.doc)
    }

    @Test
    fun `a reference that names nothing is SCH2401 once per place`() {
        val d = doc("shop.yaml", shop)
        val refs = Refs(mapOf(d.path to d), { null }, report)
        val at = Pointer.ROOT / "components" / "schemas" / "Order"
        assertNull(refs.resolve(d, at, "#/components/schemas/Missing"))
        assertNull(refs.resolve(d, at, "#/components/schemas/Missing"))
        assertEquals(
            listOf(
                "SCH2401 shop.yaml: #/components/schemas/Order: reference " +
                    "'#/components/schemas/Missing' names nothing in shop.yaml"
            ),
            messages(),
        )
        assertEquals("fix the reference, or add what it names", diagnostics.single().help)
    }

    @Test
    fun `a missing sibling file is SCH2401`() {
        val d = doc("api/shop.yaml", shop)
        assertNull(
            Refs(mapOf(d.path to d), { null }, report)
                .resolve(d, Pointer.ROOT / "x", "common.yaml#/components/schemas/Money")
        )
        assertEquals(
            listOf(
                "SCH2401 api/shop.yaml: #/x: reference 'common.yaml#/components/schemas/Money' " +
                    "names the file api/common.yaml, which does not exist"
            ),
            messages(),
        )
    }

    @Test
    fun `an unreadable sibling file reports its own error once`() {
        val d = doc("shop.yaml", shop)
        val refs = Refs(mapOf(d.path to d), { ImportInput(it, "a: [b\n") }, report)
        assertNull(refs.resolve(d, Pointer.ROOT / "a", "bad.yaml#/x"))
        assertNull(refs.resolve(d, Pointer.ROOT / "b", "bad.yaml#/y"))
        assertEquals(1, diagnostics.size, messages().toString())
        assertEquals(true, messages().single().startsWith("SCH2401 bad.yaml: cannot parse: "))
    }

    @Test
    fun `a fragment that is not a JSON pointer is SCH2401`() {
        val d = doc("shop.yaml", shop)
        assertNull(
            Refs(mapOf(d.path to d), { null }, report).resolve(d, Pointer.ROOT / "a", "#Money")
        )
        assertEquals(
            listOf("SCH2401 shop.yaml: #/a: reference '#Money' is not a JSON pointer"),
            messages(),
        )
    }

    @Test
    fun `a URL of any scheme and case is refused without locating it`() {
        val d = doc("shop.yaml", shop)
        val refs = Refs(mapOf(d.path to d), { error("located $it") }, report)
        assertNull(refs.resolve(d, Pointer.ROOT / "a", "HTTPS://example.com/common.yaml"))
        assertNull(refs.resolve(d, Pointer.ROOT / "b", "file:///etc/common.yaml#/x"))
        assertEquals(
            listOf(
                "SCH2401 shop.yaml: #/a: reference 'HTTPS://example.com/common.yaml' is a URL " +
                    "and is not fetched",
                "SCH2401 shop.yaml: #/b: reference 'file:///etc/common.yaml#/x' is a URL and " +
                    "is not fetched",
            ),
            messages(),
        )
        assertEquals(
            "copy the referenced file beside the document and refer to it by its relative path",
            diagnostics.first().help,
        )
    }

    @Test
    fun `every reference is checked where it stands but literal values are not searched`() {
        val d =
            doc(
                "shop.yaml",
                """
                openapi: 3.1.0
                info:
                  title: Shop
                  version: 1.0.0
                paths: {}
                components:
                  schemas:
                    Order:
                      type: object
                      properties:
                        example:
                          ${'$'}ref: "#/components/schemas/Gone"
                        note:
                          type: object
                          default:
                            ${'$'}ref: "#/not/a/reference"
                          x-origin:
                            ${'$'}ref: "#/not/a/reference/either"
                """,
            )
        Refs(mapOf(d.path to d), { null }, report).checkAll(d)
        assertEquals(
            listOf(
                "SCH2401 shop.yaml: #/components/schemas/Order/properties/example: reference " +
                    "'#/components/schemas/Gone' names nothing in shop.yaml"
            ),
            messages(),
        )
    }
}
