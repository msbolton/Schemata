package io.schemata.cli.report

import io.schemata.lang.Category
import io.schemata.lang.Severity

/**
 * One block per entry: a header line, the location, the source line with carets under the span, and
 * the help line; then a trailer with counts and, for compile, what was written or skipped.
 */
object HumanRenderer {
    private const val TAB = "    "

    fun render(
        report: Report,
        sources: Sources,
        palette: Palette,
        out: String,
        width: Int = 100,
    ): String {
        val blocks = report.entries.map { block(it, sources, palette, width) }
        val trailer = trailer(report, out)
        return (blocks + trailer).joinToString("\n\n") + "\n"
    }

    private fun block(entry: Entry, sources: Sources, palette: Palette, width: Int): String {
        val d = entry.diagnostic
        val span = d.span
        val lines = mutableListOf<String>()
        lines += header(entry, palette)
        val number = span.startLine.toString()
        val gutter = " ".repeat(number.length)
        lines +=
            "$gutter${palette.accent}-->${palette.reset} ${span.file}:${span.startLine}:${span.startColumn}"
        val source = sources.line(span.file, span.startLine)
        if (source != null) {
            val expanded = source.replace("\t", TAB)
            // A span's end column is its last character, so the run is end - start + 1 wide; a
            // span that goes on to another line is underlined to the end of its first line.
            val start = column(source, span.startColumn)
            val end =
                if (span.endLine == span.startLine) column(source, span.endColumn)
                else expanded.length
            val carets = "^".repeat(maxOf(1, end - start + 1))
            val bar = "${palette.accent}|${palette.reset}"
            lines += "$gutter $bar"
            lines += "${palette.accent}$number${palette.reset} $bar $expanded"
            lines += "$gutter $bar ${" ".repeat(start - 1)}$carets"
            if (span.endLine != span.startLine) lines += "$gutter $bar ..."
        }
        d.help?.let { help -> lines += wrapHelp(help, gutter, width) }
        return lines.joinToString("\n")
    }

    private fun header(entry: Entry, palette: Palette): String {
        val d = entry.diagnostic
        val severity =
            when (entry.severity) {
                Severity.ERROR -> "${palette.error}error${palette.reset}"
                Severity.WARNING -> "${palette.warning}warning${palette.reset}"
            }
        val tags = buildString {
            if (d.category == Category.LOSSY) append(" (lossy)")
            entry.target?.let { append(" ($it)") }
            if (entry.promoted) append(" [promoted]")
        }
        return "$severity[${d.code.id}]$tags: ${palette.bold}${d.message}${palette.reset}"
    }

    /**
     * A 1-based column in the raw line, moved to the same character in the tab-expanded line. A
     * column one past the end of the line (a diagnostic at end of input) maps to one past the end
     * of the expanded line.
     */
    private fun column(raw: String, column: Int): Int {
        var expanded = 0
        raw.take(column - 1).forEach { expanded += if (it == '\t') TAB.length else 1 }
        return expanded + 1
    }

    private fun wrapHelp(help: String, gutter: String, width: Int): String {
        val prefix = "$gutter = help: "
        val continuation = " ".repeat(prefix.length)
        val words = help.split(' ')
        val out = mutableListOf<String>()
        var line = StringBuilder(prefix)
        var lineHasWord = false
        for (word in words) {
            if (lineHasWord && line.length + 1 + word.length > width) {
                out += line.toString()
                line = StringBuilder(continuation)
                lineHasWord = false
            }
            if (lineHasWord) line.append(' ')
            line.append(word)
            lineHasWord = true
        }
        out += line.toString()
        return out.joinToString("\n")
    }

    private fun trailer(report: Report, out: String): String {
        val lines = mutableListOf<String>()
        lines +=
            if (report.entries.isEmpty()) "no diagnostics"
            else {
                val counts =
                    "${plural(report.errors, "error")}, ${plural(report.warnings, "warning")}"
                if (report.promotions > 0) "$counts (${report.promotions} promoted by --strict)"
                else counts
            }
        if (!report.checkOnly) {
            report.written
                .groupBy { it.target }
                .forEach { (target, files) ->
                    lines += "wrote ${plural(files.size, "file")} to $out/$target"
                }
            report.skipped.forEach {
                lines += "${it.target}: not written (${plural(it.errors, "error")})"
            }
        }
        return lines.joinToString("\n")
    }

    private fun plural(n: Int, noun: String) = if (n == 1) "1 $noun" else "$n ${noun}s"
}
