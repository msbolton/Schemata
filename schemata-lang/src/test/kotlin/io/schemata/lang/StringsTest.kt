package io.schemata.lang

import io.schemata.lang.internal.Strings
import kotlin.test.Test
import kotlin.test.assertEquals

class StringsTest {
    private fun value(body: String) = Strings.unescape(body).value

    private fun bad(body: String) =
        Strings.unescape(body).bad.map { "${it.offset}:${it.length}:${it.text}" }

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
