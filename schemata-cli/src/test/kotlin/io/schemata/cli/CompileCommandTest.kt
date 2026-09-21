package io.schemata.cli

import com.github.ajalt.clikt.testing.test
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CompileCommandTest {
    private val orders =
        """
        namespace shop.orders

        record User {
          id:    uuid
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
          id:   uuid
          name: string
        }
        """
            .trimIndent()

    @Test
    fun `compiles a directory to one file per namespace per target`() {
        val dir = Files.createTempDirectory("schemata-cli")
        val src = dir.resolve("src").createDirectories()
        src.resolve("orders.schemata").writeText(orders)
        src.resolve("customers.schemata").writeText(customers)
        val out = dir.resolve("out")

        val result = CompileCommand().test("--target proto,sql --out $out $src")

        assertEquals(0, result.statusCode, result.stderr)
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
        assertTrue(
            result.stderr.contains(
                "warning (lossy) [SCH2001]: ${src.resolve("orders.schemata")}:4:3:"
            ),
            result.stderr,
        )
    }

    @Test
    fun `the worked example compiles to proto with exit 0 and warnings on stderr`() {
        val dir = Files.createTempDirectory("schemata-cli")
        val src = dir.resolve("src").createDirectories()
        val corpus = java.io.File("src/test/resources/corpus/worked-example")
        src.resolve("orders.schemata").writeText(corpus.resolve("orders.schemata").readText())
        src.resolve("customers.schemata").writeText(corpus.resolve("customers.schemata").readText())
        val out = dir.resolve("out")
        val result = CompileCommand().test("--target proto --out $out $src")
        assertEquals(0, result.statusCode, result.stderr)
        assertEquals(
            corpus.resolve("expected/proto/shop/orders.proto").readText(),
            out.resolve("proto/shop/orders.proto").readText(),
        )
        assertTrue(
            result.stderr.lines().count { it.startsWith("warning (lossy) [SCH2001]") } == 17,
            result.stderr,
        )
    }

    @Test
    fun `exits 1 and reports the file and position on a syntax error`() {
        val dir = Files.createTempDirectory("schemata-cli")
        val input =
            dir.resolve("bad.schemata").apply { writeText("namespace a\nrecord R { x uuid }") }

        val result = CompileCommand().test("--target proto $input")

        assertEquals(1, result.statusCode)
        assertTrue(result.stderr.contains("error [SCH0001]: $input:2:14: "), result.stderr)
    }

    @Test
    fun `--strict rejects implicit ordinals`() {
        val dir = Files.createTempDirectory("schemata-cli")
        val input =
            dir.resolve("s.schemata").apply { writeText("namespace s\nrecord R { x: bool }") }
        val result = CompileCommand().test("--target proto --strict $input")
        assertEquals(1, result.statusCode)
        assertTrue(
            result.stderr.contains(
                "error [SCH1014]: $input:2:12: field 'x' has no explicit ordinal (--strict)"
            ),
            result.stderr,
        )
    }

    @Test
    fun `rejects an unknown target`() {
        val dir = Files.createTempDirectory("schemata-cli")
        val input = dir.resolve("a.schemata").apply { writeText("namespace a") }

        val result = CompileCommand().test("--target avro $input")

        assertTrue(result.statusCode != 0)
        assertTrue(result.stderr.contains("unknown target 'avro'"), result.stderr)
    }

    @Test
    fun `rejects a directory with no schemata files`() {
        val dir = Files.createTempDirectory("schemata-empty")

        val result = CompileCommand().test("--target proto $dir")

        assertTrue(result.statusCode != 0)
        assertTrue(result.stderr.contains("no .schemata files"), result.stderr)
    }
}
