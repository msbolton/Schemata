package io.schemata.lang

import io.schemata.lang.ast.AnnotationArg
import io.schemata.lang.ast.AnnotationValue
import io.schemata.lang.ast.RecordDecl
import kotlin.test.Test
import kotlin.test.assertEquals

class NameSpansTest {
    private val path = "a.schemata"

    private fun parse(source: String) = Parser.parse(source, path).file!!

    private fun span(line: Int, from: Int, to: Int) = Span(path, line, from, line, to)

    @Test
    fun `a type name carries one span per segment even with spaces between them`() {
        val file = parse("schema a\nmodel R { #1 f x . Outer.Inner }")
        val type = (file.declarations.single() as RecordDecl).fields.single().type
        assertEquals("x.Outer.Inner", type.name)
        assertEquals(listOf(span(2, 16, 16), span(2, 20, 24), span(2, 26, 30)), type.nameSegments)
    }

    @Test
    fun `a namespace line carries the span of its name`() {
        val file = parse("schema shop.orders\n")
        assertEquals(span(1, 8, 18), file.namespace.nameSpan)
    }

    @Test
    fun `an import carries the spans of its namespace and its alias`() {
        val file = parse("schema a\nimport shop.customers as cust\nimport b\n")
        val (aliased, plain) = file.imports
        assertEquals(span(2, 8, 21), aliased.namespaceSpan)
        assertEquals(span(2, 26, 29), aliased.aliasSpan)
        assertEquals(span(3, 8, 8), plain.namespaceSpan)
        assertEquals(null, plain.aliasSpan)
    }

    @Test
    fun `an annotation tuple carries one span per name`() {
        val file = parse("schema a\n@sql(unique: (first, second))\nmodel R { }")
        val arg = file.declarations.single().annotations.single().args.single()
        val tuple = (arg as AnnotationArg.Named).value as AnnotationValue.Tuple
        assertEquals(listOf("first", "second"), tuple.names)
        assertEquals(listOf(span(2, 15, 19), span(2, 22, 27)), tuple.nameSpans)
    }

    @Test
    fun `a segment span counts an astral character before it as one column`() {
        val file = parse("schema a\nmodel R { /* 😀 */ #1 f Other }")
        val type = (file.declarations.single() as RecordDecl).fields.single().type
        assertEquals(listOf(span(2, 24, 28)), type.nameSegments)
    }
}
