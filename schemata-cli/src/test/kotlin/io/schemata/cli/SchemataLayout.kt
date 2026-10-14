package io.schemata.cli

/**
 * One `.schemata` file cut where a blank line is followed by an unindented line, where the
 * formatter puts one between top-level blocks: the blocks holding the `schema` line and the
 * `import` lines are the [head], a block with a `service` line is one of the [services], kept in
 * order, and every other block is one declaration with its doc, annotations, and everything nested
 * in it, the [declarations] sorted. Two imports that list their declarations in another order have
 * the same layout.
 */
internal data class SchemataLayout(
    val head: String,
    val declarations: String,
    val services: String,
) {
    companion object {
        fun of(text: String): SchemataLayout {
            val blocks = text.trimEnd('\n').split(Regex("\n\n(?=\\S)"))
            val head =
                blocks.takeWhile { block ->
                    block.lines().any { it.startsWith("schema ") } ||
                        block.lines().all { it.startsWith("import ") }
                }
            val (services, declarations) =
                blocks.drop(head.size).partition { block ->
                    block.lines().any { it.startsWith("service ") }
                }
            return SchemataLayout(
                head.joinToString("\n\n"),
                declarations.sorted().joinToString("\n\n"),
                services.joinToString("\n\n"),
            )
        }
    }
}
