package io.schemata.cli

import com.github.ajalt.clikt.testing.test
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CompileCommandTest {
    private val fixture =
        """
        namespace shop.orders

        record User {
          id:    uuid
          email: string?
          name:  string
          age:   int32
        }

        record Session {
          token:   string
          user_id: uuid
          active:  bool
        }
        """
            .trimIndent()

    @Test
    fun `writes one file per target under the output directory`() {
        val dir = Files.createTempDirectory("schemata-cli")
        val input = dir.resolve("orders.schemata").apply { writeText(fixture) }
        val out = dir.resolve("out")

        val result = CompileCommand().test("--target proto,sql --out $out $input")

        assertEquals(0, result.statusCode, result.stderr)
        assertTrue(out.resolve("proto/shop/orders.proto").readText().contains("message User"))
        assertTrue(
            out.resolve("sql/orders.sql").readText().contains("CREATE TABLE \"orders\".\"user\"")
        )
        assertTrue(result.stderr.contains("warning (lossy): "), result.stderr)
    }

    @Test
    fun `exits 1 and reports the position on a syntax error`() {
        val dir = Files.createTempDirectory("schemata-cli")
        val input =
            dir.resolve("bad.schemata").apply { writeText("namespace a\nrecord R { x uuid }") }

        val result = CompileCommand().test("--target proto $input")

        assertEquals(1, result.statusCode)
        assertTrue(result.stderr.contains("error: $input:2:14: "), result.stderr)
    }

    @Test
    fun `rejects an unknown target`() {
        val dir = Files.createTempDirectory("schemata-cli")
        val input = dir.resolve("a.schemata").apply { writeText("namespace a") }

        val result = CompileCommand().test("--target avro $input")

        assertTrue(result.statusCode != 0)
        assertTrue(result.stderr.contains("unknown target 'avro'"), result.stderr)
    }
}
