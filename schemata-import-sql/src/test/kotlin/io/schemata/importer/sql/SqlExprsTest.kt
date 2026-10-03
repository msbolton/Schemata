package io.schemata.importer.sql

import io.schemata.importer.sql.SqlExpr.Between
import io.schemata.importer.sql.SqlExpr.Bin
import io.schemata.importer.sql.SqlExpr.Bool
import io.schemata.importer.sql.SqlExpr.Call
import io.schemata.importer.sql.SqlExpr.Col
import io.schemata.importer.sql.SqlExpr.In
import io.schemata.importer.sql.SqlExpr.IsNull
import io.schemata.importer.sql.SqlExpr.Not
import io.schemata.importer.sql.SqlExpr.Null
import io.schemata.importer.sql.SqlExpr.Num
import io.schemata.importer.sql.SqlExpr.Raw
import io.schemata.importer.sql.SqlExpr.Str
import kotlin.test.Test
import kotlin.test.assertEquals

class SqlExprsTest {
    private fun parse(src: String): SqlExpr {
        val tokens = SqlLexer.lex(src)
        return SqlExprs.parse(tokens, 0, tokens.size - 1)
    }

    @Test
    fun `the shapes an importer recognises`() {
        assertEquals(Bin(">=", Col("c"), Num("0")), parse("\"c\" >= 0"))
        assertEquals(
            Bin("<=", Call("char_length", listOf(Col("c"))), Num("4")),
            parse("char_length(\"c\") <= 4"),
        )
        assertEquals(Bin("~", Col("c"), Str("^a")), parse("\"c\" ~ '^a'"))
        assertEquals(
            In(Col("status"), listOf(Str("a"), Str("b"))),
            parse("\"status\" IN ('a', 'b')"),
        )
        assertEquals(
            In(Col("status"), listOf(Str("a"), Str("b"))),
            parse("(status = ANY (ARRAY['a'::text, 'b'::text]))"),
        )
        assertEquals(
            Bin(
                "or",
                Bin("and", IsNull(Col("a"), false), IsNull(Col("b"), false)),
                Bin("and", IsNull(Col("a"), true), IsNull(Col("b"), true)),
            ),
            parse(
                "((\"a\" IS NULL AND \"b\" IS NULL) OR (\"a\" IS NOT NULL AND \"b\" IS NOT NULL))"
            ),
        )
        assertEquals(
            Bin(
                "or",
                Bin("<>", Col("k"), Str("card")),
                Bin("and", IsNull(Col("x"), true), IsNull(Col("y"), true)),
            ),
            parse("((\"k\" <> 'card') OR (\"x\" IS NOT NULL AND \"y\" IS NOT NULL))"),
        )
        assertEquals(
            Bin("and", Bin(">=", Col("q"), Num("1")), Bin("<=", Col("q"), Num("9"))),
            parse("\"q\" >= 1 AND \"q\" <= 9"),
        )
    }

    @Test
    fun `an unrecognised expression is raw with its text`() {
        assertEquals(Raw("(\"c\" @> '{}')"), parse("(\"c\" @> '{}')"))
        assertEquals(Raw("lower(name) LIKE 'a%'".lowercase()), parse("lower(name) LIKE 'a%'"))
    }

    @Test
    fun `casts parentheses and qualified names vanish`() {
        assertEquals(
            Bin("~*", Col("code"), Str("^[a-z]+$")),
            parse("((t.code)::character varying(8) ~* ('^[a-z]+$'::text))"),
        )
        assertEquals(IsNull(Col("at"), true), parse("(at)::timestamp with time zone IS NOT NULL"))
        assertEquals(
            In(Col("s"), listOf(Str("a"))),
            parse("((s)::text = ANY ((ARRAY['a'::character varying])::text[]))"),
        )
    }

    @Test
    fun `negations between and literals`() {
        assertEquals(Not(In(Col("s"), listOf(Str("x")))), parse("s NOT IN ('x')"))
        assertEquals(Not(In(Col("s"), listOf(Str("x")))), parse("s <> ALL (ARRAY['x'::text])"))
        assertEquals(Not(Bin("=", Col("a"), Bool(true))), parse("NOT a = true"))
        assertEquals(Between(Col("n"), Num("-1"), Num("1.5")), parse("n BETWEEN -1 AND 1.5"))
        assertEquals(Not(Between(Col("n"), Num("0"), Num("1"))), parse("n NOT BETWEEN 0 AND 1"))
        assertEquals(Call("now", emptyList()), parse("now()"))
        assertEquals(Null, parse("NULL"))
        assertEquals(Bool(false), parse("false"))
        assertEquals(Bin("=", Col("x"), Raw("array[1]")), parse("x = ARRAY[1]"))
    }

    @Test
    fun `text keeps the spacing of the source`() {
        val tokens = SqlLexer.lex("CHECK ( \"Q\"  >= 1 AND\n x::text <> 'it''s' )")
        assertEquals("\"Q\" >= 1 and x::text <> 'it''s'", SqlExprs.text(tokens, 2, tokens.size - 2))
    }
}
