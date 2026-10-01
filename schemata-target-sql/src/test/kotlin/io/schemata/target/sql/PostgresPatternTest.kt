package io.schemata.target.sql

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PostgresPatternTest {
    private fun bad(p: String) = PostgresPattern.firstUnsupported(p)

    @Test
    fun `are constructs pass`() {
        listOf(
                "^[^@]+@[^@]+$",
                "(?i)abc",
                "(?=a)b",
                "(?<=a)b",
                "a*?b",
                "(a)\\1",
                "\\mword\\M",
                "[[:alpha:]]+",
            )
            .forEach { assertNull(bad(it), it) }
    }

    @Test
    fun `unsupported constructs are named as written`() {
        assertEquals("\\b", bad("\\bword"))
        assertEquals("\\p{L}", bad("\\p{L}"))
        assertEquals("(?i)", bad("a(?i)b"))
        assertEquals("(?<n>", bad("(?<n>a)"))
        assertEquals("++", bad("a++"))
        assertEquals("&&", bad("[a&&b]"))
        assertEquals("\\Q", bad("\\Qa\\E"))
    }

    @Test
    fun `more are constructs pass`() {
        listOf(
                "(?<!a)b",
                "(?!a)b",
                "a+?",
                "a??",
                "(?:ab)+",
                "\\d\\s\\w",
                "\\A\\Z",
                "\\x41",
                "\\u00e9",
                "[a-z]{2,4}",
                "[[.hyphen.]]",
                "[[=a=]]",
            )
            .forEach { assertNull(bad(it), it) }
    }

    @Test
    fun `more unsupported constructs are named as written`() {
        assertEquals("\\B", bad("\\Bword"))
        assertEquals("\\h", bad("a\\h"))
        assertEquals("\\H", bad("a\\H"))
        assertEquals("\\R", bad("a\\R"))
        assertEquals("\\X", bad("\\X"))
        assertEquals("\\G", bad("\\Gx"))
        assertEquals("\\z", bad("abc\\z"))
        assertEquals("\\e", bad("\\e"))
        assertEquals("\\a", bad("\\a"))
        assertEquals("\\k", bad("a\\k<year>"))
        assertEquals("(?P<n>", bad("(?P<n>a)"))
        assertEquals("[", bad("[a[b]]"))
        assertEquals("?+", bad("a?+"))
        assertEquals("}+", bad("a{2,3}+"))
    }
}
