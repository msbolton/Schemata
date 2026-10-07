package io.schemata.core

import io.schemata.core.ir.BoolValue
import io.schemata.core.ir.EnumRef
import io.schemata.core.ir.IntValue
import io.schemata.core.ir.QualifiedName
import io.schemata.core.ir.RealValue
import io.schemata.core.ir.RecordType
import io.schemata.core.ir.StringValue
import io.schemata.lang.Parser
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DefaultsTest {
    private fun analyze(source: String): AnalysisResult =
        Analyzer.analyze(listOf(Parser.parse(source, "t.schemata").file!!))

    private fun messages(r: AnalysisResult) =
        r.diagnostics.map { "${it.span.startLine}:${it.span.startColumn} ${it.message}" }

    @Test
    fun `defaults that satisfy the type and its refinements become values`() {
        val src =
            "schema a\n" +
                "enum Status { pending, paid }\n" +
                "alias Money = decimal(19, 4)\n" +
                "model R {\n" +
                "  a Status = pending\n" +
                "  b int32 { min 0 } = 3\n" +
                "  c string? { max 5 } = \"\"\n" +
                "  d float64 = 2\n" +
                "  e Money = 1.25\n" +
                "  f bool = true\n" +
                "  g int64 = -7\n" +
                "}"
        val r = analyze(src)
        assertEquals(emptyList(), messages(r))
        val fields =
            (r.schema!!.namespaces.single().declarations.first { it.name == "R" } as RecordType)
                .fields
        assertEquals(
            listOf(
                EnumRef(QualifiedName("a", listOf("Status")), "pending"),
                IntValue(3),
                StringValue(""),
                RealValue(BigDecimal.valueOf(2)),
                RealValue(BigDecimal("1.25")),
                BoolValue(true),
                IntValue(-7),
            ),
            fields.map { it.default },
        )
    }

    @Test
    fun `defaults are checked against the type and its refinements`() {
        val src =
            "schema a\n" +
                "enum Status { pending, paid }\n" +
                "model P { x bool }\n" +
                "model Q { y bool }\n" +
                "union U = P | Q\n" +
                "model R {\n" +
                "  a string = null\n" +
                "  b Status = shipped\n" +
                "  c P = 1\n" +
                "  d int32[] = 1\n" +
                "  e int32 { min 5 } = 3\n" +
                "  f string { max 2 } = \"abc\"\n" +
                "  g string { match \"^x\" } = \"y\"\n" +
                "  h decimal(5, 1) = 1.25\n" +
                "  i uuid = \"u\"\n" +
                "  j int32 = \"1\"\n" +
                "  k int32 { max 10 } = 11\n" +
                "  l bool = 1\n" +
                "  m decimal(5, 1) = 123456.7\n" +
                "  n bytes = \"b\"\n" +
                "  o map<string, int32> = 1\n" +
                "  p U = 1\n" +
                "  q decimal(2, 0) = 100\n" +
                "  r string { max 2 } = \"a\\nb\\\"c\"\n" +
                "}"
        val r = analyze(src)
        assertNull(r.schema)
        assertEquals(
            listOf(
                "7:14 default may not be null",
                "8:14 default for enum 'Status' must be one of: pending, paid",
                "9:9 record fields cannot have a default",
                "10:15 list fields cannot have a default",
                "11:23 default 3 is below min 5",
                "12:24 default \"abc\" is longer than max 2",
                "13:29 default \"y\" does not match pattern \"^x\"",
                "14:21 default 1.25 exceeds scale 1",
                "15:12 uuid fields cannot have a default",
                "16:13 default for int32 must be an integer literal",
                "17:24 default 11 is above max 10",
                "18:12 default for bool must be true or false",
                "19:21 default 123456.7 exceeds precision 5",
                "20:13 bytes fields cannot have a default",
                "21:26 map fields cannot have a default",
                "22:9 union fields cannot have a default",
                "23:21 default 100 exceeds precision 2",
                "24:24 default \"a\\nb\\\"c\" is longer than max 2",
            ),
            messages(r),
        )
    }

    @Test
    fun `a float default must lie within the type's finite range`() {
        val big39 = "1" + "0".repeat(39)
        val big309 = "1" + "0".repeat(309)
        val src =
            "schema a\n" +
                "model R {\n" +
                "  a float32 = $big39.0\n" +
                "  b float64 = -$big309.5\n" +
                "  c float32 = -340282350000000000000000000000000000000.0\n" +
                "  d float64 = $big39.0\n" +
                "}"
        val r = analyze(src)
        assertEquals(
            listOf(
                "3:15 default $big39.0 is outside the range of float32 (±3.4028235E38)",
                "4:15 default -$big309.5 is outside the range of float64 (±1.7976931348623157E308)",
            ),
            messages(r),
        )
        assertEquals(
            listOf(
                "use a value between -3.4028235E38 and 3.4028235E38, or declare the field float64",
                "use a value between -1.7976931348623157E308 and 1.7976931348623157E308",
            ),
            r.diagnostics.map { it.help },
        )
        assertEquals(listOf("SCH1042", "SCH1042"), r.diagnostics.map { it.code.id })
    }
}
