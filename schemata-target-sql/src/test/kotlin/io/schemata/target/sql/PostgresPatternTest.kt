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
        assertEquals("(?m)", bad("(?m)a"))
        assertEquals("(?im)", bad("(?im)a"))
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
                "\\Aabc",
                "\\a\\e",
                "(?insx)abc",
                "a{255}",
                "a{1,255}",
                "a{0255}",
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
        assertEquals("\\k", bad("a\\k<year>"))
        assertEquals("(?P<n>", bad("(?P<n>a)"))
        assertEquals("[", bad("[a[b]]"))
        assertEquals("?+", bad("a?+"))
        assertEquals("}+", bad("a{2,3}+"))
    }

    @Test
    fun `escapes whose meaning differs in Postgres are named as written`() {
        assertEquals("\\Z", bad("abc\\Z"))
        assertEquals("\\v", bad("a\\vb"))
        assertEquals("\\V", bad("a\\Vb"))
        assertEquals("\\Z", bad("[\\Z]"))
    }

    @Test
    fun `repetition counts above 255 are named by their braces`() {
        assertEquals("{300}", bad("a{300}"))
        assertEquals("{1,300}", bad("a{1,300}"))
        assertEquals("{256,}", bad("a{256,}"))
        assertEquals("{1,99999999999999999999}", bad("a{1,99999999999999999999}"))
    }

    @Test
    fun `a leading option group with letters outside insx is named as written`() {
        assertEquals("(?u)", bad("(?u)abc"))
        assertEquals("(?d)", bad("(?d)abc"))
        assertEquals("(?iu)", bad("(?iu)abc"))
    }
}
