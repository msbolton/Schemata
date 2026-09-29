package io.schemata.lang.format

import io.schemata.lang.Parser
import io.schemata.lang.ast.RecordDecl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommentsTest {
    private fun parse(text: String) = Parser.parseForFormat(text, "t.schemata")

    @Test
    fun `plain comments no longer break parsing and are collected`() {
        val p = parse("// top\nnamespace t\n\nrecord R { #1 a: bool /* after a */ }\n")
        assertTrue(p.diagnostics.isEmpty(), p.diagnostics.toString())
        assertEquals(listOf("// top"), p.comments.fileLeading.map { it.text })
        val r = p.file!!.declarations.single() as RecordDecl
        assertEquals(
            listOf("/* after a */"),
            p.comments.trailing[r.fields.single().span]?.map { it.text },
        )
    }

    @Test
    fun `a comment on its own line leads the next element in the same block`() {
        val p =
            parse(
                "namespace t\n\n// about R\nrecord R {\n  // about a\n  #1 a: bool\n  #2 b: bool\n}\n"
            )
        val r = p.file!!.declarations.single() as RecordDecl
        assertEquals(listOf("// about R"), p.comments.leading[r.span]?.map { it.text })
        assertEquals(listOf("// about a"), p.comments.leading[r.fields[0].span]?.map { it.text })
        assertEquals(null, p.comments.leading[r.fields[1].span])
    }

    @Test
    fun `a comment after the last member belongs to the end of the block`() {
        val p = parse("namespace t\n\nrecord R {\n  #1 a: bool\n  // nothing more\n}\n")
        val r = p.file!!.declarations.single() as RecordDecl
        assertEquals(listOf("// nothing more"), p.comments.endOfBlock[r.span]?.map { it.text })
    }

    @Test
    fun `a comment after the last declaration trails the file`() {
        val p = parse("namespace t\n\nrecord R { #1 a: bool }\n// bye\n")
        assertEquals(listOf("// bye"), p.comments.fileTrailing.map { it.text })
    }

    @Test
    fun `a reserved statement can carry a leading comment`() {
        val p = parse("namespace t\n\nrecord R {\n  #1 a: bool\n  // gone\n  reserved #2\n}\n")
        val r = p.file!!.declarations.single() as RecordDecl
        assertEquals(
            listOf("// gone"),
            p.comments.leading[r.reserved.first().span]?.map { it.text },
        )
    }

    @Test
    fun `the compiler path still ignores comments`() {
        val p = Parser.parse("namespace t\n\n// c\nrecord R { #1 a: bool }\n", "t.schemata")
        assertTrue(p.diagnostics.isEmpty())
    }

    @Test
    fun `a comment between two nested records leads the second`() {
        val p =
            parse(
                "namespace t\n\nrecord Outer {\n  record A {\n    #1 x: bool\n  }\n  // between\n  record B {\n    #1 y: bool\n  }\n}\n"
            )
        val outer = p.file!!.declarations.single() as RecordDecl
        val b = outer.nested[1] as RecordDecl
        assertEquals(listOf("// between"), p.comments.leading[b.span]?.map { it.text })
    }

    @Test
    fun `a comment trailing a one-line record does not leak into its last field`() {
        val p = parse("namespace t\n\nrecord R { #1 a: bool } // trailing\n")
        val r = p.file!!.declarations.single() as RecordDecl
        assertEquals(listOf("// trailing"), p.comments.trailing[r.span]?.map { it.text })
        assertEquals(null, p.comments.trailing[r.fields.single().span])
    }
}
