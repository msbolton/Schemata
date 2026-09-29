package io.schemata.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import io.schemata.cli.report.Report
import io.schemata.cli.report.Sources
import io.schemata.lang.format.FormatResult
import io.schemata.lang.format.Formatter
import java.io.File

class FmtCommand : CliktCommand(name = "fmt") {
    override fun help(context: Context) =
        "Rewrite schema files in the canonical layout, or check that they already are."

    private val check by
        option(
                "--check",
                help =
                    "Write nothing; print a diff for each file that would change and exit 1 if any would",
            )
            .flag()
    private val reporting by ReportingOptions()
    private val inputs by argument("PATHS").path(mustExist = true).multiple(required = true)

    override fun run() {
        val sources = loadSources(inputs)
        var failed = false
        var differs = false
        for (s in sources) {
            when (val r = Formatter.format(s.content, s.path)) {
                is FormatResult.Failed -> {
                    failed = true
                    val report =
                        Report.of(
                            PipelineResult(r.diagnostics, emptyList()),
                            strict = false,
                            checkOnly = true,
                        )
                    emit(this, report, Sources.of(listOf(s)), reporting, out = "")
                }
                is FormatResult.Formatted ->
                    if (r.text != s.content) {
                        differs = true
                        if (check)
                            echo(unifiedDiff(s.path, s.content, r.text), trailingNewline = false)
                        else {
                            File(s.path).writeText(r.text)
                            echo("formatted ${s.path}")
                        }
                    }
            }
        }
        if (failed || (check && differs)) throw ProgramResult(1)
    }
}

/**
 * A unified diff of two texts, line-based, with three lines of context; files are small, so an
 * O(n*m) LCS is fine.
 */
internal fun unifiedDiff(path: String, before: String, after: String): String {
    val a = before.lines().let { if (before.endsWith("\n")) it.dropLast(1) else it }
    val b = after.lines().let { if (after.endsWith("\n")) it.dropLast(1) else it }
    val lcs = Array(a.size + 1) { IntArray(b.size + 1) }
    for (i in a.indices.reversed()) for (j in b.indices.reversed()) lcs[i][j] =
        if (a[i] == b[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
    // ops: ' ' equal, '-' removed, '+' added, each with its line text
    val ops = mutableListOf<Pair<Char, String>>()
    var i = 0
    var j = 0
    while (i < a.size || j < b.size) {
        when {
            i < a.size && j < b.size && a[i] == b[j] -> {
                ops += ' ' to a[i]
                i++
                j++
            }
            j < b.size && (i == a.size || lcs[i][j + 1] >= lcs[i + 1][j]) -> {
                ops += '+' to b[j]
                j++
            }
            else -> {
                ops += '-' to a[i]
                i++
            }
        }
    }
    val out = StringBuilder("--- $path\n+++ $path\n")
    val context = 3
    var k = 0
    while (k < ops.size) {
        if (ops[k].first == ' ') {
            k++
            continue
        }
        val start = maxOf(0, k - context)
        var end = k
        var run = 0
        while (end < ops.size && run <= context * 2) {
            if (ops[end].first == ' ') run++ else run = 0
            end++
        }
        end = minOf(ops.size, if (run > context) end - (run - context) else end)
        var oldStart = 1
        var newStart = 1
        for (t in 0 until start) {
            if (ops[t].first != '+') oldStart++
            if (ops[t].first != '-') newStart++
        }
        val oldLen = ops.subList(start, end).count { it.first != '+' }
        val newLen = ops.subList(start, end).count { it.first != '-' }
        out.append("@@ -$oldStart,$oldLen +$newStart,$newLen @@\n")
        for (t in start until end) out.append(ops[t].first).append(ops[t].second).append('\n')
        k = end
    }
    return out.toString()
}
