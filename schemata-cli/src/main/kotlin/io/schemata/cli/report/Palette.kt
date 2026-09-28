package io.schemata.cli.report

/** The escape codes a report uses; [NONE] renders plain text. */
class Palette(
    val error: String,
    val warning: String,
    val accent: String,
    val bold: String,
    val reset: String,
) {
    companion object {
        val NONE = Palette("", "", "", "", "")
        val ANSI = Palette("\u001b[31m", "\u001b[33m", "\u001b[34m", "\u001b[1m", "\u001b[0m")
    }
}
