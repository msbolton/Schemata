package io.schemata.cli.guide

import io.schemata.cli.SourceInput
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Shared access to the wiki and the repository root from the CLI module's tests. The wiki is the
 * `wiki` submodule, or the checkout `-Pschemata.wiki=<dir>` names.
 */
object Guide {
    val root: File = File("..").canonicalFile
    val dir: File =
        (System.getProperty("schemata.wiki")?.let(::File) ?: File(root, "wiki")).canonicalFile
    private val update = System.getenv("SCHEMATA_GOLDEN_UPDATE") == "1"

    /** Where the README links to a wiki page: this prefix, then the page name. */
    const val WIKI_URL = "https://github.com/msbolton/Schemata/wiki/"

    /** Every wiki page, sorted by name; fails when the wiki is not checked out. */
    val pages: List<File> by lazy {
        if (!File(dir, "Home.md").isFile)
            fail("no wiki at ${dir.path}; run `git submodule update --init wiki`")
        dir.listFiles { f -> f.extension == "md" }!!.sortedBy { it.name }
    }

    /**
     * The wiki pages whose `schemata` blocks compile on their own. Worked-Examples quotes runs of
     * lines from the example files instead, which `GuideExamplesTest` checks.
     */
    val referencePages: List<File> by lazy { pages.filterNot { it.name == "Worked-Examples.md" } }

    /** The wiki page [name], as a link names it. */
    fun page(name: String): File = File(dir, "$name.md")

    /**
     * The file a relative link [path] in [from] points at: a wiki page links to another by its name
     * alone, and the README links to files of the repository.
     */
    fun resolve(from: File, path: String): File =
        if (from.parentFile.canonicalFile == dir && '/' !in path && '.' !in path) page(path)
        else File(from.parentFile, path).canonicalFile

    /**
     * Every link into the wiki in [text], inline `[x](<WIKI_URL>Page#fragment)` or a reference
     * definition `[x]: <WIKI_URL>Page#fragment`, as the page name and its fragment.
     */
    fun wikiReferences(text: String): List<CrossReference> =
        Regex(
                "(?m)(?:\\]\\(|^\\[[^]]+]: )" +
                    Regex.escape(WIKI_URL) +
                    "([^)\\s#]+)(?:#([^)\\s]+))?"
            )
            .findAll(text)
            .map { CrossReference(it.groupValues[1], it.groupValues[2]) }
            .toList()

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

    /** A link with a `#fragment`: the file part (empty for the same file) and the fragment. */
    data class CrossReference(val path: String, val fragment: String)

    /** Every `[x](path#fragment)` in [text] that stays inside the repository. */
    fun crossReferences(text: String): List<CrossReference> =
        Regex("\\]\\(([^)\\s#]*)#([^)\\s]+)\\)")
            .findAll(text)
            .map { CrossReference(it.groupValues[1], it.groupValues[2]) }
            .filterNot { it.path.startsWith("http://") || it.path.startsWith("https://") }
            .toList()

    /**
     * The anchors GitHub gives the headings of [text]: lower-cased, anything but letters, digits,
     * `_`, `-`, and spaces dropped, spaces turned to `-`, and a repeated heading numbered `-1`,
     * `-2`. Headings inside fenced blocks (a `#` comment) are not headings.
     */
    fun headingAnchors(text: String): Set<String> {
        val seen = mutableMapOf<String, Int>()
        val anchors = mutableSetOf<String>()
        var inFence = false
        for (line in text.lines()) {
            if (line.startsWith("```")) inFence = !inFence
            if (inFence) continue
            val title = Regex("^#{1,6} +(.*?)\\s*$").find(line)?.groupValues?.get(1) ?: continue
            val base = title.lowercase().replace(Regex("[^\\p{L}\\p{N}_ -]"), "").replace(' ', '-')
            val count = seen.merge(base, 1, Int::plus)!! - 1
            anchors += if (count == 0) base else "$base-$count"
        }
        return anchors
    }
}
