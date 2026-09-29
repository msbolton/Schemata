package io.schemata.lang.format

import io.schemata.lang.Parser
import io.schemata.lang.ast.EnumDecl
import io.schemata.lang.ast.RecordDecl
import io.schemata.lang.ast.UnionDecl
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

    @Test
    fun `a comment inside an otherwise empty record belongs to the end of the block`() {
        val p = parse("namespace t\n\nrecord R {\n  // just this\n}\n")
        val r = p.file!!.declarations.single() as RecordDecl
        assertEquals(listOf("// just this"), p.comments.endOfBlock[r.span]?.map { it.text })
        assertEquals(emptyList(), p.comments.fileTrailing)
    }

    @Test
    fun `a comment sharing an annotation's line trails that annotation, not the next member`() {
        val p =
            parse(
                "namespace t\n\nrecord R {\n  @sql(key)  // pk\n  #1 id: int64\n  #2 code: string\n}\n"
            )
        val r = p.file!!.declarations.single() as RecordDecl
        val id = r.fields[0]
        assertEquals(
            listOf("// pk"),
            p.comments.trailing[id.annotations.single().span]?.map { it.text },
        )
        assertEquals(null, p.comments.leading[r.fields[1].span])
        assertEquals(null, p.comments.leading[id.span])
    }

    @Test
    fun `a comment between a declaration's annotation and its keyword leads the declaration`() {
        val p = parse("namespace t\n\n@sql(table = \"x\")\n// note\nrecord R {\n  #1 a: bool\n}\n")
        val r = p.file!!.declarations.single() as RecordDecl
        assertEquals(listOf("// note"), p.comments.leading[r.span]?.map { it.text })
        assertEquals(null, p.comments.endOfBlock[r.span])
        assertEquals(null, p.comments.leading[r.fields.single().span])
    }

    @Test
    fun `a comment between a middle union member's doc and ordinal leads that member`() {
        val p =
            parse(
                "namespace t\n\nunion U = #1 A |\n/// d\n// c\n#2 B |\n#3 C\nrecord A { #1 a: bool }\nrecord B { #1 b: bool }\nrecord C { #1 c: bool }\n"
            )
        val u = p.file!!.declarations[0] as UnionDecl
        assertEquals(listOf("// c"), p.comments.leading[u.members[1].span]?.map { it.text })
        assertEquals(null, p.comments.leading[u.members[2].span])
        assertEquals(null, p.comments.endOfBlock[u.span])
    }

    @Test
    fun `a comment between the last union member's doc and ordinal leads that member`() {
        val p =
            parse(
                "namespace t\n\nunion U = #1 A |\n/// d\n// c\n#2 B\nrecord A { #1 a: bool }\nrecord B { #1 b: bool }\n"
            )
        val u = p.file!!.declarations[0] as UnionDecl
        assertEquals(listOf("// c"), p.comments.leading[u.members[1].span]?.map { it.text })
        assertEquals(null, p.comments.endOfBlock[u.span])
    }

    @Test
    fun `each reserved statement keeps its own trailing comment`() {
        val p =
            parse(
                "namespace t\n\nrecord R {\n  reserved #7 // r1\n  #4 d: bool\n  reserved #8 // r2\n}\n"
            )
        val r = p.file!!.declarations.single() as RecordDecl
        assertEquals(listOf("// r1"), p.comments.trailing[r.reserved[0].span]?.map { it.text })
        assertEquals(listOf("// r2"), p.comments.trailing[r.reserved[1].span]?.map { it.text })
        assertEquals(null, p.comments.leading[r.fields.single().span])
    }

    @Test
    fun `an enum's reserved statement keeps its trailing comment`() {
        val p = parse("namespace t\n\nenum E {\n  #1 a\n  reserved #5 // five\n}\n")
        val e = p.file!!.declarations.single() as EnumDecl
        assertEquals(
            listOf("// five"),
            p.comments.trailing[e.reserved.single().span]?.map { it.text },
        )
        assertEquals(null, p.comments.endOfBlock[e.span])
    }

    @Test
    fun `a comment inside a union member's type trails that member`() {
        val p =
            parse(
                "namespace t\n\nunion U =\n  #1 A |\n  #2 list<\n  // c\n  B>\nrecord A {}\nrecord B {}\n"
            )
        val u = p.file!!.declarations[0] as UnionDecl
        assertEquals(listOf("// c"), p.comments.trailing[u.members[1].span]?.map { it.text })
        assertEquals(null, p.comments.endOfBlock[u.span])
    }

    @Test
    fun `a comment inside a field's type trails that field`() {
        val p =
            parse(
                "namespace t\n\nrecord R {\n  #1 a: bool\n  #2 b: list<\n    // c\n    int32>\n}\n"
            )
        val r = p.file!!.declarations.single() as RecordDecl
        assertEquals(listOf("// c"), p.comments.trailing[r.fields[1].span]?.map { it.text })
        assertEquals(null, p.comments.endOfBlock[r.span])
    }

    @Test
    fun `a second line comment for the same line moves the first one above the element`() {
        val p = parse("namespace t\n\nrecord R {\n  #1 a: list<\n  // c\n  int32> // d\n}\n")
        val a = (p.file!!.declarations.single() as RecordDecl).fields.single()
        assertEquals(listOf("// c"), p.comments.leading[a.span]?.map { it.text })
        assertEquals(listOf("// d"), p.comments.trailing[a.span]?.map { it.text })
    }

    @Test
    fun `a comment on the opening brace's line trails the header`() {
        val p =
            parse("namespace t\n\nrecord R { // c\n  #1 a: bool\n}\nenum E // e\n{\n  #1 v\n}\n")
        val r = p.file!!.declarations[0] as RecordDecl
        val e = p.file!!.declarations[1] as EnumDecl
        assertEquals(listOf("// c"), p.comments.headerTrailing[r.span]?.map { it.text })
        assertEquals(listOf("// e"), p.comments.headerTrailing[e.span]?.map { it.text })
        assertEquals(null, p.comments.leading[r.fields.single().span])
        assertEquals(null, p.comments.leading[e.values.single().span])
    }

    @Test
    fun `a comment after the namespace on its line trails the namespace`() {
        val p = parse("namespace t // c\n\nrecord R {}\n")
        val f = p.file!!
        assertEquals(listOf("// c"), p.comments.trailing[f.namespace.span]?.map { it.text })
        assertEquals(null, p.comments.leading[f.declarations.single().span])
    }

    @Test
    fun `file header comments keep their place around the doc and annotations`() {
        val p = parse("// top\n/// doc\n// between\n@a\n@b // on b\n// before ns\nnamespace t\n")
        val f = p.file!!
        assertEquals(listOf("// top"), p.comments.fileLeading.map { it.text })
        assertEquals(
            listOf("// between"),
            p.comments.leading[f.annotations[0].span]?.map { it.text },
        )
        assertEquals(listOf("// on b"), p.comments.trailing[f.annotations[1].span]?.map { it.text })
        assertEquals(listOf("// before ns"), p.comments.leading[f.namespace.span]?.map { it.text })
    }
}
