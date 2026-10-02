package io.schemata.lsp.workspace

import java.nio.file.Path
import java.nio.file.Paths

/** One independently analysed group of files: a directory, or a root's whole subtree. */
data class SetKey(val directory: String, val recursive: Boolean)

/**
 * Which set a file belongs to: the subtree of the nearest configured root that contains it, or,
 * with no such root, its own directory alone. A symbol never resolves across sets.
 */
class SchemaSets(roots: List<Path>) {
    private val roots: List<Path> =
        roots.map { it.toAbsolutePath().normalize() }.sortedByDescending { it.nameCount }

    fun keyOf(path: String): SetKey {
        val file = Paths.get(path)
        val root = roots.firstOrNull { file.startsWith(it) }
        return if (root != null) SetKey(root.toString(), recursive = true)
        else SetKey(file.parent?.toString() ?: file.toString(), recursive = false)
    }

    fun contains(key: SetKey, path: String): Boolean = keyOf(path) == key
}
