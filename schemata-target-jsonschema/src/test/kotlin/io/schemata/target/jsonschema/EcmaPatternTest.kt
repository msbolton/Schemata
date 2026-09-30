package io.schemata.target.jsonschema

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
                "\\d\\w\\s\\b\\B\\.\\-\\/",
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
    }

    @Test
    fun `an escaped backslash before a letter is ordinary text`() {
        assertNull(bad("\\\\Aabc"))
    }
}
