package io.schemata.cli

import com.github.ajalt.clikt.testing.test
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

private const val CYCLE_ALPHA = "schema cyc.alpha\n\nimport cyc.beta\n\nmodel A { #1 b B? }\n"

private const val CYCLE_BETA = "schema cyc.beta\n\nimport cyc.alpha\n\nmodel B { #1 a A? }\n"

private const val CYCLE_SERVICE = "\nservice S { #1 get(A): B }\n"

class DiffCommandTest {
    @TempDir lateinit var dir: File

    private fun side(name: String, text: String): File {
        val sideDir = File(dir, name).apply { mkdirs() }
        File(sideDir, "s.schemata").writeText(text)
        return sideDir
    }

    /** A side of two schemas in a reference cycle, `cyc.alpha` written as [alpha]. */
    private fun cycleSide(name: String, alpha: String): File {
        val sideDir = File(dir, name).apply { mkdirs() }
        File(sideDir, "alpha.schemata").writeText(alpha)
        File(sideDir, "beta.schemata").writeText(CYCLE_BETA)
        return sideDir
    }

    @Test
    fun `no changes exits 0`() {
        val schema = "schema s\n\nmodel R { #1 x int32 }\n"
        val old = side("old", schema)
        val new = side("new", schema)
        val r = DiffCommand().test("--no-config ${old.path} ${new.path}")
        assertEquals(0, r.statusCode, r.stderr)
        assertTrue(r.stderr.contains("no changes"), r.stderr)
    }

    @Test
    fun `a compatible change exits 0 and prints the change line`() {
        val old = side("old", "schema s\n\nmodel R { #1 x int32 }\n")
        val new = side("new", "schema s\n\nmodel R { #1 x int32  #2 y int32? }\n")
        val r = DiffCommand().test("--no-config ${old.path} ${new.path}")
        assertEquals(0, r.statusCode, r.stderr)
        assertTrue(r.stderr.contains("field 'y' added"), r.stderr)
    }

    @Test
    fun `a note exits 2`() {
        val old = side("old", "schema s\n\nmodel R {\n  #1 x int32\n  reserved #2, \"y\"\n}\n")
        val new = side("new", "schema s\n\nmodel R { #1 x int32 }\n")
        val r = DiffCommand().test("--no-config ${old.path} ${new.path}")
        assertEquals(2, r.statusCode, r.stderr)
        assertTrue(r.stderr.contains("warning[SCH2502]"), r.stderr)
        assertTrue(r.stderr.contains("1 change"), r.stderr)
        assertTrue(r.stderr.contains("proto: 0 breaking, 1 note"), r.stderr)
        assertTrue(r.stderr.contains("sql: 0 breaking, 0 notes"), r.stderr)
        assertTrue(r.stderr.contains("xsd: 0 breaking, 0 notes"), r.stderr)
        assertTrue(r.stderr.contains("jsonschema: 0 breaking, 0 notes"), r.stderr)
    }

    @Test
    fun `a break exits 1`() {
        val old = side("old", "schema s\n\nmodel R { #1 x int32  #2 y int32 }\n")
        val new = side("new", "schema s\n\nmodel R { #1 x int32 }\n")
        val r = DiffCommand().test("--no-config ${old.path} ${new.path}")
        assertEquals(1, r.statusCode, r.stderr)
        assertTrue(r.stderr.contains("error[SCH2501]"), r.stderr)
        assertTrue(r.stderr.contains("1 change"), r.stderr)
        assertTrue(r.stderr.contains("proto: 0 breaking, 1 note"), r.stderr)
        assertTrue(r.stderr.contains("sql: 1 breaking, 0 notes"), r.stderr)
        assertTrue(r.stderr.contains("xsd: 1 breaking, 0 notes"), r.stderr)
        assertTrue(r.stderr.contains("jsonschema: 1 breaking, 0 notes"), r.stderr)
    }

    @Test
    fun `strict on a note exits 1`() {
        val old = side("old", "schema s\n\nmodel R {\n  #1 x int32\n  reserved #2, \"y\"\n}\n")
        val new = side("new", "schema s\n\nmodel R { #1 x int32 }\n")
        val r = DiffCommand().test("--no-config --strict ${old.path} ${new.path}")
        assertEquals(1, r.statusCode, r.stderr)
        assertTrue(r.stderr.contains("[promoted]"), r.stderr)
    }

    @Test
    fun `target proto exits 0 on a change breaking only on sql`() {
        val old = side("old", "schema s\n\nmodel R { #1 x int32 @sql(column: \"foo\") }\n")
        val new = side("new", "schema s\n\nmodel R { #1 x int32 @sql(column: \"bar\") }\n")
        val r = DiffCommand().test("--no-config --target proto ${old.path} ${new.path}")
        assertEquals(0, r.statusCode, r.stderr)
    }

