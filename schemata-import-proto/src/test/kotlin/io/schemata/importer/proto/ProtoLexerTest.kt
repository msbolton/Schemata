package io.schemata.importer.proto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProtoLexerTest {
    private fun kinds(src: String) = ProtoLexer.lex(src).map { it.kind to it.text }

    @Test
    fun `identifiers numbers strings symbols and comments`() {
        val tokens =
            kinds(
                """syntax = "proto3"; // head
            |message M { int32 a = 0x1F; float b = 1.5e3; bytes c = 007; }
            |/* block
            |   two */ string s = "a\"b\n\x41\101";"""
                    .trimMargin()
            )
        assertEquals(TokenKind.IDENT to "syntax", tokens[0])
        assertEquals(TokenKind.SYMBOL to "=", tokens[1])
        assertEquals(TokenKind.STRING to "proto3", tokens[2])
        assertEquals(TokenKind.SYMBOL to ";", tokens[3])
        assertEquals(TokenKind.COMMENT to " head", tokens[4])
        assertTrue(TokenKind.INT to "0x1F" in tokens)
        assertTrue(TokenKind.FLOAT to "1.5e3" in tokens)
        assertTrue(TokenKind.INT to "007" in tokens)
        assertTrue(TokenKind.COMMENT to " block\n   two " in tokens)
        assertTrue(TokenKind.STRING to "a\"b\nAA" in tokens)
        assertEquals(TokenKind.EOF, ProtoLexer.lex("").single().kind)
    }

    @Test
    fun `positions are one based and a block comment spans lines`() {
        val tokens = ProtoLexer.lex("a\n  /* x\ny */ b")
        assertEquals(Pos(1, 1), tokens[0].pos)
        assertEquals(Pos(2, 3), tokens[1].pos)
        assertEquals(3, tokens[1].endLine)
        assertTrue(tokens[1].block)
        assertEquals(Pos(3, 6), tokens[2].pos)
    }

    @Test
    fun `an unterminated string or comment is a syntax error with its position`() {
        val e = assertFailsWith<ProtoSyntaxError> { ProtoLexer.lex("x = \"abc") }
        assertEquals(Pos(1, 5), e.pos)
        assertFailsWith<ProtoSyntaxError> { ProtoLexer.lex("/* never") }
    }

    @Test
    fun `adjacent string literals concatenate`() {
        assertEquals(listOf(TokenKind.STRING to "ab"), kinds("\"a\" \"b\"").dropLast(1))
    }

    @Test
    fun `adjacent string literals join across lines and later positions follow`() {
        val tokens = ProtoLexer.lex("\"a\"\n  \"b\" c")
        assertEquals(Token(TokenKind.STRING, "ab", Pos(1, 1), 2), tokens[0])
        assertEquals(Pos(2, 7), tokens[1].pos)
    }
}
