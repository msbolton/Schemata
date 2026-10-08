package io.schemata.cli

import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.io.TempDir

class SourceSetTest {
    // JUnit removes this directory after each test.
    @TempDir lateinit var tmp: java.nio.file.Path

    @Test
    fun `expands directories recursively, sorts, and deduplicates`() {
        val root = Files.createTempDirectory(tmp, "schemata-src")
        root.resolve("b.schemata").writeText("schema b")
        root.resolve("sub").createDirectories()
        root.resolve("sub/a.schemata").writeText("schema a")
        root.resolve("sub/notes.txt").writeText("ignored")

        val loaded = SourceSet.load(listOf(root, root.resolve("b.schemata")))

        assertEquals(
            listOf(
                root.resolve("b.schemata").toString(),
                root.resolve("sub/a.schemata").toString(),
            ),
            loaded.map { it.path },
        )
        assertEquals(listOf("schema b", "schema a"), loaded.map { it.content })
    }

    @Test
    fun `keeps relative paths relative and normalized`() {
        val cwd = java.nio.file.Path.of("").toAbsolutePath()
        val dir = Files.createTempDirectory(cwd, "rel")
        try {
            dir.resolve("x.schemata").writeText("schema x")
            val relative = cwd.relativize(dir).resolve("./x.schemata")
            val loaded = SourceSet.load(listOf(relative))
            assertEquals(cwd.relativize(dir).resolve("x.schemata").toString(), loaded.single().path)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a file named beside its directory keeps the path under the directory whatever the argument order`() {
        val root = Files.createTempDirectory(tmp, "schemata-order")
        root.resolve("sub").createDirectories()
        root.resolve("sub/a.proto").writeText("message A {}")
        val file = root.resolve("sub/a.proto")

        val fileFirst = ProtoSet.load(listOf(file, root))
        val dirFirst = ProtoSet.load(listOf(root, file))

        assertEquals(listOf("sub/a.proto"), fileFirst.map { it.relative })
        assertEquals(fileFirst, dirFirst)
    }

    @Test
    fun `an xsd is read in the encoding its declaration names and located the same way`() {
        val root = Files.createTempDirectory(tmp, "schemata-encoding")
        val text = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><schema>caf\u00e9</schema>"
        root.resolve("a.xsd").writeBytes(text.toByteArray(Charsets.ISO_8859_1))

        assertEquals(listOf(text), XsdSet.load(listOf(root)).map { it.content })
        assertEquals(text, locate(root.resolve("a.xsd").toString())?.content)
    }
}
