package io.schemata.cli

import com.github.ajalt.clikt.core.ParameterHolder
import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import io.schemata.cli.report.Palette

enum class Format {
    HUMAN,
    JSON,
}

/** How a command prints its report: the format, and the palette for a human one. */
interface ReportStyle {
    val format: Format

    /**
     * ANSI when asked for; when auto, ANSI only if [ansiSupported] (the terminal supports colour
     * and the stream the report is written to, stderr, is that terminal) is true and `NO_COLOR` is
     * unset.
     */
    fun palette(ansiSupported: Boolean): Palette
}

private fun ParameterHolder.formatOption() =
    option("--format", help = "human (stderr, default) or json (stdout)")
        .choice("human" to Format.HUMAN, "json" to Format.JSON)
        .default(Format.HUMAN)

private fun ParameterHolder.colorOption() =
    option(
            "--color",
            help =
                "auto (default, colour when the terminal supports it and NO_COLOR is unset), always, or never",
        )
        .choice("auto", "always", "never")
        .default("auto")

private fun palette(color: String, ansiSupported: Boolean): Palette =
    when (color) {
        "always" -> Palette.ANSI
        "never" -> Palette.NONE
        else ->
            if (ansiSupported && System.getenv("NO_COLOR") == null) Palette.ANSI else Palette.NONE
    }

/** Options shared by every command that compiles and prints a report. */
class ReportingOptions : OptionGroup(), ReportStyle {
    override val format: Format by formatOption()

    private val color: String by colorOption()

    val strict: Boolean by
        option("--strict", help = "Report implicit ordinals and treat every warning as an error")
            .flag()

    override fun palette(ansiSupported: Boolean): Palette = palette(color, ansiSupported)
}

/** `fmt`'s options: how to print a file that does not parse. */
class FormatOptions : OptionGroup(), ReportStyle {
    override val format: Format by formatOption()

    private val color: String by colorOption()

    override fun palette(ansiSupported: Boolean): Palette = palette(color, ansiSupported)
}
