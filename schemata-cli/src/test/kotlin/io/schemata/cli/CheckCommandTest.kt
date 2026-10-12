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

    private val mutual =
        """
        schema shop.orders

        model Order { #1 id int64 { id }  #2 customer Customer { embed } }

        model Customer { #1 id int64 { id }  #2 orders Order[] { embed } }
        """
            .trimIndent()

    @Test
    fun `--proto-layout type reports the shared-file note`() {
        val src = source(mutual)
        val result = CheckCommand().test("--target proto --proto-layout type $src")
        assertTrue(result.stderr.contains("SCH2001"), result.stderr)
        assertTrue(result.stderr.contains("share shop/orders/"), result.stderr)
        val plain = CheckCommand().test("--target proto $src")
        assertFalse(plain.stderr.contains("SCH2001"), plain.stderr)
    }

    @Test
    fun `check reads the same file`() {
        val src = source("schema s\n\nmodel R { #1 x bool }\n")
        val toml = src.parent.resolve("schemata.toml")
        toml.writeText("[proto]\njava_package = \"com.example-{schema}\"\n")
        val result = CheckCommand().test("--config $toml --target proto $src")
        assertEquals(1, result.statusCode, result.stderr)
        assertTrue(
            result.stderr.contains(
                "error[SCH2007] (proto): schema 's': @proto(java_package: \"com.example-s\") " +
                    "is not a valid Java package"
            ),
            result.stderr,
        )
        // The value came from the file, but the target reports it where the schema is declared.
        assertTrue(
            result.stderr.contains(" --> ${src.resolve("customers.schemata")}:1:1"),
            result.stderr,
        )
        val plain = CheckCommand().test("--no-config --target proto $src")
        assertEquals(0, plain.statusCode, plain.stderr)
    }
}
