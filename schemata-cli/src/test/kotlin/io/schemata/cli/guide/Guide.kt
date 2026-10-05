package io.schemata.cli.guide

import io.schemata.cli.SourceInput
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.fail

/** Shared access to the committed guide and the repository root from the CLI module's tests. */
object Guide {
    val root: File = File("..").canonicalFile
    val dir: File = File(root, "guide")
    private val update = System.getenv("SCHEMATA_GOLDEN_UPDATE") == "1"

    /** Compares [actual] to [file], rewriting it under SCHEMATA_GOLDEN_UPDATE=1. */
    fun golden(file: File, actual: String) {
        if (update) {
            file.parentFile.mkdirs()
            file.writeText(actual)
            return
        }
        if (!file.isFile)
            fail("${file.path} does not exist; run with SCHEMATA_GOLDEN_UPDATE=1 to create it")
        assertEquals(
            file.readText(),
            actual,
            "${file.path} is stale; run with SCHEMATA_GOLDEN_UPDATE=1 to regenerate",
        )
    }

    /** A fenced block: the info string after the backticks and the body. */
    data class Block(val info: String, val body: String, val line: Int)

    /** Every ``` fenced block in [text] whose info string starts with [language]. */
    fun blocks(text: String, language: String): List<Block> {
        val out = mutableListOf<Block>()
        val lines = text.lines()
        var i = 0
        while (i < lines.size) {
            val open = Regex("^```(\\S+)(.*)$").find(lines[i])
            if (open != null && open.groupValues[1] == language) {
                val info = (open.groupValues[1] + open.groupValues[2]).trim()
                val start = i + 1
                var j = start
                while (j < lines.size && lines[j] != "```") j++
                out += Block(info, lines.subList(start, j).joinToString("\n"), start + 1)
                i = j + 1
            } else i++
        }
        return out
    }

    /**
     * The sources a `schemata` block holds: several files separated by a line `---
     * <name>.schemata`, or one file `example.schemata` when it has no such line.
     */
    fun sources(body: String): List<SourceInput> {
        val parts = mutableListOf<Pair<String, StringBuilder>>()
        for (line in body.lines()) {
            val m = Regex("^--- (\\S+\\.schemata)$").find(line)
            if (m != null) parts += m.groupValues[1] to StringBuilder()
            else {
                if (parts.isEmpty()) parts += "example.schemata" to StringBuilder()
                parts.last().second.appendLine(line)
            }
        }
        return parts.map { SourceInput(it.first, it.second.toString()) }
    }

    /**
     * Relative link targets in Markdown [text]: `[x](path)` where path has no scheme or `#` prefix.
     */
    fun relativeLinks(text: String): List<String> =
        Regex("\\]\\(([^)\\s]+)\\)")
            .findAll(text)
            .map { it.groupValues[1] }
            .filterNot {
                it.startsWith("http://") ||
                    it.startsWith("https://") ||
                    it.startsWith("#") ||
                    it.startsWith("mailto:")
            }
            .map { it.substringBefore('#') }
            .toList()
}
