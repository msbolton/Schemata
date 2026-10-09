package io.schemata.target.jsonschema

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EcmaPatternTest {
    private fun bad(p: String) = EcmaPattern.firstUnsupported(p)

    @Test
    fun `common patterns pass`() {
        listOf(
                "^[^@]+@[^@]+$",
                "[a-z]{2,}",
                "(?:ab|cd)+?",
                "(?<year>[0-9]{4})-\\k<year>",
                "(?=a)(?!b)(?<=c)(?<!d)x",
                "\\p{L}\\p{Lu}\\P{Nd}",
                "\\d\\w\\s\\b\\B\\.\\/[\\-]",
                "\\u00e9\\x41\\t\\n\\0",
                "[\\]\\[]",
            )
            .forEach { assertNull(bad(it), it) }
    }

    @Test
    fun `java only constructs are named as written`() {
        assertEquals("*+", bad("a*+"))
        assertEquals("++", bad("a++"))
        assertEquals("?+", bad("a?+"))
        assertEquals("{2,3}+", bad("a{2,3}+"))
        assertEquals("\\A", bad("\\Aabc"))
        assertEquals("\\z", bad("abc\\z"))
        assertEquals("\\Z", bad("abc\\Z"))
        assertEquals("\\G", bad("\\Gx"))
        assertEquals("\\Q", bad("\\Qa.b\\E"))
        assertEquals("\\h", bad("a\\hb"))
        assertEquals("\\R", bad("a\\R"))
        assertEquals("\\X", bad("\\X"))
        assertEquals("\\e", bad("\\e"))
        assertEquals("\\a", bad("\\a"))
        assertEquals("(?i)", bad("(?i)abc"))
        assertEquals("(?i:", bad("(?i:abc)"))
        assertEquals("&&", bad("[a-z&&[^aeiou]]"))
        assertEquals("[", bad("[a-z[A-Z]]"))
        assertEquals("\\p{IsLatin}", bad("\\p{IsLatin}"))
        assertEquals("\\p{Alpha}", bad("\\p{Alpha}"))
        assertEquals("\\p{javaLowerCase}", bad("\\p{javaLowerCase}"))
        assertEquals("\\x{41}", bad("\\x{41}"))
        assertEquals("\\-", bad("a\\-b"))
        assertEquals("\\@", bad("\\@"))
        assertEquals("\\01", bad("\\01"))
        assertEquals("}", bad("a}"))
        assertEquals("]", bad("a]"))
    }

    @Test
    fun `escapes follow the unicode dialect inside and outside a class`() {
        assertNull(bad("\\cJ[\\cj]"))
        assertNull(bad("(a)\\1"))
        assertNull(bad("[a\\-z\\]\\^]"))
        assertEquals("\\c", bad("\\c1"))
        assertEquals("\\@", bad("[\\@]"))
        assertEquals("\\07", bad("[\\07]"))
        assertEquals("\\ ", bad("a\\ b"))
    }

    @Test
    fun `an escaped backslash before a letter is ordinary text`() {
        assertNull(bad("\\\\Aabc"))
    }

    @Test
    fun `a python style named group is named with its ecmascript form`() {
        val text = bad("(?P<year>[0-9]{4})")!!
        assertTrue(text.startsWith("(?P<year>"), text)
        assertTrue(text.contains("Python-style named group"), text)
        assertTrue(text.contains("(?<year>...)"), text)
    }

    @Test
    fun `a word boundary negation and a named backreference inside a class are refused`() {
        assertEquals("\\B", bad("[\\B]"))
        assertEquals("\\k<n>", bad("(?<n>a)[\\k<n>]"))
        assertEquals("\\k<n>", bad("[x\\k<n>]"))
        assertNull(bad("[\\b]"))
        assertNull(bad("\\B(?<n>a)\\k<n>"))
    }
}
