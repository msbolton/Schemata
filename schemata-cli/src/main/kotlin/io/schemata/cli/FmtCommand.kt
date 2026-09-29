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
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

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
    private val reporting by FormatOptions()
    private val inputs by argument("PATHS").path(mustExist = true).multiple(required = true)

    override fun run() {
        val sources = loadSources(inputs)
        var failed = false
        var differs = false
        // Under json, stdout carries only the diagnostic documents; everything else goes to stderr.
        val json = reporting.format == Format.JSON
        for (loaded in sources) {
            // Loading replaces malformed bytes; writing that text back would destroy them.
            val content = strictUtf8(File(loaded.path).readBytes())
            if (content == null) {
                failed = true
                echo("${loaded.path}: not valid UTF-8; left unchanged", err = true)
                continue
            }
            val s = SourceInput(loaded.path, content)
            when (val r = Formatter.format(s.content, s.path)) {
                is FormatResult.Failed -> {
                    failed = true
                    val report =
                        Report.of(
                            PipelineResult(r.diagnostics, emptyList()),
                            strict = false,
                            checkOnly = true,
                        )
                    // emit exits the command on a non-zero report; every source still needs a
                    // chance to format, so the exit is deferred to the loop's own final throw.
                    try {
                        emit(this, report, Sources.of(listOf(s)), reporting, out = "")
                    } catch (_: ProgramResult) {}
                }
                is FormatResult.Formatted ->
                    if (r.text != s.content) {
                        differs = true
                        if (check)
                            echo(
                                unifiedDiff(s.path, s.content, r.text),
                                trailingNewline = false,
                                err = json,
                            )
                        else {
                            File(s.path).writeText(r.text)
                            echo("formatted ${s.path}", err = json)
                        }
                    }
            }
        }
        if (failed || (check && differs)) throw ProgramResult(1)
    }
}

/** [bytes] as UTF-8 text, or null when they are not valid UTF-8. */
private fun strictUtf8(bytes: ByteArray): String? =
    try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

/**
 * A unified diff of two texts, line-based, with three lines of context; files are small, so an
 * O(n*m) LCS is fine. Line endings are compared after turning CRLF and CR into LF; when that is the
 * only difference, the diff says so in one line instead of a hunk. A text without a final newline
 * gets the usual `\ No newline at end of file` after its last line, and that line counts as
 * changed.
 */
internal fun unifiedDiff(path: String, before: String, after: String): String {
    val out = StringBuilder("--- $path\n+++ $path\n")
    val oldText = before.replace("\r\n", "\n").replace('\r', '\n')
    if (oldText != before) out.append("(line endings: CRLF -> LF)\n")
    if (oldText == after) return out.toString()
    val a = oldText.lines().let { if (oldText.endsWith("\n")) it.dropLast(1) else it }
    val b = after.lines().let { if (after.endsWith("\n")) it.dropLast(1) else it }
    // The index of a text's last line when it has no final newline, else -1.
    val aOpen = if (oldText.isEmpty() || oldText.endsWith("\n")) -1 else a.lastIndex
    val bOpen = if (after.isEmpty() || after.endsWith("\n")) -1 else b.lastIndex
    fun same(i: Int, j: Int) = a[i] == b[j] && (i == aOpen) == (j == bOpen)
    val lcs = Array(a.size + 1) { IntArray(b.size + 1) }
    for (i in a.indices.reversed()) for (j in b.indices.reversed()) lcs[i][j] =
        if (same(i, j)) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
    // ops: ' ' equal, '-' removed, '+' added, each with its line text and whether that line is the
    // last of a text with no final newline
    val ops = mutableListOf<Triple<Char, String, Boolean>>()
    var i = 0
    var j = 0
    while (i < a.size || j < b.size) {
        when {
            i < a.size && j < b.size && same(i, j) -> {
                ops += Triple(' ', a[i], i == aOpen)
                i++
                j++
            }
            i < a.size && (j == b.size || lcs[i + 1][j] >= lcs[i][j + 1]) -> {
                ops += Triple('-', a[i], i == aOpen)
                i++
            }
            else -> {
                ops += Triple('+', b[j], j == bOpen)
                j++
            }
        }
    }
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
        for (t in start until end) {
            out.append(ops[t].first).append(ops[t].second).append('\n')
            if (ops[t].third) out.append("\\ No newline at end of file\n")
        }
        k = end
    }
    return out.toString()
}
