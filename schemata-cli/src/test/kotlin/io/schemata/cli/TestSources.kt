package io.schemata.cli

import java.io.File

/** The `*.schemata` files directly in one directory, by file name, as the compiler takes them. */
internal object TestSources {
    fun of(dir: File): List<SourceInput> =
        dir.listFiles { f -> f.extension == "schemata" }!!
            .sortedBy { it.name }
            .map { SourceInput(it.name, it.readText()) }
}
