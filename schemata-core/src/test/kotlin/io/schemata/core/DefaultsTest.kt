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
            "namespace a\n" +
                "enum Status { pending, paid }\n" +
                "alias Money = decimal(19, 4)\n" +
                "record R {\n" +
                "  a: Status = pending\n" +
                "  b: int32(min = 0) = 3\n" +
                "  c: string(max = 5)? = \"\"\n" +
                "  d: float64 = 2\n" +
                "  e: Money = 1.25\n" +
                "  f: bool = true\n" +
                "  g: int64 = -7\n" +
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
            "namespace a\n" +
                "enum Status { pending, paid }\n" +
                "record P { x: bool }\n" +
                "record R {\n" +
                "  a: string = null\n" +
                "  b: Status = shipped\n" +
                "  c: P = 1\n" +
                "  d: list<int32> = 1\n" +
                "  e: int32(min = 5) = 3\n" +
                "  f: string(max = 2) = \"abc\"\n" +
                "  g: string(pattern = \"^x\") = \"y\"\n" +
                "  h: decimal(5, 1) = 1.25\n" +
                "  i: uuid = \"u\"\n" +
                "  j: int32 = \"1\"\n" +
                "  k: int32(max = 10) = 11\n" +
                "  l: bool = 1\n" +
                "}"
        val r = analyze(src)
        assertNull(r.schema)
        assertEquals(
            listOf(
                "5:15 a default may not be null; declare the field as nullable with '?'",
                "6:15 default for enum 'Status' must be one of: pending, paid",
                "7:10 record fields cannot have a default",
                "8:20 list fields cannot have a default",
                "9:23 default 3 is below min 5",
                "10:24 default is longer than max 2",
                "11:31 default does not match pattern ^x",
                "12:22 default 1.25 exceeds scale 1",
                "13:13 uuid fields cannot have a default",
                "14:14 default for int32 must be an integer literal",
                "15:24 default 11 is above max 10",
                "16:13 default for bool must be true or false",
            ),
            messages(r),
        )
    }
}
