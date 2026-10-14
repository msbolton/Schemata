package io.schemata.importer.openapi

import io.schemata.importer.ImportInput
import io.schemata.importer.SchemataUnit
import io.schemata.importer.UnitField
import io.schemata.importer.UnitRecord
import io.schemata.importer.UnitType
import io.schemata.importer.emitUnits
import io.schemata.lang.Diagnostic
import io.swagger.v3.oas.models.media.Schema
import kotlin.test.Test
import kotlin.test.assertEquals

class SchemaLoweringTest {
    private val text =
        """
        openapi: 3.0.3
        info: {title: shop, version: 1.0.0}
        paths:
          /orders:
            get:
              parameters:
                - name: status
                  in: query
                  schema: {type: string, enum: [open, closed]}
                - name: pageSize
                  in: query
                  schema: {type: integer, format: int32, default: 20}
              responses:
                '204': {description: No content}
        components:
          schemas:
            Money:
              type: object
              required: [amount]
              properties:
                amount: {type: integer, format: int64}
              additionalProperties: false
        """
            .trimIndent()

    @Test
    fun `an operation's fields nest their inline shapes in the model it synthesizes`() {
        val doc =
            (OpenApiReader.read(ImportInput("shop.yaml", text, "shop.yaml")) as ReadOutcome.Read)
                .doc
        val diagnostics = mutableListOf<Diagnostic>()
        val report = Report(diagnostics)
        val refs = Refs(mapOf(doc.path to doc), { null }, report)
        val schemas = SchemaLowering(listOf(doc), refs, report) { "shop" }
        val parameters = Pointer("#") / "paths" / "/orders" / "get" / "parameters"
        val status =
            schemas.fieldType(
                doc,
                parameters / 0 / "schema",
                Schema<Any>(),
                "ListOrdersRequest",
                "status",
            )
        val size =
            schemas.fieldType(
                doc,
                parameters / 1 / "schema",
                Schema<Any>(),
                "ListOrdersRequest",
                "page_size",
            )
        assertEquals(UnitType.Ref("ListOrdersRequestStatus"), status.type)
        assertEquals("20", size.default)
        val request =
            UnitRecord(
                "ListOrdersRequest",
                listOf(
                    UnitField(
                        "status",
                        status.type,
                        true,
                        status.default,
                        status.doc,
                        status.annotations,
                    ),
                    UnitField(
                        "page_size",
                        size.type,
                        false,
                        size.default,
                        size.doc,
                        size.annotations,
                    ),
                ),
                emptyList(),
                null,
                emptyList(),
            )
        assertEquals("ListOrdersRequest", schemas.synthesize("shop", "ListOrdersRequest", request))
        val money = refs.resolve(doc, Pointer("#"), "#/components/schemas/Money")!!
        assertEquals("Money", schemas.refName(money, "shop"))
        assertEquals(DeclKind.MODEL, schemas.declarationKind(money))
        val units =
            schemas.declarations().map { (namespace, declarations) ->
                SchemataUnit(
                    namespace,
                    doc = null,
                    imports = schemas.imports(namespace),
                    declarations = declarations,
                    sourcePath = "shop.yaml",
                )
            }
        assertEquals(
            """
            schema shop

            model Money { amount int64 }

            model ListOrdersRequest {
              status    ListOrdersRequestStatus?
              page_size int32                    = 20

              enum ListOrdersRequestStatus { open closed }
            }
            """
                .trimIndent() + "\n",
            emitUnits(units).single().content,
        )
        assertEquals(emptyList(), diagnostics)
    }
}
