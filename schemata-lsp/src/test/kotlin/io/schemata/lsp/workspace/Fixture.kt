package io.schemata.lsp.workspace

import io.schemata.core.annotations.AnnotationRegistry
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/** Files written under a temporary directory and opened in a workspace, for query tests. */
class Fixture(private val dir: Path) {
    val workspace = Workspace(AnnotationRegistry.CORE)
    val queries = Queries(workspace)
    private val texts = mutableMapOf<String, String>()

    /** Writes [text] to [relative], opens it, and returns its absolute path. */
    fun open(relative: String, text: String): String {
        val path = write(relative, text)
        workspace.open(path, text)
        return path
    }

    /** Writes [text] to [relative] without opening it. */
    fun write(relative: String, text: String): String {
        val file = dir.resolve(relative)
        file.parent.createDirectories()
        file.writeText(text)
        val path = file.toAbsolutePath().normalize().toString()
        texts[path] = text
        return path
    }

    fun text(path: String): String = texts.getValue(path)

    /** The position of the [occurrence]-th [needle] in [path], plus [offset] characters. */
    fun at(path: String, needle: String, occurrence: Int = 0, offset: Int = 0): TextPosition {
        val text = text(path)
        var index = -1
        repeat(occurrence + 1) {
            index = text.indexOf(needle, index + 1)
            require(index >= 0) { "'$needle' occurrence $occurrence not in $path" }
        }
        val target = index + offset
        val line = text.substring(0, target).count { it == '\n' }
        val lineStart = text.lastIndexOf('\n', target - 1) + 1
        return TextPosition(line, target - lineStart)
    }

    /** The range covering the [occurrence]-th [needle] in [path]. */
    fun range(path: String, needle: String, occurrence: Int = 0): TextRange =
        TextRange(at(path, needle, occurrence), at(path, needle, occurrence, needle.length))

    fun location(path: String, needle: String, occurrence: Int = 0): Location =
        Location(path, range(path, needle, occurrence))
}
