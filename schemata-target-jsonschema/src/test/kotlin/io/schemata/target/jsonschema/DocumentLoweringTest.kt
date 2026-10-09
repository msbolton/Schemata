package io.schemata.target.jsonschema

import io.schemata.core.AnalysisOptions
import io.schemata.core.Analyzer
import io.schemata.core.annotations.AnnotationRegistry
import io.schemata.core.annotations.CoreAnnotations
import io.schemata.core.ir.Builtin
import io.schemata.core.ir.Namespace
import io.schemata.core.ir.RecordType
import io.schemata.core.ir.Refinements
import io.schemata.core.ir.Scalar
import io.schemata.core.ir.Schema
import io.schemata.lang.Diagnostic
import io.schemata.lang.Parser
import io.schemata.lang.Span
import io.schemata.target.json.JsonNumber
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DocumentLoweringTest {
    private val codes =
        LoweringCodes(
            JsonSchemaCodes.LOSSY,
            JsonSchemaCodes.NAME_COLLISION,
            JsonSchemaCodes.INVALID_OVERRIDE,
        )

    private fun compile(vararg texts: String): Schema {
        val analysis =
            Analyzer.analyze(
                texts.mapIndexed { i, text ->
                    Parser.parse(text.trimIndent(), "t$i.schemata").file!!
                },
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
        namespace: Namespace = schema.namespaces.single(),
    ): DocumentLowering {
        val names = SchemaNames(schema, codes, diagnostics)
        return DocumentLowering(
            schema,
            names,
            namespace,
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
                schema a

                model Order {
                  #1 id   uuid
                  #2 line Line

                  model Line { #1 n int32 }
                }

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
                schema a

                model Order {
                  #1 id uuid

                  model Line { #1 n int32 }
                }

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
                schema a

                enum Status { #1 open #2 closed }

                model Order {
                  #1 id     uuid
                  #2 status Status?
                  /// The page size.
                  #3 limit  int32   = 50
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
                schema a

                model Order { #1 a int32  #2 b int32 @jsonschema(name: "a")  #3 c int32 }
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

    @Test
    fun `the later declaration in the source is blamed whatever order they are lowered in`() {
        val schema =
            compile(
                """
                schema a

                @jsonschema(name: "Y")
                model X { #1 v int32 }

                model Y { #1 v int32 }
                """
            )
        val diagnostics = mutableListOf<Diagnostic>()
        val lowering = componentsLowering(schema, diagnostics)
        val (x, y) = schema.namespaces.single().declarations
        lowering.lower(listOf(y, x))
        assertEquals(
            listOf(
                "SCH2302 model 'Y' lowers to \$defs key 'a.Y', already used by model 'X' (t0.schemata:4)"
            ),
            diagnostics.map { "${it.code.id} ${it.message}" },
        )
    }

    @Test
    fun `a field of another record or namespace is refused`() {
        val schema =
            compile(
                """
                schema a

                model Order { #1 id uuid }

                model Line { #1 n int32 }
                """,
                """
                schema b

                model Other { #1 id uuid }
                """,
            )
        val diagnostics = mutableListOf<Diagnostic>()
        val lowering = componentsLowering(schema, diagnostics, schema.namespaces[0])
        val (order, line) = schema.namespaces[0].declarations.filterIsInstance<RecordType>()
        val other = schema.namespaces[1].declarations.single() as RecordType
        assertFailsWith<IllegalArgumentException> { lowering.fieldSchema(order, line.fields[0]) }
        assertFailsWith<IllegalArgumentException> { lowering.fieldSchema(order, other.fields[0]) }
        lowering.fieldSchema(order, order.fields[0])
        assertEquals(emptyList(), diagnostics)
    }

    @Test
    fun `a construct reported while lowering a type again is reported once`() {
        val schema = compile("schema a\n\nmodel Order { #1 id uuid }")
        val diagnostics = mutableListOf<Diagnostic>()
        val lowering = componentsLowering(schema, diagnostics)
        val lossy = Scalar(Builtin.STRING, Refinements(pattern = "\\Aabc"))
        val span = Span("t0.schemata", 3, 1, 3, 10)
        lowering.typeSchema(lossy, false, "field 'Order.code'", span)
        lowering.typeSchema(lossy, true, "field 'Order.code'", span)
        assertEquals(listOf("SCH2301"), diagnostics.map { it.code.id })
    }
}
