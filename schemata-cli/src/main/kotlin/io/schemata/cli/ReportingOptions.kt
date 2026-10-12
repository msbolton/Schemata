package io.schemata.cli

import com.github.ajalt.clikt.core.ParameterHolder
import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import io.schemata.cli.report.Palette
import io.schemata.config.ProjectConfig

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

private fun ParameterHolder.colorOption() =
    option(
            "--color",
            help =
                "auto (default, colour when the terminal supports it and NO_COLOR is unset), always, or never",
        )
        .choice("auto", "always", "never")

private fun palette(color: String, ansiSupported: Boolean): Palette =
    when (color) {
        "always" -> Palette.ANSI
        "never" -> Palette.NONE
        else ->
            if (ansiSupported && System.getenv("NO_COLOR") == null) Palette.ANSI else Palette.NONE
    }

/** A report style whose format and colour are settled. */
private class ResolvedStyle(override val format: Format, private val color: String) : ReportStyle {
    override fun palette(ansiSupported: Boolean): Palette = palette(color, ansiSupported)
}

/**
 * `--format` and `--color`. Absent flags fall back to the configuration's `[report]`, then to human
 * and auto. Until a configuration is known (while the file itself is being reported), the group is
 * the style of its flags and the built-in defaults.
 */
abstract class StyleOptions : OptionGroup(), ReportStyle {
    private val formatFlag: Format? by formatOption()

    private val colorFlag: String? by colorOption()

    override val format: Format
        get() = resolve(null).format

    override fun palette(ansiSupported: Boolean): Palette = resolve(null).palette(ansiSupported)

    /**
     * The style this run prints with: each flag, else [config]'s `[report]` value, else default.
     */
    fun resolve(config: ProjectConfig?): ReportStyle =
        ResolvedStyle(
            formatFlag
                ?: config?.report?.format?.value?.let {
                    if (it == "json") Format.JSON else Format.HUMAN
                }
                ?: Format.HUMAN,
            colorFlag ?: config?.report?.color?.value ?: "auto",
        )
}

/** Options shared by every command that compiles and prints a report. */
class ReportingOptions : StyleOptions() {
    /** The `--strict` flag alone; `compile` and `check` also take `[compile] strict`. */
    val strict: Boolean by
        option("--strict", help = "Report implicit ordinals and treat every warning as an error")
            .flag()
}

/** `fmt`'s options: how to print a file that does not parse. */
class FormatOptions : StyleOptions()
