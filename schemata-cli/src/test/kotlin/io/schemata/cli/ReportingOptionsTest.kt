package io.schemata.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.testing.test
import com.github.ajalt.mordant.rendering.AnsiLevel
import io.schemata.cli.report.Palette
import io.schemata.cli.report.Report
import io.schemata.cli.report.Sources
import io.schemata.lang.Diagnostic
import io.schemata.lang.Span
import io.schemata.target.sql.SqlCodes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class PaletteProbe : CliktCommand(name = "probe") {
    private val reporting by ReportingOptions()
    lateinit var whenSupported: Palette
    lateinit var whenUnsupported: Palette

    override fun run() {
        whenSupported = reporting.palette(ansiSupported = true)
        whenUnsupported = reporting.palette(ansiSupported = false)
    }
}

/** Prints one warning through [emit] with the given answer to "is stderr a terminal". */
private class EmitProbe(private val stderrIsTerminal: Boolean) : CliktCommand(name = "emit") {
    private val reporting by ReportingOptions()

    override fun run() {
        val d = Diagnostic(SqlCodes.LOSSY, "lost", Span("s.schemata", 1, 1, 1, 1))
        val report = Report.of(listOf(d), emptyList(), emptyList(), strict = false)
        emit(this, report, Sources.of(emptyList()), reporting, "out", { stderrIsTerminal })
    }
}

class ReportingOptionsTest {
    @Test
    fun `auto is ANSI when the terminal supports it and NO_COLOR is unset`() {
        if (System.getenv("NO_COLOR") != null) {
            println(
                "skipping: NO_COLOR is set in this environment, so this run cannot assert the unset case"
            )
            return
        }
        val probe = PaletteProbe()
        probe.test("")
        assertEquals(Palette.ANSI, probe.whenSupported)
    }

    @Test
    fun `auto is plain when the terminal does not support ansi`() {
        val probe = PaletteProbe()
        probe.test("")
        assertEquals(Palette.NONE, probe.whenUnsupported)
    }

    @Test
    fun `always is ANSI even when the terminal does not support it`() {
        val probe = PaletteProbe()
        probe.test("--color always")
        assertEquals(Palette.ANSI, probe.whenUnsupported)
    }

    @Test
    fun `never is plain even when the terminal supports it`() {
        val probe = PaletteProbe()
        probe.test("--color never")
        assertEquals(Palette.NONE, probe.whenSupported)
    }

    @Test
    fun `auto colours the report only when stderr is a terminal`() {
        if (System.getenv("NO_COLOR") != null) {
            println("skipping: NO_COLOR is set in this environment")
            return
        }
        // The test terminal reports ANSI support and an interactive stdout either way, so only
        // the stderr answer differs between the two runs.
        val onTerminal =
            EmitProbe(stderrIsTerminal = true).test("", ansiLevel = AnsiLevel.TRUECOLOR)
        val redirected =
            EmitProbe(stderrIsTerminal = false).test("", ansiLevel = AnsiLevel.TRUECOLOR)
        assertTrue(onTerminal.stderr.contains("\u001b["), onTerminal.stderr)
        assertFalse(redirected.stderr.contains("\u001b["), redirected.stderr)
    }
}
