package io.schemata.cli

import com.github.ajalt.clikt.testing.test
import io.schemata.cli.guide.Guide
import io.schemata.config.ConfigLoader
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir

class InitCommandTest {
    @TempDir lateinit var tmp: Path

    private fun write(path: String, text: String = "schema s\n") =
        tmp.resolve(path).apply {
            parent.createDirectories()
            writeText(text)
        }

    private fun loads(file: Path) {
        val loaded = ConfigLoader.load(file, Pipeline.annotations, Pipeline.targets.map { it.name })
        assertEquals(emptyList(), loaded.diagnostics.map { it.message })
    }

    @Test
    fun `init writes the starter file and it loads`() {
        write("schemas/shop/a.schemata")
        write("models/b.schemata")
        write(".git/c.schemata")
        write("out/d.schemata")
        write("docs/readme.md", "x")
        val result = InitCommand().test(tmp.toString())
        assertEquals(0, result.statusCode, result.stderr)
        val file = tmp.resolve("schemata.toml")
        assertEquals("wrote ${displayPath(file)}\n", result.stdout)
        Guide.golden(File("src/test/resources/init/schemata.toml"), file.readText())
        assertTrue("paths = [\"models\", \"schemas\"]" in file.readText(), file.readText())
        loads(file)
    }

    @Test
    fun `a schema file in the directory itself gives the directory`() {
        write("a.schemata")
        write("schemas/b.schemata")
        InitCommand().test(tmp.toString())
        val text = tmp.resolve("schemata.toml").readText()
        assertTrue(text.lines().any { it.startsWith("paths = [\".\"]") }, text)
    }

    @Test
    fun `a directory with no visible schema files writes sources commented`() {
        write(".hidden/a.schemata")
        write("out/b.schemata")
        InitCommand().test(tmp.toString())
        val file = tmp.resolve("schemata.toml")
        val text = file.readText()
        assertTrue("# [sources]\n# paths = [\"schemas\"]" in text, text)
        assertTrue(text.lines().none { it.startsWith("paths") || it == "[sources]" }, text)
        loads(file)
    }

    @Test
    fun `an existing file is left alone`() {
        val file = write("schemata.toml", "[compile]\nstrict = true\n")
        val result = InitCommand().test(tmp.toString())
        assertEquals(1, result.statusCode)
        assertEquals("schemata.toml already exists in ${displayPath(tmp)}\n", result.stderr)
        assertEquals("[compile]\nstrict = true\n", file.readText())
    }

    @Test
    fun `every default the starter file shows loads when uncommented`() {
        write("schemas/a.schemata")
        InitCommand().test(tmp.toString())
        val file = tmp.resolve("schemata.toml")
        // a run-table key with a default is written `# key = value   # note`; uncommenting it must
        // give a value the loader accepts
        val keys = Regex("^# ([a-z_]+) = (.+?)   # ")
        val withDefaults =
            setOf(
                "targets",
                "out",
                "strict",
                "proto_layout",
                "include",
                "allow_destructive",
                "format",
                "color",
                "exclude",
            )
        val uncommented =
            file
                .readText()
                .lines()
                .takeWhile { !it.startsWith("# Defaults for the schema line") }
                .joinToString("\n") { line ->
                    val m = keys.find(line)
                    if (m != null && m.groupValues[1] in withDefaults) line.removePrefix("# ")
                    else line
                }
        file.writeText(uncommented + "\n")
        loads(file)
    }

    @Test
    fun `an unreadable directory holds no schema file`() {
        write("schemas/a.schemata")
        val locked = tmp.resolve("pgdata").also { it.createDirectories() }.toFile()
        write("pgdata/x/b.schemata")
        locked.setReadable(false, false)
        try {
            assumeTrue(!Files.isReadable(locked.toPath()), "directory is still readable")
            val result = InitCommand().test(tmp.toString())
            assertEquals(0, result.statusCode, result.stderr)
            assertTrue("paths = [\"schemas\"]" in tmp.resolve("schemata.toml").readText())
        } finally {
            locked.setReadable(true, false)
        }
    }

    @Test
    fun `a directory that cannot be written reports one line`() {
        write("schemas/a.schemata")
        val dir = tmp.toFile()
        dir.setWritable(false, false)
        try {
            assumeTrue(!Files.isWritable(dir.toPath()), "directory is still writable")
            val result = InitCommand().test(tmp.toString())
            assertEquals(1, result.statusCode)
            val file = tmp.resolve("schemata.toml")
            assertTrue(
                result.stderr.startsWith("cannot write ${displayPath(file)}: "),
                result.stderr,
            )
            assertEquals(1, result.stderr.trim().lines().size, result.stderr)
        } finally {
            dir.setWritable(true, false)
        }
    }
}
