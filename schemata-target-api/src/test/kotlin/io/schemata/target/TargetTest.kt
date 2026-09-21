package io.schemata.target

import io.schemata.core.ir.Schema
import io.schemata.lang.Category
import io.schemata.lang.Diagnostic
import io.schemata.lang.DiagnosticCode
import io.schemata.lang.Severity
import io.schemata.lang.Span
import kotlin.test.Test
import kotlin.test.assertEquals

class TargetTest {
    private val at = Span("t.schemata", 1, 1, 1, 1)

    private data class CountModel(val records: Int) : TargetModel

    private object CountTarget : Target<CountModel> {
        override val name = "count"

        override fun lower(schema: Schema) =
            Lowered(
                CountModel(schema.namespaces.sumOf { it.declarations.size }),
                listOf(
                    Diagnostic(
                        DiagnosticCode("SCH9901", Severity.WARNING, Category.LOSSY),
                        "counted",
                        Span("t.schemata", 1, 1, 1, 1),
                    )
                ),
            )

        override fun render(model: CountModel) =
            listOf(OutputFile("count.txt", "${model.records}\n"))
    }

    private object FailingTarget : Target<CountModel> {
        override val name = "failing"

        override fun lower(schema: Schema) =
            Lowered(
                CountModel(0),
                listOf(
                    Diagnostic(
                        DiagnosticCode("SCH9902", Severity.ERROR, Category.SEMANTIC),
                        "broken",
                        Span("t.schemata", 1, 1, 1, 1),
                    )
                ),
            )

        override fun render(model: CountModel) = listOf(OutputFile("count.txt", "0\n"))
    }

    @Test
    fun `compile runs lower then render and keeps the lowering diagnostics`() {
        val out = CountTarget.compile(Schema(emptyList()))
        assertEquals(listOf(OutputFile("count.txt", "0\n")), out.files)
        assertEquals(listOf("counted"), out.diagnostics.map { it.message })
        assertEquals(at, out.diagnostics.single().span)
    }

    @Test
    fun `compile is callable through a star-projected target`() {
        val target: Target<*> = CountTarget
        assertEquals("count", target.name)
        assertEquals(1, target.compile(Schema(emptyList())).files.size)
    }

    @Test
    fun `compile skips render when lowering reports an error`() {
        val out = FailingTarget.compile(Schema(emptyList()))
        assertEquals(emptyList(), out.files)
        assertEquals(listOf("broken"), out.diagnostics.map { it.message })
    }

    @Test
    fun `a target declares no annotation keys unless it overrides the default`() {
        assertEquals(emptyList(), CountTarget.annotationSpecs)
    }
}
