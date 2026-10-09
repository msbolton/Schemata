package io.schemata.importer.sql

import java.util.IdentityHashMap

/**
 * Expressions over a slice of tokens, `[from, until)`, comments skipped. The grammar, loosest
 * binding first:
 * ```
 * or      := and (OR and)*
 * and     := not (AND not)*
 * not     := NOT not | cmp
 * cmp     := primary ( (= | <> | != | < | > | <= | >=) (ANY|ALL '(' or ')' | primary)
 *                    | IS [NOT] NULL | [NOT] IN '(' list ')' | [NOT] BETWEEN primary AND primary
 *                    | (~ | ~*) primary )?
 * primary := '(' or ')' | literal | ARRAY '[' list ']' | name '(' list ')' | name ('.' name)*
 * ```
 *
 * An unquoted `CURRENT_TIMESTAMP`, `CURRENT_DATE`, `CURRENT_TIME`, `LOCALTIME`, `LOCALTIMESTAMP`,
 * `CURRENT_USER`, `SESSION_USER`, `CURRENT_ROLE` or `CURRENT_CATALOG` is a call with no arguments.
 * A `::type` after any primary is skipped, so casts vanish, as do redundant parentheses. `= ANY
 * (ARRAY[…])` reads as [SqlExpr.In] and `<> ALL (ARRAY[…])` as its negation, the forms pg_dump
 * writes for `IN` and `NOT IN`; an `ARRAY[…]` anywhere else is [SqlExpr.Raw]. A slice outside the
 * grammar is [SqlExpr.Raw] with the slice's [text].
 */
object SqlExprs {
    fun parse(tokens: List<SqlToken>, from: Int, until: Int): SqlExpr {
        val code = tokens.subList(from, until).filter { it.kind != SqlTokenKind.COMMENT }
        return try {
            Parser(code).run {
                val e = or()
                if (i != code.size) fail()
                e
            }
        } catch (_: NoMatch) {
            SqlExpr.Raw(text(tokens, from, until))
        }
    }

    /**
     * The slice as source text: each token as it would be written (strings and quoted identifiers
     * re-quoted, identifiers in lower case), with one space wherever the source had any space or
     * line break between two tokens.
     */
    fun text(tokens: List<SqlToken>, from: Int, until: Int): String = buildString {
        var prev: SqlToken? = null
        for (t in tokens.subList(from, until)) {
            if (t.kind == SqlTokenKind.COMMENT || t.kind == SqlTokenKind.EOF) continue
            val written = written(t)
            prev?.let { p ->
                // Adjacent when the source ran one straight into the other, whatever the length
                // of the first token's source (an `E'…'` string is longer than its re-quoted text).
                val adjacent = p.end == t.pos
                if (!adjacent) append(' ')
            }
            append(written)
            prev = t
        }
    }

    private fun written(t: SqlToken): String =
        when (t.kind) {
            SqlTokenKind.STRING -> "'" + t.text.replace("'", "''") + "'"
            SqlTokenKind.QIDENT -> "\"" + t.text.replace("\"", "\"\"") + "\""
            else -> t.text
        }

    private object NoMatch : RuntimeException()

    private val COMPARISONS = setOf("=", "<>", "!=", "<", ">", "<=", ">=")

    /** Words that cannot be a column, so a slice using them some other way is raw. */
    private val RESERVED =
        setOf(
            "and",
            "or",
            "not",
            "is",
            "in",
            "between",
            "any",
            "all",
            "some",
            "array",
            "case",
            "when",
            "then",
            "else",
            "end",
            "like",
            "ilike",
            "similar",
            "select",
            "exists",
            "distinct",
            "from",
            "collate",
        )

    /** Functions SQL calls without parentheses; written unquoted they are calls, never columns. */
    private val VALUE_FUNCTIONS =
        setOf(
            "current_timestamp",
            "current_date",
            "current_time",
            "localtime",
            "localtimestamp",
            "current_user",
            "session_user",
            "current_role",
            "current_catalog",
        )

    /** Words that continue a cast's type name: `character varying`, `timestamp with time zone`. */
    private val TYPE_CONTINUATION = setOf("varying", "precision", "with", "without", "time", "zone")

    private class Parser(val ts: List<SqlToken>) {
        var i = 0
        /** Each `ARRAY[…]` read so far, by identity, with its items. */
        val arrays = IdentityHashMap<SqlExpr, List<SqlExpr>>()

        fun fail(): Nothing = throw NoMatch

        fun peek(k: Int = 0): SqlToken? = ts.getOrNull(i + k)

        fun isWord(word: String, k: Int = 0) =
            peek(k)?.let { it.kind == SqlTokenKind.IDENT && it.text == word } == true

        fun isSymbol(symbol: String, k: Int = 0) =
            peek(k)?.let { it.kind == SqlTokenKind.SYMBOL && it.text == symbol } == true

        fun word(word: String): Boolean = isWord(word).also { if (it) i++ }

        fun symbol(symbol: String): Boolean = isSymbol(symbol).also { if (it) i++ }

        fun expect(symbol: String) {
            if (!symbol(symbol)) fail()
        }

        fun or(): SqlExpr {
            var e = and()
            while (word("or")) e = SqlExpr.Bin("or", e, and())
            return e
        }

        fun and(): SqlExpr {
            var e = not()
            while (word("and")) e = SqlExpr.Bin("and", e, not())
            return e
        }

