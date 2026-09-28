package io.schemata.cli.report

import io.schemata.cli.SourceInput

/** Source text by the path diagnostics print, split once so excerpts never re-read disk. */
class Sources(private val lines: Map<String, List<String>>) {
    /** The 1-based [number]th line of [file], or null when the file or line is unknown. */
    fun line(file: String, number: Int): String? = lines[file]?.getOrNull(number - 1)

    companion object {
        val EMPTY = Sources(emptyMap())

        fun of(inputs: List<SourceInput>): Sources =
            Sources(inputs.associate { it.path to it.content.lines() })
    }
}