    @Test
    fun `a parse error in new exits 1 with SCH2503`() {
        val old = side("old", "schema s\n\nmodel R { #1 x int32 }\n")
        val new = side("new", "schema s\nmodel R { #1 x }\n")
        val r = DiffCommand().test("--no-config ${old.path} ${new.path}")
        assertEquals(1, r.statusCode, r.stderr)
        assertTrue(r.stderr.contains("SCH2503"), r.stderr)
    }

    @Test
    fun `format json prints the comparison document`() {
        val old = side("old", "schema s\n\nmodel R { #1 x int32  #2 y int32 }\n")
        val new = side("new", "schema s\n\nmodel R { #1 x int32 }\n")
        val r = DiffCommand().test("--no-config --format json ${old.path} ${new.path}")
        assertEquals(1, r.statusCode, r.stderr)
        assertTrue(r.stdout.contains("\"exitCode\": 1"), r.stdout)
        assertTrue(r.stdout.contains("\"verdict\":\"breaking\""), r.stdout)
    }

    @Test
    fun `two sides sharing no namespace exit 1 with SCH2503`() {
        val old = side("old", "schema a\n\nmodel R { #1 x int32 }\n")
        val new = side("new", "schema b\n\nmodel R { #1 x int32 }\n")
        val r = DiffCommand().test("--no-config ${old.path} ${new.path}")
        assertEquals(1, r.statusCode, r.stderr)
        assertTrue(r.stderr.contains("error[SCH2503]: OLD and NEW share no schema"), r.stderr)
        assertTrue(r.stderr.contains("diff two versions of the same schema set"), r.stderr)
    }

    @Test
    fun `format json on SCH2503 prints an empty diff document with its errors`() {
        val old = side("old", "schema s\n\nmodel R { #1 x int32 }\n")
        val new = side("new", "schema s\nmodel R { #1 x }\n")
        val r =
            DiffCommand()
                .test("--no-config --format json --target proto,sql ${old.path} ${new.path}")
        assertEquals(1, r.statusCode, r.stderr)
        val lines = r.stdout.lines()
        assertEquals("  \"changes\": [],", lines[1], r.stdout)
        assertEquals(
            "  \"summary\": {\"proto\":{\"breaking\":0,\"notes\":0},\"sql\":{\"breaking\":0,\"notes\":0}},",
            lines[2],
            r.stdout,
        )
        assertEquals("  \"exitCode\": 1,", lines[3], r.stdout)
        assertTrue(lines[4].startsWith("  \"errors\": [{\"code\":\"SCH2503\""), r.stdout)
        assertTrue(lines[4].contains("\"message\":\"NEW: the schema set has 1 error\""), r.stdout)
        assertTrue(
            lines[4].contains("\"help\":\"fix the schema with check before diffing\""),
            r.stdout,
        )
        assertTrue(lines[4].contains("\"line\":2"), r.stdout)
    }

    @Test
    fun `target values are trimmed and repeated ones counted once`() {
        val old = side("old", "schema s\n\nmodel R { #1 x int32  #2 y int32 }\n")
        val new = side("new", "schema s\n\nmodel R { #1 x int32 }\n")
        val r =
            DiffCommand()
                .test(listOf("--no-config", "--target", "sql, proto,sql", old.path, new.path))
        assertEquals(1, r.statusCode, r.stderr)
        assertTrue(r.stderr.contains("sql: breaking, proto: note"), r.stderr)
        assertEquals(1, Regex("^sql: 1 breaking", RegexOption.MULTILINE).findAll(r.stderr).count())
    }

    @Test
    fun `a member annotation change names the member in its line`() {
        val old = side("old", "schema s\n\nmodel R { #1 x int32 { id }  #2 y int32 }\n")
        val new = side("new", "schema s\n\nmodel R { #1 x int32  #2 y int32 { id } }\n")
        val r = DiffCommand().test("--no-config ${old.path} ${new.path}")
        assertTrue(r.stderr.contains("field 'x': { id } removed"), r.stderr)
        assertTrue(r.stderr.contains("field 'y': { id } added"), r.stderr)
    }

    @Test
    fun `a change to a deprecated member says so in its line`() {
        val old =
            side(
                "old",
                "schema s\n" +
                    "\n" +
                    "model R { #1 x int32  #2 y int32? @deprecated  #3 z int32? @deprecated }\n",
            )
        val new = side("new", "schema s\n\nmodel R { #1 x int32  #2 w int32? @deprecated }\n")
        val r = DiffCommand().test("--no-config ${old.path} ${new.path}")
        assertTrue(r.stderr.contains("deprecated field 'y' renamed to 'w'"), r.stderr)
        assertTrue(r.stderr.contains("deprecated field 'z' removed"), r.stderr)
    }

    @Test
    fun `a dotted namespace's annotation change groups under its own name`() {
        val old = side("old", "schema shop.orders\n\nmodel R { #1 x int32 }\n")
        val new =
            side(
                "new",
                "schema shop.orders @sql(schema: \"orders_v2\")\n\nmodel R { #1 x int32 }\n",
            )
        val r = DiffCommand().test("--no-config ${old.path} ${new.path}")
        assertTrue(r.stderr.startsWith("shop.orders\n  @sql(schema) added"), r.stderr)
    }

