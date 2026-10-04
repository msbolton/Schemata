package io.schemata.importer.sql

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SqlLexerTest {
    private val d = '$'

    @Test
    fun `identifiers fold strings decode and casts are symbols`() {
        val t =
            SqlLexer.lex(
                    """CREATE TABLE "Shop"."order" (a Text DEFAULT 'it''s'::text, b E'x\ny', c $d${d}dollar$d$d, d ${d}q${d}x${d}q$d);  -- schemata: uuid?"""
                )
                .map { it.kind to it.text }
        assertEquals(SqlTokenKind.IDENT to "create", t[0])
        assertEquals(SqlTokenKind.QIDENT to "Shop", t[2])
        assertEquals(SqlTokenKind.SYMBOL to ".", t[3])
        assertTrue(SqlTokenKind.IDENT to "text" in t)
        assertTrue(SqlTokenKind.STRING to "it's" in t)
        assertTrue(SqlTokenKind.SYMBOL to "::" in t)
        assertTrue(SqlTokenKind.STRING to "x\ny" in t)
        assertTrue(SqlTokenKind.STRING to "dollar" in t)
        assertTrue(SqlTokenKind.STRING to "x" in t)
        assertTrue(SqlTokenKind.COMMENT to " schemata: uuid?" in t)
    }

    @Test
    fun `block comments numbers and operators`() {
        val t =
            SqlLexer.lex("/* c */ x >= -1.5e2 AND y <> 3 OR z ~* 'p' OR w != 'q' OR v ~ 'r'").map {
                it.kind to it.text
            }
        assertEquals(SqlTokenKind.COMMENT to " c ", t[0])
        assertTrue(SqlTokenKind.SYMBOL to ">=" in t)
        assertTrue(SqlTokenKind.NUMBER to "1.5e2" in t)
        assertTrue(SqlTokenKind.SYMBOL to "<>" in t)
        assertTrue(SqlTokenKind.SYMBOL to "~*" in t)
        assertTrue(SqlTokenKind.SYMBOL to "!=" in t)
        assertTrue(SqlTokenKind.SYMBOL to "~" in t)
    }

    @Test
    fun `psql meta commands are comments`() {
        assertEquals(SqlTokenKind.COMMENT, SqlLexer.lex("\\connect db\nSELECT 1;")[0].kind)
    }

    @Test
    fun `an unterminated string reports its position`() {
        assertEquals(
            SqlPos(2, 5),
            assertFailsWith<SqlSyntaxError> { SqlLexer.lex("x;\n  y 'abc") }.pos,
        )
    }

    @Test
    fun `positions are one based and follow multi line tokens`() {
        val t = SqlLexer.lex("a\n  /* x\n /* nested */ y */ b 'p\nq' c")
        assertEquals(SqlPos(1, 1), t[0].pos)
        assertEquals(SqlPos(2, 3), t[1].pos)
        assertEquals(SqlToken(SqlTokenKind.IDENT, "b", SqlPos(3, 20)), t[2])
        assertEquals(SqlToken(SqlTokenKind.STRING, "p\nq", SqlPos(3, 22)), t[3])
        assertEquals(SqlPos(4, 4), t[4].pos)
        assertEquals(SqlTokenKind.EOF, t.last().kind)
    }

    @Test
    fun `copy data from stdin is skipped through its terminator`() {
        val t =
            SqlLexer.lex("COPY t (a) FROM stdin;\n1\tit's\n2\t\\N\n\\.\nSELECT 1;").filter {
                it.kind != SqlTokenKind.COMMENT
            }
        assertEquals(
            listOf("copy", "t", "(", "a", ")", "from", "stdin", ";", "select", "1", ";", ""),
            t.map { it.text },
        )
        assertEquals(SqlPos(5, 1), t[8].pos)
    }

    @Test
    fun `operators other than comparisons lex as symbols and unknown characters fail`() {
        val t = SqlLexer.lex("a @> b || c").map { it.text }
        assertEquals(listOf("a", "@", ">", "b", "|", "|", "c", ""), t)
        assertEquals(SqlPos(1, 3), assertFailsWith<SqlSyntaxError> { SqlLexer.lex("a `b`") }.pos)
    }
}
