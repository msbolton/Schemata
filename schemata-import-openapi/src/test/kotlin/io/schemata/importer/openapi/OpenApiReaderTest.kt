package io.schemata.importer.openapi

import io.schemata.importer.ImportCodes
import io.schemata.importer.ImportInput
import io.schemata.lang.Span
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class OpenApiReaderTest {
    private fun read(path: String, text: String): ReadOutcome =
        OpenApiReader.read(ImportInput(path, text.trimIndent(), relative = path))

    private fun messages(outcome: ReadOutcome): List<String> =
        when (outcome) {
            is ReadOutcome.Read -> outcome.warnings
            is ReadOutcome.Unreadable -> outcome.diagnostics
        }.map { "${it.code.id} ${it.message}" }

    @Test
    fun `a yaml 3_0 document is read with its version`() {
        val outcome =
            read(
                "shop.yaml",
                """
                openapi: 3.0.3
                info:
                  title: Shop
                  version: 1.0.0
                paths: {}
                """,
            )
        val doc = assertIs<ReadOutcome.Read>(outcome).doc
        assertEquals("3.0.3", doc.version)
        assertEquals("Shop", doc.api.info.title)
        assertEquals("shop.yaml", doc.path)
        assertEquals("3.0.3", doc.tree.get("openapi").asText())
        assertEquals(emptyList(), messages(outcome))
    }

    @Test
    fun `a json 3_1 document is read with its version`() {
        val outcome =
            read(
                "shop.json",
                """{"openapi":"3.1.0","info":{"title":"Shop","version":"1.0.0"},"paths":{}}""",
            )
        assertEquals("3.1.0", assertIs<ReadOutcome.Read>(outcome).doc.version)
        assertEquals(emptyList(), messages(outcome))
    }

    @Test
    fun `the document's own messages arrive as warnings`() {
        val outcome =
            read(
                "shop.yaml",
                """
                openapi: 3.0.3
                info:
                  title: Shop
                  version: 1.0.0
                  colour: red
                paths: {}
                whatever: 1
                """,
            )
        val read = assertIs<ReadOutcome.Read>(outcome)
        assertEquals(
            listOf(
                "SCH2405 shop.yaml: attribute info.colour is unexpected; ignored",
                "SCH2405 shop.yaml: attribute whatever is unexpected; ignored",
            ),
            messages(outcome),
        )
        assertEquals(
            "fix the document where the message points; the import ignores that part",
            read.warnings.first().help,
        )
        assertEquals(Span("shop.yaml", 1, 1, 1, 1), read.warnings.first().span)
    }

    @Test
    fun `a swagger 2_0 document is refused with its version`() {
        val outcome =
            read(
                "petstore.yaml",
                """
                swagger: "2.0"
                info:
                  title: Petstore
                  version: "1.0"
                paths: {}
                """,
            )
        val unreadable = assertIs<ReadOutcome.Unreadable>(outcome)
        assertEquals(
            listOf("SCH2401 petstore.yaml: Swagger 2.0 is not read; convert it to OpenAPI 3 first"),
            messages(outcome),
        )
        assertEquals(
            "convert the document with a Swagger 2.0 to OpenAPI 3 converter",
            unreadable.diagnostics.single().help,
        )
    }

    @Test
    fun `yaml that does not parse names the problem and its place`() {
        // Not through read(): its trimIndent would drop the final newline, and the parser stops at
        // the end of the file, which is on line 3 only when that newline is there.
        val outcome =
            OpenApiReader.read(ImportInput("broken.yaml", "openapi: 3.0.3\ninfo: [unclosed\n"))
        val unreadable = assertIs<ReadOutcome.Unreadable>(outcome)
        assertEquals(
            listOf(
                "SCH2401 broken.yaml: cannot parse: expected ',' or ']', but got <stream end> " +
                    "at line 3, column 1"
            ),
            messages(outcome),
        )
        assertEquals(ImportCodes.UNRESOLVED, unreadable.diagnostics.single().code)
        assertEquals(
            "give the importer an OpenAPI 3.0 or 3.1 document",
            unreadable.diagnostics.single().help,
        )
    }

    @Test
    fun `json that does not parse names the problem and its place`() {
        assertEquals(
            listOf(
                "SCH2401 broken.json: cannot parse: Unexpected end-of-input within/between " +
                    "Object entries at line 1, column 22"
            ),
            messages(read("broken.json", """{"openapi": "3.0.3", """)),
        )
    }

    @Test
    fun `a file without an openapi key cannot be parsed`() {
        assertEquals(
            listOf("SCH2401 package.json: cannot parse: no top-level openapi key"),
            messages(read("package.json", """{"name": "shop", "version": "1.0.0"}""")),
        )
        assertEquals(
            listOf("SCH2401 notes.yaml: cannot parse: the top level is not an object"),
            messages(read("notes.yaml", "just a string\n")),
        )
        assertEquals(
            listOf("SCH2401 empty.yaml: cannot parse: the top level is not an object"),
            messages(read("empty.yaml", "")),
        )
    }

    @Test
    fun `a version other than 3_0 or 3_1 cannot be parsed`() {
        assertEquals(
            listOf("SCH2401 next.yaml: cannot parse: OpenAPI 4.0.0 is not 3.0 or 3.1"),
            messages(
                read(
                    "next.yaml",
                    """
                    openapi: 4.0.0
                    info:
                      title: Next
                      version: 1.0.0
                    paths: {}
                    """,
                )
            ),
        )
    }

    @Test
    fun `a referenced file without an openapi key is a fragment of the referring version`() {
        val outcome =
            OpenApiReader.read(
                ImportInput(
                    "common.yaml",
                    "components:\n  schemas:\n    Money:\n      type: string\n",
                ),
                fragmentVersion = "3.1.0",
            )
        val doc = assertIs<ReadOutcome.Read>(outcome).doc
        assertEquals("3.1.0", doc.version)
        assertEquals("string", doc.tree.at("/components/schemas/Money/type").asText())
        assertEquals(emptyList(), messages(outcome))
    }
}
