package io.schemata.core

import io.schemata.core.ir.Builtin
import io.schemata.core.ir.ListOf
import io.schemata.core.ir.MapOf
import io.schemata.core.ir.RecordType
import io.schemata.core.ir.Refinements
import io.schemata.core.ir.Scalar
import io.schemata.lang.Parser
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RefinementsTest {
    private fun analyze(source: String): AnalysisResult =
        Analyzer.analyze(listOf(Parser.parse(source, "t.schemata").file!!))

    private fun messages(r: AnalysisResult) =
        r.diagnostics.map { "${it.span.startLine}:${it.span.startColumn} ${it.message}" }

    private fun record(r: AnalysisResult, name: String) =
        r.schema!!.namespaces.single().declarations.first { it.name == name } as RecordType

    private fun big(n: Long) = BigDecimal.valueOf(n)

    @Test
    fun `refinements on builtins, collections, and aliases reach the IR`() {
        val src =
            "namespace a\n" +
                "alias Email = string(max = 254, pattern = \"^[^@]+@[^@]+$\")\n" +
                "alias Money = decimal(19, 4)\n" +
                "record R {\n" +
                "  a: Email\n" +
                "  b: Money?\n" +
                "  c: int32(min = 0, max = 100)\n" +
                "  d: list<string(max = 32)>(max = 10)\n" +
                "  e: float64(min = -1.5)\n" +
                "  f: map<string(min = 1), int32>(min = 1)\n" +
                "}"
        val r = analyze(src)
        assertEquals(emptyList(), messages(r))
        val fields = record(r, "R").fields
        assertEquals(
            Scalar(Builtin.STRING, Refinements(max = big(254), pattern = "^[^@]+@[^@]+$")),
            fields[0].type,
        )
        assertEquals("Email", fields[0].aliasName)
        assertEquals(
            Scalar(Builtin.DECIMAL, Refinements(precision = 19, scale = 4)),
            fields[1].type,
        )
        assertEquals(true, fields[1].nullable)
        assertEquals(
            Scalar(Builtin.INT32, Refinements(min = big(0), max = big(100))),
            fields[2].type,
        )
        assertEquals(
            ListOf(
                Scalar(Builtin.STRING, Refinements(max = big(32))),
                false,
                Refinements(max = big(10)),
            ),
            fields[3].type,
        )
        assertEquals(Scalar(Builtin.FLOAT64, Refinements(min = BigDecimal("-1.5"))), fields[4].type)
        assertEquals(
            MapOf(
                Scalar(Builtin.STRING, Refinements(min = big(1))),
                Scalar(Builtin.INT32),
                false,
                Refinements(min = big(1)),
            ),
            fields[5].type,
        )
    }

    @Test
    fun `unknown, duplicate, and misplaced refinements are reported at the refinement`() {
        val src =
            "namespace a\n" +
                "enum E { x }\n" +
                "alias A = int32\n" +
                "record R {\n" +
                "  a: string(size = 5)\n" +
                "  b: bool(max = 1)\n" +
                "  c: int32(min = 1, min = 2)\n" +
                "  d: E(max = 1)\n" +
                "  e: A(max = 1)\n" +
                "}"
        val r = analyze(src)
        assertNull(r.schema)
        assertEquals(
            listOf(
                "5:13 'size' is not a refinement of string; allowed: max, min, pattern",
                "6:11 'max' is not a refinement; bool takes no refinements",
                "7:21 'min' is given more than once",
                "8:8 'E' is an enum; only builtin types and collections take refinements",
                "9:8 'A' is an alias; refine it where it is declared",
            ),
            messages(r),
        )
    }

    @Test
    fun `refinement values are validated`() {
        val src =
            "namespace a\n" +
                "record R {\n" +
                "  a: int32(min = 10, max = 5)\n" +
                "  b: int32(max = 3000000000)\n" +
                "  c: string(max = -1)\n" +
                "  d: string(pattern = \"(\")\n" +
                "  e: float32(min = \"x\")\n" +
                "  f: decimal(4, 5)\n" +
                "  g: decimal\n" +
                "  h: string(3)\n" +
                "  i: int64(min = 1.5)\n" +
                "  j: decimal(0, 0)\n" +
                "  k: decimal(3, -1)\n" +
                "  l: string(pattern = 5)\n" +
                "  m: string(max = \"x\")\n" +
                "}"
        val r = analyze(src)
        assertNull(r.schema)
        assertEquals(
            listOf(
                "3:22 min 10 exceeds max 5",
                "4:12 max 3000000000 is outside the range of int32",
                "5:13 max must not be negative",
                "6:13 pattern does not compile: Unclosed group",
                "7:14 min for float32 must be a numeric literal",
                "8:17 scale 5 exceeds precision 4",
                "9:6 decimal takes its precision and scale positionally: decimal(p, s)",
                "10:13 only decimal takes positional refinements",
                "11:12 min for int64 must be an integer literal",
                "12:14 precision must be at least 1",
                "13:17 scale must not be negative",
                "14:13 pattern must be a string literal",
                "15:13 max must be an integer literal",
            ),
            messages(r),
        )
    }

    @Test
    fun `an alias is checked once, whether or not it is used`() {
        val used = analyze("namespace a\nalias Bad = string(max = -1)\nrecord R { x: Bad  y: Bad }")
        assertEquals(listOf("2:20 max must not be negative"), messages(used))
        val unused = analyze("namespace a\nalias Bad = string(max = -1)\nrecord R { x: bool }")
        assertEquals(listOf("2:20 max must not be negative"), messages(unused))
    }
}
