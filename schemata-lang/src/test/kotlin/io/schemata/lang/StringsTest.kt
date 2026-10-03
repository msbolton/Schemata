package io.schemata.lang

import io.schemata.lang.internal.BadText
import io.schemata.lang.internal.Strings
import kotlin.test.Test
import kotlin.test.assertEquals

class StringsTest {
    private fun value(body: String) = Strings.unescape(body).value

    private fun bad(body: String) =
        Strings.unescape(body).bad.map { "${it.offset}:${it.length}:${it.text}" }

    private fun reasons(body: String) = Strings.unescape(body).bad.map { it.reason }

    @Test
    fun `the six escapes have their usual meaning`() {
        assertEquals("a\"b", value("a\\\"b"))
        assertEquals("a\\b", value("a\\\\b"))
        assertEquals("a\nb", value("a\\nb"))
        assertEquals("a\tb", value("a\\tb"))
        assertEquals("a\rb", value("a\\rb"))
        assertEquals("A", value("\\u{41}"))
        assertEquals("é", value("\\u{E9}"))
        assertEquals("😀", value("\\u{1F600}"))
        assertEquals(emptyList(), bad("a\\\"\\\\\\n\\t\\r\\u{41}"))
    }

    @Test
    fun `an unknown escape is reported with its position and kept as written`() {
        val result = Strings.unescape("a\\qb")
        assertEquals("a\\qb", result.value)
        assertEquals(listOf("1:2:\\q"), bad("a\\qb"))
        assertEquals(listOf("0:2:\\d"), bad("\\d+"))
    }

    @Test
    fun `a malformed unicode escape is one bad escape`() {
        assertEquals(listOf("0:4:\\u{4"), bad("\\u{4"))
        assertEquals(listOf("0:4:\\u{}"), bad("\\u{}"))
        assertEquals(listOf("0:10:\\u{110000}"), bad("\\u{110000}"))
        assertEquals(listOf("0:8:\\u{D800}"), bad("\\u{D800}"))
        assertEquals(listOf("0:11:\\u{1234567}"), bad("\\u{1234567}"))
        assertEquals(listOf("0:2:\\u"), bad("\\u41"))
    }

    @Test
    fun `a well-formed unicode escape that names no scalar value says so`() {
        assertEquals(listOf(BadText.NotScalar), reasons("\\u{110000}"))
        assertEquals(listOf(BadText.NotScalar), reasons("\\u{D800}"))
        assertEquals(listOf(BadText.UnknownEscape), reasons("\\u{}"))
        assertEquals(listOf(BadText.UnknownEscape), reasons("\\u{1234567}"))
    }

    @Test
    fun `a malformed unicode escape ends within seven characters of its brace`() {
        assertEquals(listOf("0:10:\\u{1234567"), bad("\\u{12345678}"))
        assertEquals(listOf("0:10:\\u{zzzzzzz"), bad("\\u{zzzzzzzzzz}"))
    }

    @Test
    fun `a malformed unicode escape never splits a surrogate pair`() {
        assertEquals(listOf("0:10:\\u{123456😀"), bad("\\u{123456😀x"))
    }

    @Test
    fun `a control character other than tab, newline, and return is reported raw or escaped`() {
        assertEquals(listOf("1:1:\u0001"), bad("a\u0001b"))
        assertEquals(listOf(BadText.Control(1)), reasons("a\u0001b"))
        assertEquals(listOf("0:5:\\u{0}"), bad("\\u{0}"))
        assertEquals(listOf(BadText.Control(0)), reasons("\\u{0}"))
        assertEquals(listOf(BadText.Control(0x1F)), reasons("\\u{1F}"))
        assertEquals(listOf(BadText.Control(0xFFFE)), reasons("\\u{FFFE}"))
        assertEquals(listOf(BadText.Control(0xFFFF)), reasons("\uFFFF"))
    }

    @Test
    fun `tab, newline, and return are allowed raw or escaped`() {
        assertEquals(emptyList(), bad("a\tb\\t\\n\\r\\u{9}\\u{A}\\u{D}\\u{20}"))
        assertEquals("a\tb\t\n\r\t\n\r ", value("a\tb\\t\\n\\r\\u{9}\\u{A}\\u{D}\\u{20}"))
        assertEquals(emptyList(), bad("\u007F\u0085\uFFFD"))
    }

    @Test
    fun `raw controls ignore escapes, so a pattern reports only its raw control characters`() {
        assertEquals(
            listOf("2:1:\u0002"),
            Strings.rawControls("\\d\u0002\\u{0}\t").map { "${it.offset}:${it.length}:${it.text}" },
        )
    }

    @Test
    fun `offsets count code points so an astral character before an escape counts once`() {
        assertEquals(listOf("1:2:\\q"), bad("😀\\q"))
    }

    @Test
    fun `a pattern keeps every backslash and unescapes only the quote`() {
        assertEquals("\\d+", Strings.unquotePattern("\"\\d+\""))
        assertEquals("say \"hi\"", Strings.unquotePattern("\"say \\\"hi\\\"\""))
        assertEquals("\\\\d", Strings.unquotePattern("\"\\\\d\""))
    }
}
