package io.schemata.cli

import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class SourceSetTest {
    @Test
    fun `expands directories recursively, sorts, and deduplicates`() {
        val root = Files.createTempDirectory("schemata-src")
        root.resolve("b.schemata").writeText("namespace b")
        root.resolve("sub").createDirectories()
        root.resolve("sub/a.schemata").writeText("namespace a")
        root.resolve("sub/notes.txt").writeText("ignored")

        val loaded = SourceSet.load(listOf(root, root.resolve("b.schemata")))

        assertEquals(
            listOf(
                root.resolve("b.schemata").toString(),
                root.resolve("sub/a.schemata").toString(),
            ),
            loaded.map { it.path },
        )
        assertEquals(listOf("namespace b", "namespace a"), loaded.map { it.content })
    }

    @Test
    fun `keeps relative paths relative and normalized`() {
        val cwd = java.nio.file.Path.of("").toAbsolutePath()
        val dir = Files.createTempDirectory(cwd, "rel")
        try {
            dir.resolve("x.schemata").writeText("namespace x")
            val relative = cwd.relativize(dir).resolve("./x.schemata")
            val loaded = SourceSet.load(listOf(relative))
            assertEquals(cwd.relativize(dir).resolve("x.schemata").toString(), loaded.single().path)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
