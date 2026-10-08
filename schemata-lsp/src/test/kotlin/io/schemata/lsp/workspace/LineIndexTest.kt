package io.schemata.lsp.workspace

import io.schemata.lang.Span
import kotlin.test.Test
import kotlin.test.assertEquals

class LineIndexTest {
    private fun span(l1: Int, c1: Int, l2: Int, c2: Int) = Span("f", l1, c1, l2, c2)

    @Test
    fun `an ascii span becomes a zero-based range with an exclusive end`() {
        val index = LineIndex("schema a\nmodel R {}\n")
        assertEquals(
            TextRange(TextPosition(1, 6), TextPosition(1, 7)),
            index.range(span(2, 7, 2, 7)),
        )
    }

    @Test
    fun `an astral character counts one compiler column and two editor units`() {
        val index = LineIndex("/// 😀 doc\nmodel R {}")
        // compiler column 7 is the 'd' of doc: "/// " is 4, the emoji 1, the space 1
        assertEquals(TextPosition(0, 7), index.toEditor(1, 7))
        assertEquals(1 to 7, index.toCompiler(TextPosition(0, 7)))
    }

    @Test
    fun `a position inside a surrogate pair maps to the character's column`() {
        val index = LineIndex("😀x")
        assertEquals(1 to 1, index.toCompiler(TextPosition(0, 1)))
        assertEquals(1 to 2, index.toCompiler(TextPosition(0, 2)))
    }

    @Test
    fun `crlf line endings do not shift the following line`() {
        val index = LineIndex("schema a\r\nmodel R {}\r\n")
        assertEquals(TextPosition(1, 0), index.toEditor(2, 1))
        assertEquals("R", index.slice(span(2, 7, 2, 7)))
    }

    @Test
    fun `positions past the end of a line or the file clamp`() {
        val index = LineIndex("ab\ncd")
        assertEquals(1 to 3, index.toCompiler(TextPosition(0, 99)))
        assertEquals(100 to 1, index.toCompiler(TextPosition(99, 5)))
        assertEquals(TextPosition(1, 2), index.end())
    }

    @Test
    fun `a slice across lines keeps the text between the two ends`() {
        val index = LineIndex("model R {\n  #1 a int32\n}")
        assertEquals("a int32", index.slice(span(2, 6, 2, 13)))
        assertEquals("R {\n  #1", index.slice(span(1, 7, 2, 4)))
    }
}
