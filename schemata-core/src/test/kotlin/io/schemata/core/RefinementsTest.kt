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
            "schema a\n" +
                "alias Email = string { max 254, match \"^[^@]+@[^@]+$\" }\n" +
                "alias Money = decimal(19, 4)\n" +
                "model R {\n" +
                "  a Email\n" +
                "  b Money?\n" +
                "  c int32 { min 0, max 100 }\n" +
                "  d string[] { maxItems 10, max 32 }\n" +
                "  e float64 { min -1.5 }\n" +
                "  f map<string { min 1 }, int32> { minItems 1 }\n" +
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
    fun `unknown, duplicate, and misplaced options are reported at the option`() {
        val src =
            "schema a\n" +
                "enum E { x }\n" +
                "alias A = int32\n" +
                "model R {\n" +
                "  a string { size 5 }\n" +
                "  b bool { max 1 }\n" +
                "  c int32 { min 1, min 2 }\n" +
                "  d E { max 1 }\n" +
                "  e A { max 1 }\n" +
                "}"
        val r = analyze(src)
        assertNull(r.schema)
        assertEquals(
            listOf(
                "5:14 unknown option 'size'; options: embed, id, index, match, max, maxItems, min, minItems, unique",
                "6:12 option 'max' does not apply to bool; it needs a string, bytes, or a number",
                "7:20 option 'min' is given more than once",
                "8:9 option 'max' does not apply to an enum; it needs a string, bytes, or a number",
                "9:9 'A' is an alias and takes no option 'max' here",
            ),
            messages(r),
        )
    }

    @Test
    fun `refinement values are validated`() {
        val src =
            "schema a\n" +
                "model R {\n" +
                "  a int32 { min 10, max 5 }\n" +
                "  b int32 { max 3000000000 }\n" +
                "  c string { max -1 }\n" +
                "  d string { match \"(\" }\n" +
                "  e float32 { min \"x\" }\n" +
                "  f decimal(4, 5)\n" +
                "  g decimal\n" +
                "  h string(3, 1)\n" +
                "  i int64 { min 1.5 }\n" +
                "  j decimal(0, 0)\n" +
                "  k decimal(3, -1)\n" +
                "  l string { match 5 }\n" +
                "  m string { max \"x\" }\n" +
                "}"
        val r = analyze(src)
        assertNull(r.schema)
        assertEquals(
            listOf(
                "3:21 min 10 exceeds max 5",
                "4:13 max 3000000000 is outside the range of int32",
                "5:14 max must not be negative",
                "6:14 match does not compile: Unclosed group",
                "7:15 min for float32 must be a number",
                "8:16 scale 5 exceeds precision 4",
                "9:5 decimal needs precision and scale",
                "10:12 only decimal takes positional refinements",
                "10:15 only decimal takes positional refinements",
                "11:13 min for int64 must be an integer literal",
                "12:13 precision must be at least 1",
                "13:16 scale must not be negative",
                "14:14 match must be a string literal",
                "15:14 max must be an integer literal",
            ),
            messages(r),
        )
    }

    @Test
    fun `a float bound must lie within the type's finite range`() {
        val big39 = "1" + "0".repeat(39)
        val big309 = "1" + "0".repeat(309)
        val src =
            "schema a\n" +
                "model R {\n" +
                "  a float32 { max $big39.0 }\n" +
                "  b float32 { min -$big39.5 }\n" +
                "  c float64 { max $big309.0 }\n" +
                "  d float32 { min -340282350000000000000000000000000000000.0, max 340282350000000000000000000000000000000.0 }\n" +
                "  e float64 { max $big39.0 }\n" +
                "  f decimal(400, 0) { max $big309.0 }\n" +
                "}"
        val r = analyze(src)
        assertEquals(
            listOf(
                "3:15 max $big39.0 is outside the range of float32 (±3.4028235E38)",
                "4:15 min -$big39.5 is outside the range of float32 (±3.4028235E38)",
                "5:15 max $big309.0 is outside the range of float64 (±1.7976931348623157E308)",
            ),
            messages(r),
        )
        assertEquals(
            listOf("use a value between -3.4028235E38 and 3.4028235E38"),
            r.diagnostics.map { it.help }.take(1),
        )
    }

    @Test
    fun `an alias is checked once, whether or not it is used`() {
        val used = analyze("schema a\nalias Bad = string { max -1 }\nmodel R { x Bad  y Bad }")
        assertEquals(listOf("2:22 max must not be negative"), messages(used))
        val unused = analyze("schema a\nalias Bad = string { max -1 }\nmodel R { x bool }")
        assertEquals(listOf("2:22 max must not be negative"), messages(unused))
    }
}
