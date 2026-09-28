package io.schemata.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.testing.test
import io.schemata.cli.report.Palette
import kotlin.test.Test
import kotlin.test.assertEquals

private class PaletteProbe : CliktCommand(name = "probe") {
    private val reporting by ReportingOptions()
    lateinit var whenSupported: Palette
    lateinit var whenUnsupported: Palette

    override fun run() {
        whenSupported = reporting.palette(ansiSupported = true)
        whenUnsupported = reporting.palette(ansiSupported = false)
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
}
