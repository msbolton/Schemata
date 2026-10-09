package io.schemata.cli

import com.github.ajalt.clikt.testing.test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class CheckCommandTest {
    private val customers =
        """
        schema shop.customers

        model Customer { #1 id uuid { id }  #2 name string }
        """
            .trimIndent()

    // JUnit removes this directory after each test, so the sources and outputs never outlive it.
    @TempDir lateinit var tmp: Path

    private fun source(text: String): Path {
        val dir = Files.createTempDirectory(tmp, "schemata-check")
        val src = dir.resolve("src").createDirectories()
        src.resolve("customers.schemata").writeText(text)
        return src
    }

    @Test
    fun `check reports what compile reports and writes nothing`() {
        val src = source(customers)
        val check = CheckCommand().test("$src")
        assertFalse(Files.exists(src.parent.resolve("out")))
        val compile = CompileCommand().test("--out ${src.parent.resolve("out")} $src")
        assertEquals(compile.statusCode, check.statusCode)
        // The whole report, excerpts and counts included, is compile's less the lines naming what
        // it wrote.
        assertEquals(
            compile.stderr.lines().filterNot { it.startsWith("wrote ") }.joinToString("\n"),
            check.stderr,
        )
        assertFalse(check.stderr.contains("wrote "), check.stderr)
    }

    @Test
    fun `check exits 2 on warnings, 1 under strict, and 0 when clean`() {
        val src = source(customers)
        assertEquals(2, CheckCommand().test("--target proto $src").statusCode)
        assertEquals(1, CheckCommand().test("--target proto --strict $src").statusCode)
        val clean =
            source(
                "schema shop.customers\n" +
                    "\n" +
                    "model Customer { #1 id uuid { id }  #2 name string }\n"
            )
        val sqlOnly = CheckCommand().test("--target sql $clean")
        assertEquals(0, sqlOnly.statusCode, sqlOnly.stderr)
        assertTrue(sqlOnly.stderr.contains("no diagnostics"), sqlOnly.stderr)
    }

    @Test
    fun `check --format json has no written or skipped entries`() {
        val src = source(customers)
        val result = CheckCommand().test("--format json $src")
        assertEquals("", result.stderr)
        assertTrue(result.stdout.contains("\"written\": []"), result.stdout)
        assertTrue(result.stdout.contains("\"skipped\": []"), result.stdout)
    }
}
