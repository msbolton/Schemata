package io.schemata.cli

import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.testing.test
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class CompileCommandTest {
    private val orders =
        """
        schema shop.orders

        model User {
          id    uuid    { id }
          email string?
          name  string
          age   int32
        }
        """
            .trimIndent()

    private val customers =
        """
        schema shop.customers

        model Customer { id uuid { id }  name string }
        """
            .trimIndent()

    // JUnit removes this directory after each test, so the sources and outputs never outlive it.
    @TempDir lateinit var tmp: java.nio.file.Path

    private fun tempSources(
        vararg files: Pair<String, String>
    ): Pair<java.nio.file.Path, java.nio.file.Path> {
        val dir = Files.createTempDirectory(tmp, "schemata-cli")
        val src = dir.resolve("src").createDirectories()
        files.forEach { (name, text) -> src.resolve(name).writeText(text) }
        return src to dir.resolve("out")
    }

    @Test
    fun `compiles a directory to one file per namespace per target and exits 2 on warnings`() {
        val (src, out) = tempSources("orders.schemata" to orders, "customers.schemata" to customers)

        val result = CompileCommand().test("--no-config --target proto,sql --out $out $src")

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
        val result = CompileCommand().test("--no-config --out $out $src")
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
                    schema p

                    model Orphan { #1 name string }

                    model R { #1 id uuid { id } }
                    """
                        .trimIndent()
            )
        val result = CompileCommand().test("--no-config --out $out $src")
        assertEquals(1, result.statusCode, result.stderr)
        assertTrue(Files.exists(out.resolve("proto/p.proto")), result.stderr)
        assertFalse(Files.exists(out.resolve("sql")), result.stderr)
        assertTrue(result.stderr.contains("sql: not written (1 error)"), result.stderr)
    }

    @Test
    fun `the worked example compiles to proto with exit 2 and 18 lossy warnings`() {
        val corpus = java.io.File("src/test/resources/corpus/worked-example")
        val (src, out) =
            tempSources(
                "orders.schemata" to corpus.resolve("orders.schemata").readText(),
                "customers.schemata" to corpus.resolve("customers.schemata").readText(),
            )
        val result = CompileCommand().test("--no-config --target proto --out $out $src")
        assertEquals(2, result.statusCode, result.stderr)
        assertEquals(
            corpus.resolve("expected/proto/shop/orders.proto").readText(),
            out.resolve("proto/shop/orders.proto").readText(),
        )
        assertEquals(
            18,
            result.stderr.lines().count { it.startsWith("warning[SCH2001]") },
            result.stderr,
        )
        assertTrue(result.stderr.contains("0 errors, 18 warnings"), result.stderr)
    }

    @Test
    fun `exits 1 and shows the excerpt on a syntax error`() {
        val (src, out) = tempSources("bad.schemata" to "schema a\nmodel R { x: uuid }")
        val result = CompileCommand().test("--no-config --target proto --out $out $src")
        assertEquals(1, result.statusCode)
        assertTrue(result.stderr.contains("error[SCH0001]: "), result.stderr)
        assertTrue(result.stderr.contains("2 | model R { x: uuid }"), result.stderr)
        assertFalse(Files.exists(out))
    }

    @Test
    fun `a 1x file is one SCH0008 that names the upgrade`() {
        val (src, out) = tempSources("old.schemata" to "namespace a\nrecord R { x: uuid }\n")
        val result = CompileCommand().test("--no-config --target proto --out $out $src")
        assertEquals(1, result.statusCode)
        assertTrue(result.stderr.contains("error[SCH0008]: this is a 1.x schema"), result.stderr)
        assertTrue(result.stderr.contains("help: run schemata upgrade on this file"), result.stderr)
        assertTrue(result.stderr.contains("1 error, 0 warnings"), result.stderr)
    }

    @Test
    fun `a syntax error at end of file does not crash the human renderer`() {
        // The trailing extraneous-input diagnostic for this file spans an empty line at endColumn
        // 0, which used to make HumanRenderer.column() call String.take(-1) and throw.
        val (src, out) =
            tempSources(
                "bad.schemata" to "schema shop.orders\n\nmodel OrderLine {\n  #1 model string\n}\n"
            )
        val result = CompileCommand().test("--no-config --target proto --out $out $src")
        assertEquals(1, result.statusCode, result.stderr)
        assertTrue(result.stderr.contains("error[SCH0001]"), result.stderr)
        assertFalse(result.stderr.contains("Exception"), result.stderr)
    }

    @Test
    fun `--strict rejects implicit ordinals and promotes warnings`() {
        val (src, out) = tempSources("s.schemata" to "schema s\n\nmodel R { x bool }")
        val result = CompileCommand().test("--no-config --target proto --strict --out $out $src")
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
        val result =
            CompileCommand().test("--no-config --target proto --format json --out $out $src")
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
        val colored =
            CompileCommand().test("--no-config --target proto --color always --out $out $src")
        assertTrue(colored.stderr.contains("\u001b[33mwarning\u001b[0m"), colored.stderr)
        val plain =
            CompileCommand().test("--no-config --target proto --color never --out $out $src")
        assertFalse(plain.stderr.contains("\u001b["), plain.stderr)
    }

    @Test
    fun `rejects an unknown target`() {
        val (src, _) = tempSources("a.schemata" to "schema a")
        val result = CompileCommand().test("--no-config --target avro $src")
        assertEquals(1, result.statusCode)
        assertTrue(result.stderr.contains("unknown target 'avro'"), result.stderr)
    }

    @Test
    fun `--proto-layout type writes one proto file per type under the package directory`() {
        val (src, out) = tempSources("orders.schemata" to orders, "customers.schemata" to customers)
        val result =
            CompileCommand().test("--no-config --target proto --proto-layout type --out $out $src")
        assertTrue(result.statusCode == 0 || result.statusCode == 2, result.stderr)
        assertTrue(Files.exists(out.resolve("proto/shop/orders/user.proto")), result.stderr)
        assertFalse(Files.exists(out.resolve("proto/shop/orders.proto")), result.stderr)
    }

    @Test
    fun `--proto-layout without the proto target is a usage error`() {
        val (src, _) = tempSources("a.schemata" to "schema a")
        val result = CompileCommand().test("--no-config --target sql --proto-layout type $src")
        assertEquals(1, result.statusCode)
        assertTrue(
            result.stderr.contains("--proto-layout needs the proto target; add proto to --target"),
            result.stderr,
        )
    }

    @Test
    fun `--proto-layout rejects an unknown layout`() {
        val (src, _) = tempSources("a.schemata" to "schema a")
        val result = CompileCommand().test("--no-config --proto-layout flat $src")
        assertTrue(result.statusCode != 0)
        assertTrue(result.stderr.contains("schema, type"), result.stderr)
    }

    @Test
    fun `rejects a directory with no schemata files`() {
        val dir = Files.createTempDirectory(tmp, "schemata-empty")
        val result = CompileCommand().test("--no-config --target proto $dir")
        assertEquals(1, result.statusCode)
        assertTrue(result.stderr.contains("no .schemata files"), result.stderr)
    }

    @Test
    fun `duplicate --target names lower once`() {
        val (src, out) = tempSources("customers.schemata" to customers)
        val duplicated = CompileCommand().test("--no-config --target proto,proto --out $out $src")
        val (src2, out2) = tempSources("customers.schemata" to customers)
        val single = CompileCommand().test("--no-config --target proto --out $out2 $src2")
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
        val result =
            CompileCommand().test("--no-config --target proto --out ${blocker.resolve("sub")} $src")
        assertEquals(1, result.statusCode, result.stderr)
        assertTrue(result.stderr.contains("cannot write"), result.stderr)
        assertFalse(result.stderr.contains("at io.schemata"), result.stderr)
    }

    @Test
    fun `a usage error raised while running prints the subcommand usage line`() {
        val (src, _) = tempSources("a.schemata" to "schema a")
        val result =
            Schemata().subcommands(CompileCommand()).test("compile --no-config --target avro $src")
        assertEquals(1, result.statusCode)
        assertTrue(result.stderr.contains("Usage: schemata compile "), result.stderr)
        assertTrue(result.stderr.contains("unknown target 'avro'"), result.stderr)
    }

    @Test
    fun `--strict skips the target holding a promoted warning and still writes the others`() {
        val (src, out) =
            tempSources(
                "customers.schemata" to
                    "schema shop.customers\n\nmodel Customer { #1 id uuid { id }  #2 name string }\n"
            )
        val result =
            CompileCommand().test("--no-config --target proto,sql --strict --out $out $src")
        assertEquals(1, result.statusCode, result.stderr)
        assertFalse(Files.exists(out.resolve("proto")), result.stderr)
        assertTrue(Files.exists(out.resolve("sql")), result.stderr)
        assertTrue(result.stderr.contains("proto: not written"), result.stderr)
        assertTrue(result.stderr.contains("wrote 1 file to $out/sql"), result.stderr)
    }

    @Test
    fun `--strict promotes lossy warnings so the target is skipped`() {
        val (src, out) =
            tempSources(
                "e.schemata" to
                    """
                    schema e

                    enum Color { #1 red #2 green }

                    model R { #1 id uuid { id }  #2 color Color }
                    """
                        .trimIndent()
            )
        val result = CompileCommand().test("--no-config --target proto --strict --out $out $src")
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

    private val shop = "schema shop\n\nmodel R { #1 x bool }\n"

    /** A project directory holding `schemata.toml` with [toml] and `schemas/shop.schemata`. */
    private fun project(toml: String, schema: String = shop): java.nio.file.Path {
        val root = Files.createTempDirectory(tmp, "project")
        root.resolve("schemata.toml").writeText(toml)
        root.resolve("schemas").createDirectories().resolve("shop.schemata").writeText(schema)
        return root
    }

    private val javaPackage = "[proto]\njava_package = \"com.example.{schema}\"\n"

    @Test
    fun `a schemata toml in the working directory supplies proto defaults`() {
        val root = project(javaPackage)
        val run =
            SchemataProcess.run(root, "compile", "--target", "proto", "--out", "out", "schemas")
        assertEquals(0, run.statusCode, run.stderr)
        assertTrue(
            root
                .resolve("out/proto/shop.proto")
                .readText()
                .contains("option java_package = \"com.example.shop\";"),
            run.stderr,
        )
    }

    @Test
    fun `the nearest file above the working directory is found`() {
        val root = project("[sources]\npaths = [\"schemas\"]\n\n$javaPackage")
        val nested = root.resolve("schemas/nested").createDirectories()
        assertEquals(
            root.resolve("schemata.toml"),
            io.schemata.config.ConfigLoader.discover(nested),
        )
        val out = root.resolve("out")
        val run = SchemataProcess.run(nested, "compile", "--target", "proto", "--out", "$out")
        assertEquals(0, run.statusCode, run.stderr)
        assertTrue(
            out.resolve("proto/shop.proto")
                .readText()
                .contains("option java_package = \"com.example.shop\";"),
            run.stderr,
        )
    }

    @Test
    fun `--no-config ignores a file in the working directory`() {
        val root = project(javaPackage)
        val run =
            SchemataProcess.run(
                root,
                "compile",
                "--no-config",
                "--target",
                "proto",
                "--out",
                "out",
                "schemas",
            )
        assertEquals(0, run.statusCode, run.stderr)
        val (src, out) = tempSources("shop.schemata" to shop)
        val plain = CompileCommand().test("--no-config --target proto --out $out $src")
        assertEquals(plain.statusCode, run.statusCode)
        assertEquals(
            out.resolve("proto/shop.proto").readText(),
            root.resolve("out/proto/shop.proto").readText(),
        )
        assertEquals("no diagnostics\nwrote 1 file to out/proto\n", run.stderr)
    }

    @Test
    fun `--config and --no-config together are a usage error`() {
        val root = project(javaPackage)
        val result =
            CompileCommand()
                .test(
                    "--config ${root.resolve("schemata.toml")} --no-config ${root.resolve("schemas")}"
                )
        assertEquals(1, result.statusCode)
        assertTrue(
            result.stderr.contains("--config and --no-config are mutually exclusive"),
            result.stderr,
        )
    }

    @Test
    fun `--config names a missing file as SCH2901`() {
        val (src, out) = tempSources("shop.schemata" to shop)
        val missing = tmp.resolve("nope.toml")
        val result = CompileCommand().test("--config $missing --out $out $src")
        assertEquals(1, result.statusCode, result.stderr)
        assertTrue(
            result.stderr.contains(
                "error[SCH2901]: $missing: cannot read the configuration file: no such file"
            ),
            result.stderr,
        )
        assertTrue(
            result.stderr.contains(
                "help: create the file, name another with --config, or pass --no-config"
            ),
            result.stderr,
        )
        assertFalse(Files.exists(out))
    }

    @Test
    fun `compile with no paths uses sources from the file`() {
        val root = project("[compile]\ntargets = [\"proto\"]\n\n[sources]\npaths = [\"schemas\"]\n")
        val out = root.resolve("out")
        val result = CompileCommand().test("--config ${root.resolve("schemata.toml")} --out $out")
        assertEquals(0, result.statusCode, result.stderr)
        assertTrue(Files.exists(out.resolve("proto/shop.proto")), result.stderr)
        assertFalse(Files.exists(out.resolve("sql")), result.stderr)
    }

    @Test
    fun `no paths and no sources is a usage error`() {
        val root = project("[compile]\ntargets = [\"proto\"]\n")
        val toml = root.resolve("schemata.toml")
        val withFile = CompileCommand().test("--config $toml")
        assertEquals(1, withFile.statusCode)
        assertTrue(
            withFile.stderr.contains("no paths given and no [sources] in $toml"),
            withFile.stderr,
        )
        val without = CompileCommand().test("--no-config")
        assertEquals(1, without.statusCode)
        assertTrue(
            without.stderr.contains("no paths given and no [sources] in any schemata.toml"),
            without.stderr,
        )
    }

    @Test
    fun `a missing sources path is SCH2905`() {
        val root = project("[sources]\npaths = [\"missing\"]\n")
        val toml = root.resolve("schemata.toml")
        val result = CompileCommand().test("--config $toml --out ${root.resolve("out")}")
        assertEquals(1, result.statusCode, result.stderr)
        assertTrue(
            result.stderr.contains("error[SCH2905]: $toml:2: sources path 'missing' names nothing"),
            result.stderr,
        )
        // The file's own line is quoted under the diagnostic.
        assertTrue(result.stderr.contains("2 | paths = [\"missing\"]"), result.stderr)
        assertTrue(result.stderr.contains("1 error, 0 warnings"), result.stderr)
        assertFalse(Files.exists(root.resolve("out")))
    }

    @Test
    fun `a file with errors is reported as json under --format json`() {
        val root = project("[compile]\nstrict = \"yes\"\n")
        val toml = root.resolve("schemata.toml")
        val result =
            CompileCommand().test("--config $toml --format json ${root.resolve("schemas")}")
        assertEquals(1, result.statusCode, result.stderr)
        assertEquals("", result.stderr)
        assertTrue(result.stdout.contains("\"code\":\"SCH2903\""), result.stdout)
        assertTrue(result.stdout.contains("\"exitCode\": 1"), result.stdout)
    }

    @Test
    fun `an exclude that leaves nothing is a usage error naming the file`() {
        val root = project("[sources]\npaths = [\"schemas\"]\nexclude = [\"schemas/**\"]\n")
        val toml = root.resolve("schemata.toml")
        val result = CompileCommand().test("--config $toml --out ${root.resolve("out")}")
        assertEquals(1, result.statusCode, result.stderr)
        assertTrue(
            result.stderr.contains(
                "every file under ${root.resolve("schemas")} is excluded by [sources].exclude in $toml"
            ),
            result.stderr,
        )
    }

    @Test
    fun `an exclude drops the files it matches`() {
        val root =
            project(
                "[compile]\ntargets = [\"proto\"]\n\n[sources]\npaths = [\"schemas\"]\nexclude = [\"schemas/drafts/**\"]\n"
            )
        root
            .resolve("schemas/drafts")
            .createDirectories()
            .resolve("draft.schemata")
            .writeText("schema draft\n\nmodel R { #1 x bool }\n")
        val out = root.resolve("out")
        val result = CompileCommand().test("--config ${root.resolve("schemata.toml")} --out $out")
        assertEquals(0, result.statusCode, result.stderr)
        assertTrue(Files.exists(out.resolve("proto/shop.proto")), result.stderr)
        assertFalse(Files.exists(out.resolve("proto/draft.proto")), result.stderr)
    }

    @Test
    fun `a flag beats the file`() {
        val root =
            project(
                "[compile]\ntargets = [\"proto\"]\nproto_layout = \"type\"\nout = \"x\"\n\n" +
                    "[sources]\npaths = [\"schemas\"]\n"
            )
        val y = root.resolve("y")
        val result =
            CompileCommand()
                .test("--config ${root.resolve("schemata.toml")} --proto-layout schema --out $y")
        assertEquals(0, result.statusCode, result.stderr)
        assertTrue(Files.exists(y.resolve("proto/shop.proto")), result.stderr)
        assertFalse(Files.exists(y.resolve("proto/shop/r.proto")), result.stderr)
        assertFalse(Files.exists(root.resolve("x")), result.stderr)
    }

    @Test
    fun `the file beats the built-in default`() {
        val root =
            project(
                "[compile]\ntargets = [\"sql\"]\nout = \"gen\"\n\n[sources]\npaths = [\"schemas\"]\n",
                "schema shop\n\nmodel R { #1 id int64 { id }  #2 x bool }\n",
            )
        val result = CompileCommand().test("--config ${root.resolve("schemata.toml")}")
        assertEquals(0, result.statusCode, result.stderr)
        // A relative out in the file is relative to the file's directory.
        val gen = root.resolve("gen")
        assertTrue(Files.exists(gen.resolve("sql/shop.sql")), result.stderr)
        assertFalse(Files.exists(gen.resolve("proto")), result.stderr)
        assertTrue(result.stderr.contains("wrote 1 file to $gen/sql"), result.stderr)
    }

    @Test
    fun `a proto layout from the file is ignored when proto is not selected`() {
        val root =
            project(
                "[compile]\nproto_layout = \"type\"\n",
                "schema shop\n\nmodel R { #1 id int64 { id }  #2 x bool }\n",
            )
        val out = root.resolve("out")
        val result =
            CompileCommand()
                .test(
                    "--config ${root.resolve("schemata.toml")} --target sql --out $out ${root.resolve("schemas")}"
                )
        assertEquals(0, result.statusCode, result.stderr)
        assertTrue(Files.exists(out.resolve("sql/shop.sql")), result.stderr)
    }

    @Test
    fun `strict from the file promotes warnings`() {
        val root =
            project("[compile]\nstrict = true\n", "schema shop\n\nmodel R { #1 id uuid { id } }\n")
        val out = root.resolve("out")
        val result =
            CompileCommand()
                .test(
                    "--config ${root.resolve("schemata.toml")} --target proto --out $out ${root.resolve("schemas")}"
                )
        assertEquals(1, result.statusCode, result.stderr)
        assertTrue(
            result.stderr.contains("error[SCH2001] (lossy) (proto) [promoted]"),
            result.stderr,
        )
        assertFalse(Files.exists(out.resolve("proto")))
    }

    @Test
    fun `the report format and colour come from the file`() {
        val root = project("[report]\nformat = \"json\"\n")
        val out = root.resolve("out")
        val result =
            CompileCommand()
                .test(
                    "--config ${root.resolve("schemata.toml")} --target proto --out $out ${root.resolve("schemas")}"
                )
        assertEquals(0, result.statusCode, result.stderr)
        assertEquals("", result.stderr)
        assertTrue(result.stdout.startsWith("{\n  \"diagnostics\": []"), result.stdout)
        val human =
            CompileCommand()
                .test(
                    "--config ${root.resolve("schemata.toml")} --format human --target proto --out $out ${root.resolve("schemas")}"
                )
        assertEquals("", human.stdout)
        assertTrue(human.stderr.contains("no diagnostics"), human.stderr)
    }

    /**
     * Every command test that is not about discovery passes `--no-config`, but a `schemata.toml` in
     * the repository would still change how the CLI behaves for anyone working in it, so one here
     * fails loudly.
     */
    @Test
    fun `the repository holds no schemata toml`() {
        val cli = java.nio.file.Path.of("").toAbsolutePath()
        assertFalse(Files.exists(cli.resolve("schemata.toml")), "$cli holds a schemata.toml")
        assertFalse(
            Files.exists(cli.parent.resolve("schemata.toml")),
            "${cli.parent} holds a schemata.toml",
        )
    }
}
