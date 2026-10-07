package io.schemata.core

import io.schemata.core.ir.RecordType
import io.schemata.lang.Parser
import kotlin.test.Test
import kotlin.test.assertEquals

class RecursionTest {
    private fun recursive(src: String): Map<String, Boolean> {
        val r = Analyzer.analyze(listOf(Parser.parse(src, "t").file!!))
        assertEquals(emptyList(), r.diagnostics)
        return r.schema!!
            .namespaces
            .single()
            .declarations
            .flatMap { flatten(it) }
            .associate { it.qualifiedName.path.joinToString(".") to it.recursive }
    }

    private fun flatten(d: io.schemata.core.ir.TypeDecl): List<RecordType> =
        (if (d is RecordType) listOf(d) else emptyList()) + d.nested.flatMap { flatten(it) }

    @Test
    fun `self reference, mutual reference, and reference through list or union are recursive`() {
        val src =
            """
            schema a
            model Node { next Node? }
            model A { b B }
            model B { a A? }
            model Tree { children Tree[] }
            model Expr { kids map<string, Expr> }
            model Leaf { v int32 }
            union Item = Leaf | Box
            model Box { items Item[] }
            model Plain { leaf Leaf }
            """
                .trimIndent()
        assertEquals(
            mapOf(
                "Node" to true,
                "A" to true,
                "B" to true,
                "Tree" to true,
                "Expr" to true,
                "Leaf" to false,
                "Box" to true,
                "Plain" to false,
            ),
            recursive(src),
        )
    }

    @Test
    fun `nested records participate`() {
        val src = "schema a\nmodel Outer {\n  inner Inner\n  model Inner { back Outer? }\n}"
        assertEquals(mapOf("Outer" to true, "Outer.Inner" to true), recursive(src))
    }
}
