package io.schemata.lang

import io.schemata.lang.antlr.SchemataLexer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NamesTest {
    @Test
    fun `the keywords are exactly the lexer's literal word tokens`() {
        val vocabulary = SchemataLexer.VOCABULARY
        val words =
            (0..vocabulary.maxTokenType)
                .mapNotNull { vocabulary.getLiteralName(it)?.removeSurrounding("'") }
                .filter { Regex("[A-Za-z_][A-Za-z0-9_]*").matches(it) }
                .toSet()
        assertEquals(words, Names.keywords)
    }

    @Test
    fun `an identifier is a name the lexer reads as one identifier token`() {
        assertTrue(Names.isIdentifier("Customer"))
        assertTrue(Names.isIdentifier("_first_2"))
        assertFalse(Names.isIdentifier("9lives"))
        assertFalse(Names.isIdentifier("has space"))
        assertFalse(Names.isIdentifier(""))
        assertFalse(Names.isIdentifier("model"))
        assertTrue(Names.isIdentifier("record"))
    }
}
