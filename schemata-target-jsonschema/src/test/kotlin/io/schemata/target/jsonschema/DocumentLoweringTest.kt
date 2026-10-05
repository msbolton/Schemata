package io.schemata.target.jsonschema

import io.schemata.core.AnalysisOptions
import io.schemata.core.Analyzer
import io.schemata.core.annotations.AnnotationRegistry
import io.schemata.core.annotations.CoreAnnotations
import io.schemata.core.ir.RecordType
import io.schemata.core.ir.Schema
import io.schemata.lang.Diagnostic
import io.schemata.lang.Parser
import io.schemata.target.json.JsonNumber
import kotlin.test.Test
import kotlin.test.assertEquals

class DocumentLoweringTest {
    private val codes =
        LoweringCodes(
            JsonSchemaCodes.LOSSY,
            JsonSchemaCodes.NAME_COLLISION,
            JsonSchemaCodes.INVALID_OVERRIDE,
            JsonSchemaCodes.ID_COLLISION,
        )

    private fun compile(text: String): Schema {
        val analysis =
            Analyzer.analyze(
                listOf(Parser.parse(text.trimIndent(), "t.schemata").file!!),
                AnalysisOptions(
                    annotations =
                        AnnotationRegistry(CoreAnnotations.specs + JsonSchemaAnnotations.specs)
                ),
            )
        assertEquals(emptyList(), analysis.diagnostics.map { "${it.code.id} ${it.message}" })
        return analysis.schema!!
    }

    /**
     * A lowering of [schema]'s only namespace that files under `#/components/schemas/<ns>.<key>`.
     */
    private fun componentsLowering(
        schema: Schema,
        diagnostics: MutableList<Diagnostic>,
    ): DocumentLowering {
        val names = SchemaNames(schema, codes, diagnostics)
        return DocumentLowering(
            schema,
            names,
            schema.namespaces.single(),
            codes,
            diagnostics,
            refs = { "#/components/schemas/${it.namespace}.${names.defsKey(it)}" },
            keys = { "${it.namespace}.${names.defsKey(it)}" },
        )
    }

    @Test
    fun `a document lowering can file declarations under another key and ref scheme`() {
        val schema =
            compile(
                """
                namespace a
                record Order { #1 id: uuid #2 line: Line record Line { #1 n: int32 } }
                enum S { #1 x }
                """
            )
        val diagnostics = mutableListOf<Diagnostic>()
        val lowering = componentsLowering(schema, diagnostics)
        val defs = lowering.lower(listOf(schema.namespaces.single().declarations.first()))
        assertEquals(listOf("a.Order", "a.Order.Line"), defs.map { it.key })
        val line =
            (defs[0].schema as ObjectSchema).properties.single { it.name == "line" }.schema
                as RefSchema
        assertEquals("#/components/schemas/a.Order.Line", line.uri)
        assertEquals(emptyList(), diagnostics)
    }

    @Test
    fun `a declaration listed with its parent is lowered once in first-listed order`() {
        val schema =
            compile(
                """
                namespace a
                record Order { #1 id: uuid record Line { #1 n: int32 } }
                enum S { #1 x }
                """
            )
        val diagnostics = mutableListOf<Diagnostic>()
        val lowering = componentsLowering(schema, diagnostics)
        val order = schema.namespaces.single().declarations.first()
        val s = schema.namespaces.single().declarations[1]
        val defs = lowering.lower(listOf(s, order.nested.single(), order))
        assertEquals(listOf("a.S", "a.Order.Line", "a.Order"), defs.map { it.key })
        assertEquals(emptyList(), diagnostics)
    }

    @Test
    fun `partial records and field schemas`() {
        val schema =
            compile(
                """
                namespace a
                enum Status { #1 open #2 closed }
                record Order {
                  #1 id: uuid
                  #2 status: Status?
                  /// The page size.
                  #3 limit: int32 = 50
                }
                """
            )
        val diagnostics = mutableListOf<Diagnostic>()
        val lowering = componentsLowering(schema, diagnostics)
        val order = schema.namespaces.single().declarations.filterIsInstance<RecordType>().single()
        val partial = lowering.partialRecord(order, order.fields.drop(1), "operation 'x'")
        assertEquals(listOf("status", "limit"), partial.properties.map { it.name })
        assertEquals(listOf(false, false), partial.properties.map { it.required })
        assertEquals(
            "#/components/schemas/a.Status",
            (partial.properties[0].schema as RefSchema).uri,
        )
        val limit = lowering.fieldSchema(order, order.fields[2])
        assertEquals(JsonNumber("50"), limit.common.default)
        assertEquals("The page size.", limit.common.description)
        assertEquals(partial.properties[1].schema, limit)
        lowering.lower(schema.namespaces.single().declarations)
        assertEquals(emptyList(), diagnostics)
    }

    @Test
    fun `a field lowered again in a partial record reports its collision once`() {
        val schema =
            compile(
                """
                namespace a
                record Order { #1 a: int32 @jsonschema(name = "a") #2 b: int32 #3 c: int32 }
                """
            )
        val diagnostics = mutableListOf<Diagnostic>()
        val lowering = componentsLowering(schema, diagnostics)
        val order = schema.namespaces.single().declarations.single() as RecordType
        lowering.lower(listOf(order))
        lowering.partialRecord(order, order.fields.take(2), "operation 'x'")
        lowering.fieldSchema(order, order.fields[1])
        lowering.lower(listOf(order))
        assertEquals(listOf("SCH2302"), diagnostics.map { it.code.id })
    }
}
