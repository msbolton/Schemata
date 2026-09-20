package io.schemata.core

import io.schemata.core.ir.Builtin
import io.schemata.lang.Category
import io.schemata.lang.Parser
import io.schemata.lang.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnalyzerTest {
    private val fixture =
        """
        namespace shop.orders

        record User {
          id:    uuid
          email: string?
          name:  string
          age:   int32
        }
        """
            .trimIndent()

    private fun analyze(source: String): AnalysisResult =
        Analyzer.analyze(Parser.parse(source).file!!)

    @Test
    fun `lowers the fixture to IR with implicit ordinals`() {
        val result = analyze(fixture)
        assertEquals(emptyList(), result.diagnostics)
        val schema = assertNotNull(result.schema)
        assertEquals("shop.orders", schema.namespace)
        val user = schema.records.single()
        assertEquals("User", user.name)
        assertEquals(listOf(1, 2, 3, 4), user.fields.map { it.ordinal })
        assertEquals(
            listOf(Builtin.UUID, Builtin.STRING, Builtin.STRING, Builtin.INT32),
            user.fields.map { it.type },
        )
        assertEquals(listOf(false, true, false, false), user.fields.map { it.nullable })
    }

    @Test
    fun `is deterministic`() {
        assertEquals(analyze(fixture), analyze(fixture))
    }

    @Test
    fun `reports an unknown type with its span`() {
        val result = analyze("namespace a\nrecord R { x: money }")
        assertNull(result.schema)
        val d = result.diagnostics.single()
        assertEquals(Severity.ERROR, d.severity)
        assertEquals(Category.SEMANTIC, d.category)
        assertEquals("unknown type 'money'", d.message)
        assertEquals(2, d.span!!.startLine)
        assertEquals(15, d.span!!.startColumn)
    }

    @Test
    fun `says record references are not supported yet rather than guessing`() {
        val result =
            analyze("namespace a\nrecord Address { city: string }\nrecord User { home: Address }")
        assertNull(result.schema)
        assertEquals(
            "'Address' is a record; record-typed fields are not supported yet",
            result.diagnostics.single().message,
        )
    }

    @Test
    fun `enforces naming conventions`() {
        val result = analyze("namespace a\nrecord user_record { FirstName: string }")
        assertNull(result.schema)
        assertEquals(
            listOf(
                "record name 'user_record' must be UpperCamel",
                "field name 'FirstName' must be lower_snake",
            ),
            result.diagnostics.map { it.message },
        )
    }

    @Test
    fun `rejects duplicate records and fields`() {
        val result = analyze("namespace a\nrecord R { x: bool\n x: bool }\nrecord R { y: bool }")
        assertNull(result.schema)
        assertTrue(
            result.diagnostics.any {
                it.message == "field 'x' is declared more than once in record 'R'"
            }
        )
        assertTrue(result.diagnostics.any { it.message == "record 'R' is declared more than once" })
    }
}