        fun not(): SqlExpr = if (word("not")) SqlExpr.Not(not()) else cmp()

        fun cmp(): SqlExpr {
            val left = primary()
            val t = peek() ?: return left
            if (t.kind == SqlTokenKind.SYMBOL && t.text in COMPARISONS) {
                i++
                return when {
                    word("any") || word("some") -> quantified(left, t.text == "=", false)
                    word("all") -> quantified(left, t.text == "<>" || t.text == "!=", true)
                    else -> SqlExpr.Bin(t.text, left, primary())
                }
            }
            if (t.kind == SqlTokenKind.SYMBOL && (t.text == "~" || t.text == "~*")) {
                i++
                return SqlExpr.Bin(t.text, left, primary())
            }
            if (word("is")) {
                val not = word("not")
                if (!word("null")) fail()
                return SqlExpr.IsNull(left, not)
            }
            val negated = isWord("not") && (isWord("in", 1) || isWord("between", 1))
            if (negated) i++
            val e =
                when {
                    word("in") -> {
                        expect("(")
                        SqlExpr.In(left, list(")"))
                    }
                    word("between") -> {
                        val low = primary()
                        if (!word("and")) fail()
                        SqlExpr.Between(left, low, primary())
                    }
                    else -> return left
                }
            return if (negated) SqlExpr.Not(e) else e
        }

        /** `ANY (ARRAY[…])` after `=` and `ALL (ARRAY[…])` after `<>`; nothing else qualifies. */
        private fun quantified(left: SqlExpr, matches: Boolean, negate: Boolean): SqlExpr {
            expect("(")
            val inner = or()
            expect(")")
            val items = arrays[inner]
            if (!matches || items == null) fail()
            val e = SqlExpr.In(left, items)
            return if (negate) SqlExpr.Not(e) else e
        }

        /** Comma-separated expressions up to and including [close]. */
        fun list(close: String): List<SqlExpr> {
            val items = mutableListOf<SqlExpr>()
            if (symbol(close)) return items
            do items += or() while (symbol(","))
            expect(close)
            return items
        }

        fun primary(): SqlExpr {
            val start = i
            val t = peek() ?: fail()
            val e: SqlExpr =
                when {
                    t.kind == SqlTokenKind.SYMBOL && t.text == "(" -> {
                        i++
                        or().also { expect(")") }
                    }
                    t.kind == SqlTokenKind.STRING -> {
                        i++
                        SqlExpr.Str(t.text)
                    }
                    t.kind == SqlTokenKind.NUMBER -> {
                        i++
                        SqlExpr.Num(t.text)
                    }
                    t.kind == SqlTokenKind.SYMBOL &&
                        (t.text == "-" || t.text == "+") &&
                        peek(1)?.kind == SqlTokenKind.NUMBER -> {
                        i += 2
                        SqlExpr.Num(if (t.text == "-") "-" + ts[i - 1].text else ts[i - 1].text)
                    }
                    isWord("true") || isWord("false") -> {
                        i++
                        SqlExpr.Bool(t.text == "true")
                    }
                    isWord("null") -> {
                        i++
                        SqlExpr.Null
                    }
                    t.kind == SqlTokenKind.IDENT &&
                        t.text in VALUE_FUNCTIONS &&
                        !isSymbol("(", 1) &&
                        !isSymbol(".", 1) -> {
                        i++
                        SqlExpr.Call(t.text, emptyList())
                    }
                    isWord("array") && isSymbol("[", 1) -> {
                        i += 2
                        val items = list("]")
                        SqlExpr.Raw(text(ts, start, i)).also { arrays[it] = items }
                    }
                    t.kind == SqlTokenKind.QIDENT ||
                        (t.kind == SqlTokenKind.IDENT && t.text !in RESERVED) -> {
                        var name = t.text
                        i++
                        while (
                            isSymbol(".") &&
                                peek(1)?.kind.let {
                                    it == SqlTokenKind.IDENT || it == SqlTokenKind.QIDENT
                                }
                        ) {
                            name = ts[i + 1].text
                            i += 2
                        }
                        if (symbol("(")) SqlExpr.Call(name, list(")")) else SqlExpr.Col(name)
                    }
                    else -> fail()
                }
            while (symbol("::")) skipType()
            return e
        }

        /** A cast's type: a possibly qualified name, its continuation words, `(…)`, and `[]`s. */
        private fun skipType() {
            val t = peek() ?: fail()
            if (t.kind != SqlTokenKind.IDENT && t.kind != SqlTokenKind.QIDENT) fail()
            i++
            while (
                isSymbol(".") &&
                    peek(1)?.kind.let { it == SqlTokenKind.IDENT || it == SqlTokenKind.QIDENT }
            ) i += 2
            while (true) {
                when {
                    peek()?.let { it.kind == SqlTokenKind.IDENT && it.text in TYPE_CONTINUATION } ==
                        true -> i++
                    isSymbol("(") -> skipParens()
                    else -> break
                }
            }
            while (symbol("[")) {
                if (peek()?.kind == SqlTokenKind.NUMBER) i++
                expect("]")
            }
        }

        private fun skipParens() {
            var depth = 0
            do {
                val t = peek() ?: fail()
                if (t.kind == SqlTokenKind.SYMBOL && t.text == "(") depth++
                if (t.kind == SqlTokenKind.SYMBOL && t.text == ")") depth--
                i++
            } while (depth > 0)
        }
    }
}
