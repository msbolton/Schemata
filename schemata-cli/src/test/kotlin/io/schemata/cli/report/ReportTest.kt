package io.schemata.cli.report

import io.schemata.cli.PipelineResult
import io.schemata.cli.TargetResult
import io.schemata.core.CoreCodes
import io.schemata.lang.Diagnostic
import io.schemata.lang.LangCodes
import io.schemata.lang.Severity
import io.schemata.lang.Span
import io.schemata.target.OutputFile
import io.schemata.target.proto.ProtoCodes
import io.schemata.target.sql.SqlCodes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReportTest {
    private fun d(
        code: io.schemata.lang.DiagnosticCode,
        file: String,
        line: Int,
        col: Int,
        msg: String = "m",
    ) = Diagnostic(code, msg, Span(file, line, col, line, col + 1))

    private val clean =
        PipelineResult(
            emptyList(),
            listOf(TargetResult("proto", listOf(OutputFile("a.proto", "")), emptyList())),
        )

    @Test
    fun `entries are sorted by file, line, column, code, message and keep emission order on ties`() {
        val result =
            PipelineResult(
                core =
                    listOf(
                        d(CoreCodes.UNUSED_IMPORT, "b.schemata", 1, 1),
                        d(CoreCodes.BUILTIN_SHADOWED, "a.schemata", 3, 1),
                    ),
                targets =
                    listOf(
                        TargetResult(
                            "sql",
                            emptyList(),
                            listOf(
                                d(SqlCodes.LOSSY, "a.schemata", 2, 5, "second"),
                                d(SqlCodes.LOSSY, "a.schemata", 2, 5, "first"),
                            ),
                        ),
                        TargetResult(
                            "proto",
                            emptyList(),
                            listOf(d(ProtoCodes.LOSSY, "a.schemata", 2, 5)),
                        ),
                    ),
            )
        val report = Report.of(result, strict = false, checkOnly = true)
        assertEquals(
            listOf(
                "a.schemata:2:5 SCH2001 m" to "proto",
                "a.schemata:2:5 SCH2105 first" to "sql",
                "a.schemata:2:5 SCH2105 second" to "sql",
                "a.schemata:3:1 SCH1010 m" to null,
                "b.schemata:1:1 SCH1012 m" to null,
            ),
            report.entries.map {
                "${it.diagnostic.span.file}:${it.diagnostic.span.startLine}:${it.diagnostic.span.startColumn} ${it.diagnostic.code.id} ${it.diagnostic.message}" to
                    it.target
            },
        )
    }

    @Test
    fun `exit code is 0 when nothing was reported`() {
        val report = Report.of(clean, strict = false, checkOnly = false)
        assertEquals(0, report.exitCode)
        assertEquals(0, report.errors)
        assertEquals(0, report.warnings)
    }

    @Test
    fun `exit code is 2 with warnings only and 1 with any error`() {
        val warn =
            PipelineResult(listOf(d(CoreCodes.UNUSED_IMPORT, "a.schemata", 1, 1)), emptyList())
        assertEquals(2, Report.of(warn, strict = false, checkOnly = true).exitCode)
        val err = PipelineResult(listOf(d(LangCodes.SYNTAX, "a.schemata", 1, 1)), emptyList())
        assertEquals(1, Report.of(err, strict = false, checkOnly = true).exitCode)
    }

    @Test
    fun `strict promotes every warning to an error and counts the promotions`() {
        val result =
            PipelineResult(
                listOf(d(CoreCodes.UNUSED_IMPORT, "a.schemata", 1, 1)),
                listOf(
                    TargetResult(
                        "proto",
                        emptyList(),
                        listOf(d(ProtoCodes.LOSSY, "a.schemata", 2, 1)),
                    )
                ),
            )
        val report = Report.of(result, strict = true, checkOnly = true)
        assertTrue(report.entries.all { it.promoted && it.severity == Severity.ERROR })
        assertEquals(2, report.errors)
        assertEquals(0, report.warnings)
        assertEquals(2, report.promotions)
        assertEquals(1, report.exitCode)
    }

    @Test
    fun `written lists each clean target's files and skipped names the erroring ones`() {
        val result =
            PipelineResult(
                emptyList(),
                listOf(
                    TargetResult("proto", listOf(OutputFile("shop/orders.proto", "")), emptyList()),
                    TargetResult(
                        "sql",
                        emptyList(),
                        listOf(
                            d(SqlCodes.MISSING_KEY, "a.schemata", 1, 1),
                            d(SqlCodes.MISSING_KEY, "a.schemata", 2, 1),
                        ),
                    ),
                ),
            )
        val report = Report.of(result, strict = false, checkOnly = false)
        assertEquals(listOf(Written("proto", "proto/shop/orders.proto")), report.written)
        assertEquals(listOf(Skipped("sql", 2)), report.skipped)
        assertEquals(1, report.exitCode)
    }

    @Test
    fun `a clean target whose warnings were promoted is skipped, not written`() {
        val result =
            PipelineResult(
                emptyList(),
                listOf(
                    TargetResult(
                        "proto",
                        listOf(OutputFile("a.proto", "")),
                        listOf(d(ProtoCodes.LOSSY, "a.schemata", 1, 1)),
                    )
                ),
            )
        val report = Report.of(result, strict = true, checkOnly = false)
        assertEquals(emptyList(), report.written)
        assertEquals(listOf(Skipped("proto", 1)), report.skipped)
    }

    @Test
    fun `check reports nothing written or skipped`() {
        val report = Report.of(clean, strict = false, checkOnly = true)
        assertEquals(emptyList(), report.written)
        assertEquals(emptyList(), report.skipped)
    }
}
