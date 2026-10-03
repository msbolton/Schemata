package io.schemata.cli.report

import io.schemata.cli.PipelineResult
import io.schemata.cli.SourceInput
import io.schemata.cli.TargetResult
import io.schemata.core.CoreCodes
import io.schemata.lang.Diagnostic
import io.schemata.lang.LangCodes
import io.schemata.lang.Span
import io.schemata.target.OutputFile
import io.schemata.target.proto.ProtoCodes
import io.schemata.target.sql.SqlCodes
import io.schemata.testkit.Golden
import kotlin.test.Test

class HumanRendererTest {
    private val orders =
        SourceInput(
            "shop/orders.schemata",
            "namespace shop.orders\n\nrecord Order {\n  status: Status = null\n\tnote: string(max = 5,\n    min = 1)\n}\n",
        )
    private val sources = Sources.of(listOf(orders))

    private fun render(report: Report, palette: Palette = Palette.NONE) =
        HumanRenderer.render(report, sources, palette, out = "out")

    private fun report(
        vararg targets: TargetResult,
        core: List<Diagnostic> = emptyList(),
        strict: Boolean = false,
        checkOnly: Boolean = true,
    ) = Report.of(PipelineResult(core, targets.toList()), strict, checkOnly)

    @Test
    fun `single-line span with help`() {
        val d =
            Diagnostic(
                CoreCodes.NULL_DEFAULT,
                "a default may not be null; declare the field as nullable with '?'",
                Span("shop/orders.schemata", 4, 20, 4, 24),
                help = "declare the field as `Status?`",
            )
        Golden.assertMatches("report/single-line.txt", render(report(core = listOf(d))))
    }

    @Test
    fun `multi-line span underlines to the end of its first line and marks continuation`() {
        val d =
            Diagnostic(
                CoreCodes.UNKNOWN_REFINEMENT,
                "'min' is not a refinement of string",
                Span("shop/orders.schemata", 5, 15, 6, 12),
            )
        Golden.assertMatches("report/multi-line.txt", render(report(core = listOf(d))))
    }

    @Test
    fun `tabs render as four spaces and the caret line compensates`() {
        val d =
            Diagnostic(
                CoreCodes.FIELD_NAMING,
                "field name",
                Span("shop/orders.schemata", 5, 2, 5, 6),
            )
        Golden.assertMatches("report/tabs.txt", render(report(core = listOf(d))))
    }

    @Test
    fun `a diagnostic on an unknown file prints header and location only`() {
        val d = Diagnostic(SqlCodes.LOSSY, "lost", Span("elsewhere.schemata", 9, 1, 9, 2))
        Golden.assertMatches(
            "report/missing-source.txt",
            render(report(TargetResult("sql", emptyList(), listOf(d)))),
        )
    }

    @Test
    fun `promoted warnings print as errors and the trailer counts them`() {
        val d =
            Diagnostic(
                ProtoCodes.LOSSY,
                "proto3 requires a zero value",
                Span("shop/orders.schemata", 3, 8, 3, 13),
            )
        Golden.assertMatches(
            "report/promoted.txt",
            render(report(TargetResult("proto", emptyList(), listOf(d)), strict = true)),
        )
    }

    @Test
    fun `color wraps severity, arrow, gutter, and message`() {
        val d =
            Diagnostic(
                ProtoCodes.LOSSY,
                "proto3 requires a zero value",
                Span("shop/orders.schemata", 3, 8, 3, 13),
            )
        Golden.assertMatches(
            "report/color.txt",
            render(report(TargetResult("proto", emptyList(), listOf(d))), Palette.ANSI),
        )
    }

    @Test
    fun `a span one column past the end of the line renders its caret after the last character`() {
        val d =
            Diagnostic(
                LangCodes.SYNTAX,
                "mismatched input '<EOF>' expecting '}'",
                Span("shop/orders.schemata", 3, 15, 3, 15),
            )
        Golden.assertMatches("report/end-of-input.txt", render(report(core = listOf(d))))
    }

    @Test
    fun `an empty report prints only the trailer`() {
        Golden.assertMatches("report/empty.txt", render(report()))
    }

    @Test
    fun `compile trailers name written and skipped targets`() {
        val d =
            Diagnostic(
                SqlCodes.MISSING_KEY,
                "record 'Order' has no key",
                Span("shop/orders.schemata", 3, 8, 3, 13),
            )
        val r =
            report(
                TargetResult(
                    "proto",
                    listOf(
                        OutputFile("shop/orders.proto", ""),
                        OutputFile("shop/customers.proto", ""),
                    ),
                    emptyList(),
                ),
                TargetResult("sql", emptyList(), listOf(d)),
                checkOnly = false,
            )
        Golden.assertMatches("report/writes.txt", render(r))
    }
}
