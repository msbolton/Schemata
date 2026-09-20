package io.schemata.core

import io.schemata.core.ir.Builtin
import io.schemata.lang.Category
import io.schemata.lang.Parser
import io.schemata.lang.Severity
import io.schemata.lang.ast.SourceFile
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

    private fun file(source: String, path: String = "test.schemata"): SourceFile =
        Parser.parse(source, path).file!!

    private fun analyze(source: String): AnalysisResult = Analyzer.analyze(listOf(file(source)))

    @Test
    fun `lowers the fixture to IR with implicit ordinals and spans`() {
        val result = analyze(fixture)
        assertEquals(emptyList(), result.diagnostics)
        val schema = assertNotNull(result.schema)
        val ns = schema.namespaces.single()
        assertEquals("shop.orders", ns.name)
        assertEquals(1, ns.span.startLine)
        val user = ns.records.single()
        assertEquals("User", user.name)
        assertEquals(3, user.span.startLine)
        assertEquals(listOf(1, 2, 3, 4), user.fields.map { it.ordinal })
        assertEquals(
            listOf(Builtin.UUID, Builtin.STRING, Builtin.STRING, Builtin.INT32),
            user.fields.map { it.type },
        )
        assertEquals(listOf(false, true, false, false), user.fields.map { it.nullable })
        assertEquals(listOf(4, 5, 6, 7), user.fields.map { it.span.startLine })
        assertTrue(user.fields.all { it.span.file == "test.schemata" })
    }

    @Test
    fun `is deterministic`() {
        assertEquals(analyze(fixture), analyze(fixture))
    }

    @Test
    fun `merges files that share a namespace in sorted-path order`() {
        val b = file("namespace shop.orders\nrecord Beta { x: bool }", "b.schemata")
        val a = file("namespace shop.orders\nrecord Alpha { x: bool }", "a.schemata")
        val result = Analyzer.analyze(listOf(b, a))
        assertEquals(emptyList(), result.diagnostics)
        val ns = result.schema!!.namespaces.single()
        assertEquals(listOf("Alpha", "Beta"), ns.records.map { it.name })
        assertEquals("a.schemata", ns.span.file)
    }

    @Test
    fun `orders namespaces by name`() {
        val z = file("namespace zoo\nrecord Z { x: bool }", "1.schemata")
        val a = file("namespace apple\nrecord A { x: bool }", "2.schemata")
        val result = Analyzer.analyze(listOf(z, a))
        assertEquals(listOf("apple", "zoo"), result.schema!!.namespaces.map { it.name })
    }

    @Test
    fun `reports a record declared in two files naming both`() {
        val a = file("namespace n\nrecord R { x: bool }", "a.schemata")
        val b = file("namespace n\n\nrecord R { y: bool }", "b.schemata")
        val result = Analyzer.analyze(listOf(a, b))
        assertNull(result.schema)
        val d = result.diagnostics.single()
        assertEquals("record 'R' is declared in both a.schemata:2 and b.schemata:3", d.message)
        assertEquals("b.schemata", d.span.file)
    }

    @Test
    fun `reports an unknown type with its span`() {
        val result = analyze("namespace a\nrecord R { x: money }")
        assertNull(result.schema)
        val d = result.diagnostics.single()
        assertEquals(Severity.ERROR, d.severity)
        assertEquals(Category.SEMANTIC, d.category)
        assertEquals("unknown type 'money'", d.message)
        assertEquals(2, d.span.startLine)
        assertEquals(15, d.span.startColumn)
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
    fun `enforces lower_snake namespace segments`() {
        val result = analyze("namespace Shop.orders")
        assertNull(result.schema)
        val d = result.diagnostics.single()
        assertEquals("namespace segment 'Shop' must be lower_snake", d.message)
        assertEquals(1, d.span.startLine)
    }

    @Test
    fun `rejects duplicate records and fields within one file`() {
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
