package io.schemata.target

import io.schemata.core.ir.Schema
import io.schemata.lang.Category
import io.schemata.lang.Diagnostic
import io.schemata.lang.Severity
import kotlin.test.Test
import kotlin.test.assertEquals

class TargetTest {
    private data class CountModel(val records: Int) : TargetModel

    private object CountTarget : Target<CountModel> {
        override val name = "count"

        override fun lower(schema: Schema) =
            Lowered(
                CountModel(schema.records.size),
                listOf(Diagnostic(Severity.WARNING, Category.LOSSY, "counted", null)),
            )

        override fun render(model: CountModel) =
            listOf(OutputFile("count.txt", "${model.records}\n"))
    }

    @Test
    fun `compile runs lower then render and keeps the lowering diagnostics`() {
        val out = CountTarget.compile(Schema("a", emptyList()))
        assertEquals(listOf(OutputFile("count.txt", "0\n")), out.files)
        assertEquals(listOf("counted"), out.diagnostics.map { it.message })
    }

    @Test
    fun `compile is callable through a star-projected target`() {
        val target: Target<*> = CountTarget
        assertEquals("count", target.name)
        assertEquals(1, target.compile(Schema("a", emptyList())).files.size)
    }
}