    @Test
    fun `both sides get the file's defaults`() {
        val toml =
            File(dir, "schemata.toml").apply {
                writeText("[proto]\njava_package = \"com.example.{schema}\"\n")
            }
        val body = "\n\nmodel R { #1 x int32 }\n"
        val old = side("old", "schema s @proto(java_package: \"com.example.s\")$body")
        val new = side("new", "schema s$body")
        // The file gives NEW the package OLD wrote by hand, so nothing changed.
        val r = DiffCommand().test("--config ${toml.path} ${old.path} ${new.path}")
        assertEquals(0, r.statusCode, r.stderr)
        assertTrue(r.stderr.contains("no changes"), r.stderr)
        val plain = DiffCommand().test("--no-config ${old.path} ${new.path}")
        assertEquals(2, plain.statusCode, plain.stderr)
        assertTrue(plain.stderr.contains("warning[SCH2502]"), plain.stderr)
        // A schema line's own value still beats the file's on either side.
        val moved = side("moved", "schema s @proto(java_package: \"com.other.s\")$body")
        val changed = DiffCommand().test("--config ${toml.path} ${old.path} ${moved.path}")
        assertEquals(2, changed.statusCode, changed.stderr)
        assertTrue(changed.stderr.contains("warning[SCH2502]"), changed.stderr)
        assertTrue(changed.stderr.contains("java_package option changed"), changed.stderr)
    }

    @Test
    fun `strict from the file promotes a warning`() {
        val toml = File(dir, "schemata.toml").apply { writeText("[compile]\nstrict = true\n") }
        val old = side("old", "schema s\n\nmodel R {\n  #1 x int32\n  reserved #2, \"y\"\n}\n")
        val new = side("new", "schema s\n\nmodel R { #1 x int32 }\n")
        val r = DiffCommand().test("--config ${toml.path} ${old.path} ${new.path}")
        assertEquals(1, r.statusCode, r.stderr)
        assertTrue(r.stderr.contains("error[SCH2502]"), r.stderr)
        assertTrue(r.stderr.contains("[promoted]"), r.stderr)
    }

    @Test
    fun `proto layout from the flag changes method paths`() {
        val old = cycleSide("old", CYCLE_ALPHA + CYCLE_SERVICE)
        val new = cycleSide("new", CYCLE_ALPHA)
        val r =
            DiffCommand()
                .test("--no-config --target proto --proto-layout type ${old.path} ${new.path}")
        assertEquals(1, r.statusCode, r.stderr)
        assertTrue(r.stderr.contains("clients that call /cyc.alpha.S/…"), r.stderr)
        val schemaLayout = DiffCommand().test("--no-config --target proto ${old.path} ${new.path}")
        assertEquals(1, schemaLayout.statusCode, schemaLayout.stderr)
        assertTrue(schemaLayout.stderr.contains("clients that call /cyc.S/…"), schemaLayout.stderr)
    }

    @Test
    fun `proto layout from the configuration changes method paths`() {
        val toml =
            File(dir, "schemata.toml").apply { writeText("[compile]\nproto_layout = \"type\"\n") }
        val old = cycleSide("old", CYCLE_ALPHA + CYCLE_SERVICE)
        val new = cycleSide("new", CYCLE_ALPHA)
        val r = DiffCommand().test("--config ${toml.path} --target proto ${old.path} ${new.path}")
        assertEquals(1, r.statusCode, r.stderr)
        assertTrue(r.stderr.contains("clients that call /cyc.alpha.S/…"), r.stderr)
        // The flag still wins over the file.
        val flag =
            DiffCommand()
                .test(
                    "--config ${toml.path} --target proto --proto-layout schema " +
                        "${old.path} ${new.path}"
                )
        assertTrue(flag.stderr.contains("clients that call /cyc.S/…"), flag.stderr)
    }

    @Test
    fun `proto layout without the proto target is a usage error`() {
        val schema = "schema s\n\nmodel R { #1 x int32 }\n"
        val old = side("old", schema)
        val new = side("new", schema)
        val r =
            DiffCommand()
                .test("--no-config --target sql --proto-layout type ${old.path} ${new.path}")
        assertEquals(1, r.statusCode)
        assertTrue(
            r.stderr.contains("--proto-layout needs the proto target; add proto to --target"),
            r.stderr,
        )
        // The file's layout is only a default, so a run without proto ignores it.
        val toml =
            File(dir, "schemata.toml").apply { writeText("[compile]\nproto_layout = \"type\"\n") }
        val configured =
            DiffCommand().test("--config ${toml.path} --target sql ${old.path} ${new.path}")
        assertEquals(0, configured.statusCode, configured.stderr)
        assertTrue(configured.stderr.contains("no changes"), configured.stderr)
    }
}
