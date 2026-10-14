package io.schemata.cli

import io.schemata.importer.ImportInput
import io.schemata.importer.ImportResult
import io.schemata.importer.openapi.OpenApiImporter
import io.schemata.lang.Severity
import io.schemata.target.openapi.OpenApiTarget
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * An imported OpenAPI document compiles under the openapi target, and importing that output again
 * gives the same Schemata, so the names the importer makes up for an operation's models survive.
 */
class OpenApiOperationRoundTripTest {
    private fun import(path: String, text: String): ImportResult =
        OpenApiImporter.import(listOf(ImportInput(path, text, relative = path)))

    private fun errors(result: ImportResult): List<String> =
        result.diagnostics
            .filter { it.severity == Severity.ERROR }
            .map { "${it.code.id} ${it.message}" }

    /** [result]'s files compiled under the openapi target, which must report no error. */
    private fun compiled(result: ImportResult): List<ImportInput> {
        val out =
            Pipeline.compile(
                result.files.map { SourceInput(it.path, it.content) },
                listOf(OpenApiTarget),
            )
        assertEquals(
            emptyList(),
            out.diagnostics
                .filter { it.severity == Severity.ERROR }
                .map { "${it.code.id} ${it.message}" },
        )
        return out.files.map { ImportInput(it.file.path, it.file.content, relative = it.file.path) }
    }

    /**
     * Each file of [result] cut where a blank line is followed by an unindented line: the head and
     * the services in order, the other declarations sorted. A second import declares a model the
     * compiled document holds as a component before those it makes for operations, so only their
     * order may differ.
     */
    private fun schemata(result: ImportResult): Map<String, List<String>> =
        result.files.associate { file ->
            val blocks = file.content.split(Regex("\n\n(?=\\S)"))
            val (services, others) =
                blocks.drop(1).partition { it.lines().any { line -> line.startsWith("service ") } }
            file.path to listOf(blocks.first()) + services + others.sorted()
        }

    @Test
    fun `an operation without an id imports the same after a compile`() {
        val first =
            import(
                "shop.yaml",
                """
                openapi: 3.0.3
                info: {title: shop, version: 1.0.0}
                tags: [{name: Orders}, {name: order list}]
                paths:
                  /orders/{id}/lines:
                    get:
                      tags: [Orders]
                      parameters:
                        - {name: id, in: path, required: true, schema: {type: string}}
                      responses:
                        '200':
                          description: Lines
                          content:
                            application/json:
                              schema: {type: array, items: {type: string}}
                        '404':
                          description: Missing
                          content:
                            application/json:
                              schema:
                                type: object
                                properties: {reason: {type: string}}
                                required: [reason]
                                additionalProperties: false
                  /lists/{id}:
                    delete:
                      tags: [order list]
                      operationId: drop list
                      parameters:
                        - {name: id, in: path, required: true, schema: {type: string}}
                      responses: {'204': {description: No content}}
                """
                    .trimIndent(),
            )
        assertEquals(emptyList(), errors(first))
        val second = OpenApiImporter.import(compiled(first))
        assertEquals(emptyList(), errors(second))
        assertEquals(schemata(first), schemata(second))
    }

    @Test
    fun `alias and inner pointer bodies import and compile`() {
        val result =
            import(
                "shop.yaml",
                """
                openapi: 3.0.3
                info: {title: shop, version: 1.0.0}
                paths:
                  /orders:
                    get:
                      operationId: Shop_get
                      responses:
                        '200':
                          description: Order
                          content:
                            application/json:
                              schema: {${'$'}ref: '#/components/schemas/Alias'}
                  /addresses:
                    post:
                      operationId: Shop_create
                      requestBody:
                        content:
                          application/json:
                            schema: {${'$'}ref: '#/components/schemas/Order/properties/address'}
                      responses: {'204': {description: No content}}
                components:
                  schemas:
                    Alias: {${'$'}ref: '#/components/schemas/Order'}
                    Order:
                      type: object
                      properties:
                        address:
                          type: object
                          properties: {city: {type: string}}
                          required: [city]
                          additionalProperties: false
                      required: [address]
                      additionalProperties: false
                """
                    .trimIndent(),
            )
        assertEquals(emptyList(), errors(result))
        assertTrue(result.files.isNotEmpty())
        compiled(result)
    }
}
