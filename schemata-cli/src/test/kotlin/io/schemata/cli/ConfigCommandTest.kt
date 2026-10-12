package io.schemata.cli

import com.github.ajalt.clikt.testing.test
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class ConfigCommandTest {
    @TempDir lateinit var tmp: Path

    private fun toml(text: String): Path = tmp.resolve("schemata.toml").apply { writeText(text) }

    private fun schema(path: String, text: String): Path =
        tmp.resolve(path).apply {
            parent.createDirectories()
            writeText(text)
        }

    @Test
    fun `config prints no file when none applies`() {
        val result = ConfigCommand().test("--no-config")
        assertEquals(0, result.statusCode, result.stderr)
        assertEquals(
            listOf(
                "no configuration file",
                "compile.targets = [\"proto\", \"sql\", \"xsd\", \"jsonschema\", \"openapi\"]  (default)",
                "compile.out = \"out\"  (default)",
                "compile.strict = false  (default)",
                "compile.proto_layout = \"schema\"  (default)",
                "import.include = []  (default)",
                "migrate.allow_destructive = false  (default)",
                "report.format = \"human\"  (default)",
                "report.color = \"auto\"  (default)",
                "",
            ),
            result.stdout.lines(),
        )
        assertEquals("", result.stderr)
    }

    @Test
    fun `config prints settings with their origin`() {
        val toml =
            toml(
                """
                [compile]
                targets = ["proto", "sql"]
                strict = true

                [import]
                from = "proto"
                include = ["vendor"]

                [report]
                color = "never"

                [sources]
                paths = ["schemas"]
                exclude = ["schemas/drafts/**"]

                [proto]
                java_package = "com.example.{schema}"
                java_multiple_files = true

                [proto.schemas."shop"]
                java_package = "com.example.shop.v1"
                """
                    .trimIndent() + "\n"
            )
        schema("schemas/shop.schemata", "schema shop\n\nmodel R { #1 x bool }\n")
        schema("schemas/drafts/draft.schemata", "schema draft\n\nmodel R { #1 x bool }\n")
        val result = ConfigCommand().test("--config $toml")
        assertEquals(0, result.statusCode, result.stderr)
        assertEquals(
            listOf(
                "file: $toml",
                "compile.targets = [\"proto\", \"sql\"]  ($toml:2)",
                "compile.out = \"out\"  (default)",
                "compile.strict = true  ($toml:3)",
                "compile.proto_layout = \"schema\"  (default)",
                "import.from = \"proto\"  ($toml:6)",
                "import.include = [\"vendor\"]  ($toml:7)",
                "migrate.allow_destructive = false  (default)",
                "report.format = \"human\"  (default)",
                "report.color = \"never\"  ($toml:10)",
                "sources.paths = [\"schemas\"]  ($toml:13)",
                "sources.exclude = [\"schemas/drafts/**\"]  ($toml:14)",
                "sources: 1 file",
                "proto.java_package = \"com.example.{schema}\"  ($toml:17)",
                "proto.java_multiple_files = true  ($toml:18)",
                "proto.schemas.\"shop\".java_package = \"com.example.shop.v1\"  ($toml:21)",
                "synthesized:",
                "  shop: @proto(java_package: \"com.example.shop.v1\")  ($toml:21)",
                "  shop: @proto(java_multiple_files: true)  ($toml:18)",
                "",
            ),
            result.stdout.lines(),
        )
        assertEquals("", result.stderr)
    }

    @Test
    fun `config lists the synthesized annotations with their lines`() {
        val toml =
            toml(
                "[proto]\njava_package = \"com.example.{schema}\"\n\n[sql]\nschema = \"{schema_}\"\n"
            )
        val src = tmp.resolve("src")
        schema(
            "src/orders.schemata",
            "schema shop.orders @sql(schema: \"orders\")\n\nmodel R { #1 x bool }\n",
        )
        schema("src/customers.schemata", "schema shop.customers\n\nmodel C { #1 x bool }\n")
        val result = ConfigCommand().test("--config $toml $src")
        assertEquals(0, result.statusCode, result.stderr)
        assertEquals(
            listOf(
                "synthesized:",
                "  shop.customers: @proto(java_package: \"com.example.shop.customers\")  ($toml:2)",
                "  shop.customers: @sql(schema: \"shop_customers\")  ($toml:5)",
                "  shop.orders: @proto(java_package: \"com.example.shop.orders\")  ($toml:2)",
                "",
            ),
            result.stdout.lines().dropWhile { it != "synthesized:" },
        )
    }

    @Test
    fun `config says when the file synthesizes nothing`() {
        val toml = toml("[compile]\nstrict = true\n")
        val src = schema("src/shop.schemata", "schema shop\n\nmodel R { #1 x bool }\n").parent
        val result = ConfigCommand().test("--config $toml $src")
        assertEquals(0, result.statusCode, result.stderr)
        assertTrue(result.stdout.endsWith("\nsynthesized: none\n"), result.stdout)
    }

    @Test
    fun `config prints json`() {
        val toml =
            toml("[compile]\nstrict = true\n\n[proto]\njava_package = \"com.example.{schema}\"\n")
        val src = schema("src/shop.schemata", "schema shop\n\nmodel R { #1 x bool }\n").parent
        val result = ConfigCommand().test("--config $toml --format json $src")
        assertEquals(0, result.statusCode, result.stderr)
        assertEquals("", result.stderr)
        val n = "\"line\":null"
        assertEquals(
            listOf(
                "{",
                "  \"file\": \"$toml\",",
                "  \"settings\": {" +
                    "\"compile\":{" +
                    "\"targets\":{\"value\":[\"proto\",\"sql\",\"xsd\",\"jsonschema\",\"openapi\"],$n}," +
                    "\"out\":{\"value\":\"out\",$n}," +
                    "\"strict\":{\"value\":true,\"line\":2}," +
                    "\"proto_layout\":{\"value\":\"schema\",$n}}," +
                    "\"import\":{\"include\":{\"value\":[],$n}}," +
                    "\"migrate\":{\"allow_destructive\":{\"value\":false,$n}}," +
                    "\"report\":{\"format\":{\"value\":\"human\",$n},\"color\":{\"value\":\"auto\",$n}}," +
                    "\"proto\":{\"java_package\":{\"value\":\"com.example.{schema}\",\"line\":5}}},",
                "  \"sources\": [],",
                "  \"synthesized\": [{\"schema\":\"shop\",\"target\":\"proto\"," +
                    "\"key\":\"java_package\",\"value\":\"com.example.shop\",\"line\":5}]",
                "}",
                "",
            ),
            result.stdout.lines(),
        )
    }

    @Test
    fun `config prints json for no file`() {
        val result = ConfigCommand().test("--no-config --format json")
        assertEquals(0, result.statusCode, result.stderr)
        assertTrue(result.stdout.startsWith("{\n  \"file\": null,\n"), result.stdout)
        assertTrue(result.stdout.endsWith("  \"sources\": [],\n  \"synthesized\": []\n}\n"))
    }

    @Test
    fun `config lists the files sources resolves to in json`() {
        val toml = toml("[sources]\npaths = [\"schemas\"]\n")
        val shop = schema("schemas/shop.schemata", "schema shop\n\nmodel R { #1 x bool }\n")
        val result = ConfigCommand().test("--config $toml --format json")
        assertEquals(0, result.statusCode, result.stderr)
        assertTrue(
            result.stdout.contains(
                "\"sources\":{\"paths\":{\"value\":[\"schemas\"],\"line\":2}}},\n"
            ),
            result.stdout,
        )
        assertTrue(result.stdout.contains("  \"sources\": [\"$shop\"],\n"), result.stdout)
    }

    @Test
    fun `config reports a file with errors and exits 1`() {
        val toml = toml("[proto]\njava_pakage = \"x\"\n")
        val result = ConfigCommand().test("--config $toml")
        assertEquals(1, result.statusCode)
        assertEquals("", result.stdout)
        assertTrue(
            result.stderr.contains("error[SCH2902]: $toml:2: unknown key 'proto.java_pakage'"),
            result.stderr,
        )
        assertTrue(result.stderr.contains("2 | java_pakage = \"x\""), result.stderr)
    }

    @Test
    fun `config reports schemas that do not analyse after the settings`() {
        val toml = toml("[compile]\nstrict = true\n")
        val src = schema("src/bad.schemata", "schema bad\nmodel R { #1 x }\n").parent
        val result = ConfigCommand().test("--config $toml $src")
        assertEquals(1, result.statusCode)
        assertTrue(result.stdout.startsWith("file: $toml\n"), result.stdout)
        assertTrue(!result.stdout.contains("synthesized"), result.stdout)
        assertTrue(result.stderr.contains("error[SCH0001]"), result.stderr)
    }
}
