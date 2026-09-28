package io.schemata.cli

import com.github.ajalt.clikt.testing.test
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompileCommandTest {
    private val orders =
        """
        namespace shop.orders

        record User {
          @sql(key) id:    uuid
          email: string?
          name:  string
          age:   int32
        }
        """
            .trimIndent()

    private val customers =
        """
        namespace shop.customers

        record Customer {
          @sql(key) id:   uuid
          name: string
        }
        """
            .trimIndent()

    private fun tempSources(
        vararg files: Pair<String, String>
    ): Pair<java.nio.file.Path, java.nio.file.Path> {
        val dir = Files.createTempDirectory("schemata-cli")
        val src = dir.resolve("src").createDirectories()
        files.forEach { (name, text) -> src.resolve(name).writeText(text) }
        return src to dir.resolve("out")
    }

    @Test
    fun `compiles a directory to one file per namespace per target and exits 2 on warnings`() {
        val (src, out) = tempSources("orders.schemata" to orders, "customers.schemata" to customers)

        val result = CompileCommand().test("--target proto,sql --out $out $src")

        assertEquals(2, result.statusCode, result.stderr)
        assertTrue(out.resolve("proto/shop/orders.proto").readText().contains("message User"))
        assertTrue(
            out.resolve("proto/shop/customers.proto").readText().contains("message Customer")
        )
        assertTrue(
            out.resolve("sql/shop/orders.sql")
                .readText()
                .contains("CREATE TABLE \"orders\".\"user\"")
        )
        assertTrue(
            out.resolve("sql/shop/customers.sql")
                .readText()
                .contains("CREATE TABLE \"customers\".\"customer\"")
        )
        assertTrue(result.stderr.contains("warning[SCH2001] (lossy) (proto): "), result.stderr)
        assertTrue(
            result.stderr.contains(" --> ${src.resolve("orders.schemata")}:4:3"),
            result.stderr,
        )
        assertTrue(result.stderr.contains("wrote 2 files to $out/proto"), result.stderr)
        assertTrue(result.stderr.contains("wrote 2 files to $out/sql"), result.stderr)
    }

    @Test
    fun `--target defaults to every registered target`() {
        val (src, out) = tempSources("customers.schemata" to customers)
        val result = CompileCommand().test("--out $out $src")
        assertTrue(result.statusCode in setOf(0, 2), result.stderr)
        assertTrue(Files.exists(out.resolve("proto/shop/customers.proto")))
        assertTrue(Files.exists(out.resolve("sql/shop/customers.sql")))
    }

    @Test
    fun `writes the target that lowered cleanly and skips the one that errored`() {
        val (src, out) =
            tempSources(
                "p.schemata" to
                    """
                    namespace p

                    record Orphan { #1 name: string }

                    record R { @sql(key) #1 id: uuid }
                    """
                        .trimIndent()
            )
        val result = CompileCommand().test("--out $out $src")
        assertEquals(1, result.statusCode, result.stderr)
        assertTrue(Files.exists(out.resolve("proto/p.proto")), result.stderr)
        assertFalse(Files.exists(out.resolve("sql")), result.stderr)
        assertTrue(result.stderr.contains("sql: not written (1 error)"), result.stderr)
    }

    @Test
    fun `the worked example compiles to proto with exit 2 and 17 lossy warnings`() {
        val corpus = java.io.File("src/test/resources/corpus/worked-example")
        val (src, out) =
            tempSources(
                "orders.schemata" to corpus.resolve("orders.schemata").readText(),
                "customers.schemata" to corpus.resolve("customers.schemata").readText(),
            )
        val result = CompileCommand().test("--target proto --out $out $src")
        assertEquals(2, result.statusCode, result.stderr)
        assertEquals(
            corpus.resolve("expected/proto/shop/orders.proto").readText(),
            out.resolve("proto/shop/orders.proto").readText(),
        )
        assertEquals(
            17,
            result.stderr.lines().count { it.startsWith("warning[SCH2001]") },
            result.stderr,
        )
        assertTrue(result.stderr.contains("0 errors, 17 warnings"), result.stderr)
    }

    @Test
    fun `exits 1 and shows the excerpt on a syntax error`() {
        val (src, out) = tempSources("bad.schemata" to "namespace a\nrecord R { x uuid }")
        val result = CompileCommand().test("--target proto --out $out $src")
        assertEquals(1, result.statusCode)
        assertTrue(result.stderr.contains("error[SCH0001]: "), result.stderr)
        assertTrue(result.stderr.contains("2 | record R { x uuid }"), result.stderr)
        assertFalse(Files.exists(out))
    }

    @Test
    fun `a syntax error at end of file does not crash the human renderer`() {
        // The trailing extraneous-input diagnostic for this file spans an empty line at endColumn
        // 0, which used to make HumanRenderer.column() call String.take(-1) and throw.
        val (src, out) =
            tempSources(
                "bad.schemata" to
                    "namespace shop.orders\n\nrecord OrderLine {\n  #1 record: string\n}\n"
            )
        val result = CompileCommand().test("--target proto --out $out $src")
        assertEquals(1, result.statusCode, result.stderr)
        assertTrue(result.stderr.contains("error[SCH0001]"), result.stderr)
        assertFalse(result.stderr.contains("Exception"), result.stderr)
    }

    @Test
    fun `--strict rejects implicit ordinals and promotes warnings`() {
        val (src, out) = tempSources("s.schemata" to "namespace s\nrecord R { x: bool }")
        val result = CompileCommand().test("--target proto --strict --out $out $src")
        assertEquals(1, result.statusCode)
        assertTrue(
            result.stderr.contains("error[SCH1014]: field 'x' has no explicit ordinal (--strict)"),
            result.stderr,
        )
        assertFalse(Files.exists(out.resolve("proto")))
    }

    @Test
    fun `--format json writes one document to stdout and nothing to stderr`() {
        val (src, out) = tempSources("customers.schemata" to customers)
        val result = CompileCommand().test("--target proto --format json --out $out $src")
        assertEquals("", result.stderr)
        assertTrue(result.stdout.startsWith("{\n  \"diagnostics\": ["), result.stdout)
        assertTrue(result.stdout.contains("\"exitCode\": ${result.statusCode}"), result.stdout)
        assertTrue(
            result.stdout.contains("\"path\":\"$out/proto/shop/customers.proto\""),
            result.stdout,
        )
    }

    @Test
    fun `--color always emits escapes and never does not`() {
        val (src, out) = tempSources("orders.schemata" to orders)
        val colored = CompileCommand().test("--target proto --color always --out $out $src")
        assertTrue(colored.stderr.contains("\u001b[33mwarning\u001b[0m"), colored.stderr)
        val plain = CompileCommand().test("--target proto --color never --out $out $src")
        assertFalse(plain.stderr.contains("\u001b["), plain.stderr)
    }

    @Test
    fun `rejects an unknown target`() {
        val (src, _) = tempSources("a.schemata" to "namespace a")
        val result = CompileCommand().test("--target avro $src")
        assertEquals(1, result.statusCode)
        assertTrue(result.stderr.contains("unknown target 'avro'"), result.stderr)
    }

    @Test
    fun `rejects a directory with no schemata files`() {
        val dir = Files.createTempDirectory("schemata-empty")
        val result = CompileCommand().test("--target proto $dir")
        assertEquals(1, result.statusCode)
        assertTrue(result.stderr.contains("no .schemata files"), result.stderr)
    }

    @Test
    fun `duplicate --target names lower once`() {
        val (src, out) = tempSources("customers.schemata" to customers)
        val duplicated = CompileCommand().test("--target proto,proto --out $out $src")
        val (src2, out2) = tempSources("customers.schemata" to customers)
        val single = CompileCommand().test("--target proto --out $out2 $src2")
        assertEquals(
            1,
            duplicated.stderr.lines().count {
                it.contains("wrote") && it.contains("to $out/proto")
            },
            duplicated.stderr,
        )
        assertEquals(single.statusCode, duplicated.statusCode)
        assertEquals(
            single.stderr.lines().count { it.startsWith("warning[SCH2001]") },
            duplicated.stderr.lines().count { it.startsWith("warning[SCH2001]") },
        )
    }

    @Test
    fun `a write failure is reported as a one-line message with exit 1`() {
        val (src, out) = tempSources("customers.schemata" to customers)
        val blocker = out.parent.resolve("blocker")
        blocker.writeText("not a directory")
        val result = CompileCommand().test("--target proto --out ${blocker.resolve("sub")} $src")
        assertEquals(1, result.statusCode, result.stderr)
        assertTrue(result.stderr.contains("cannot write"), result.stderr)
        assertFalse(result.stderr.contains("at io.schemata"), result.stderr)
    }

    @Test
    fun `--strict promotes lossy warnings so the target is skipped`() {
        val (src, out) =
            tempSources(
                "e.schemata" to
                    """
                    namespace e

                    enum Color { #1 red, #2 green }

                    record R { @sql(key) #1 id: uuid  #2 color: Color }
                    """
                        .trimIndent()
            )
        val result = CompileCommand().test("--target proto --strict --out $out $src")
        assertEquals(1, result.statusCode, result.stderr)
        assertTrue(
            result.stderr.contains("error[SCH2001] (lossy) (proto) [promoted]"),
            result.stderr,
        )
        // The enum's synthesized zero value and the uuid field's string lowering are both lossy,
        // so proto reports two promoted errors for this fixture, not one.
        assertTrue(result.stderr.contains("proto: not written (2 errors)"), result.stderr)
        assertFalse(Files.exists(out.resolve("proto")))
    }
}
