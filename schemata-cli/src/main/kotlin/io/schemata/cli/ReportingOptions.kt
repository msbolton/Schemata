package io.schemata.cli

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

/** Options shared by every command that prints a report. */
class ReportingOptions : OptionGroup() {
    val format: Format by
        option("--format", help = "human (stderr, default) or json (stdout)")
            .choice("human" to Format.HUMAN, "json" to Format.JSON)
            .default(Format.HUMAN)

    private val color: String by
        option(
                "--color",
                help =
                    "auto (default, colour when the terminal supports it and NO_COLOR is unset), always, or never",
            )
            .choice("auto", "always", "never")
            .default("auto")

    val strict: Boolean by
        option("--strict", help = "Report implicit ordinals and treat every warning as an error")
            .flag()

    /**
     * ANSI when asked for; when auto, ANSI only if [ansiSupported] (the terminal Clikt detected for
     * this run) is true and `NO_COLOR` is unset.
     */
    fun palette(ansiSupported: Boolean): Palette =
        when (color) {
            "always" -> Palette.ANSI
            "never" -> Palette.NONE
            else ->
                if (ansiSupported && System.getenv("NO_COLOR") == null) Palette.ANSI
                else Palette.NONE
        }
}
