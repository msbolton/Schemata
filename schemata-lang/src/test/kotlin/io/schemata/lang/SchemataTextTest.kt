package io.schemata.lang

import io.schemata.lang.internal.Strings
import kotlin.test.Test
import kotlin.test.assertEquals

class SchemataTextTest {
    @Test
    fun `a string prints with the six escapes and braced hex for other control characters`() {
        assertEquals("\"plain\"", SchemataText.string("plain"))
        assertEquals("\"q\\\"q b\\\\s\"", SchemataText.string("q\"q b\\s"))
        assertEquals("\"a\\nb\\tc\\rd\"", SchemataText.string("a\nb\tc\rd"))
        assertEquals("\"\\u{0}\\u{1F}\"", SchemataText.string("\u0000\u001F"))
        assertEquals("\"😀 é\"", SchemataText.string("😀 é"))
    }

    @Test
    fun `a printed string reads back as the same value`() {
        listOf("a\nb", "t\tt", "r\rr", "q\"q", "back\\slash", "😀", "\\d{4}").forEach {
            val literal = SchemataText.string(it)
            val read = Strings.unescape(literal.substring(1, literal.length - 1))
            assertEquals(emptyList(), read.bad)
            assertEquals(it, read.value)
        }
    }

    @Test
    fun `a pattern is printed as written with only its quotes escaped`() {
        assertEquals("\"^[A-Z]{2}\\d{4}$\"", SchemataText.pattern("^[A-Z]{2}\\d{4}$"))
        assertEquals("\"say \\\"hi\\\"\"", SchemataText.pattern("say \"hi\""))
        listOf("^\\d+$", "a\"b", "\\\\\"").forEach {
            assertEquals(it, Strings.unquotePattern(SchemataText.pattern(it)))
        }
    }
}
