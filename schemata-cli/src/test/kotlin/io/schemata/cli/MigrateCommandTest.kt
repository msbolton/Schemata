package io.schemata.cli

import com.github.ajalt.clikt.testing.test
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class MigrateCommandTest {
    @TempDir lateinit var dir: File

    private val old =
        """
        schema s
        model Customer {
          #1 id uuid { id }
          #2 name string { max 100 }
          #3 note string?
        }
        """
            .trimIndent()

    private class Run(val exitCode: Int, val stdout: String, val stderr: String, val outDir: File) {
        fun file(path: String): String = File(outDir, path).readText()

        fun fileOrNull(path: String): String? = File(outDir, path).takeIf { it.isFile }?.readText()
    }

    private fun migrate(oldText: String, newText: String, vararg flags: String): Run {
        File(dir, "old").apply { mkdirs() }.resolve("s.schemata").writeText(oldText)
        File(dir, "new").apply { mkdirs() }.resolve("s.schemata").writeText(newText)
        val out = File(dir, "out")
        val args = listOf("--out", out.path) + flags + listOf("$dir/old", "$dir/new")
        val r = MigrateCommand().test(args.joinToString(" "))
        return Run(r.statusCode, r.stdout, r.stderr, out)
    }

    @Test
    fun `a clean migration writes one file per namespace and exits 0`() {
        val new = old.replace("#3 note string?", "#3 note string?\n  #4 tier int32?")
        val run = migrate(old, new)
        assertEquals(0, run.exitCode, run.stderr)
        assertEquals(
            "BEGIN;\n\nALTER TABLE \"s\".\"customer\" ADD COLUMN \"tier\" integer;\n\nCOMMIT;\n",
            run.file("migrate/s.sql"),
        )
        assertTrue(
            run.stderr.contains("s.sql\n  add column \"customer\".\"tier\"    clean\n"),
            run.stderr,
        )
        assertTrue(run.stderr.contains("1 step in 1 file"), run.stderr)
    }

    @Test
    fun `a destructive step is an error and writes nothing without the flag`() {
        val new = old.replace("  #3 note string?\n", "")
        val run = migrate(old, new)
        assertEquals(1, run.exitCode)
        assertNull(run.fileOrNull("migrate/s.sql"))
        assertTrue(run.stderr.contains("error[SCH2701]"), run.stderr)
        assertTrue(
            run.stderr.contains("drop column \"customer\".\"note\"    destructive"),
            run.stderr,
        )
    }

    @Test
    fun `allow-destructive writes the step with its marker and exits 2`() {
        val new = old.replace("  #3 note string?\n", "")
        val run = migrate(old, new, "--allow-destructive")
        assertEquals(2, run.exitCode)
        assertTrue(
            run.file("migrate/s.sql")
                .contains(
                    "-- SCH2701: s.Customer.note: DROP COLUMN \"note\" loses every value the column holds\nALTER TABLE"
                )
        )
        assertTrue(run.stderr.contains("warning[SCH2701]"), run.stderr)
    }

    @Test
    fun `allow-destructive changes nothing when no step is destructive`() {
        val new = old.replace("#3 note string?", "#3 note string?\n  #4 tier int32?")
        val run = migrate(old, new, "--allow-destructive")
        assertEquals(0, run.exitCode)
        assertFalse(run.stderr.contains("SCH2701"))
    }

    @Test
    fun `strict promotes a may-fail step to an error`() {
        val new = old.replace("#3 note string?", "#3 note string")
        assertEquals(2, migrate(old, new).exitCode)
        assertEquals(1, migrate(old, new, "--strict").exitCode)
    }

    @Test
    fun `no changes prints no changes and exits 0`() {
        val run = migrate(old, old)
        assertEquals(0, run.exitCode)
        assertEquals("no changes\n\nno diagnostics\n", run.stderr)
        assertNull(run.fileOrNull("migrate/s.sql"))
    }

    @Test
    fun `a side with an sql error writes nothing`() {
        // keyless and unused: SCH2106 under sql
        val new = old + "\nmodel Orphan { #1 x int32 }\n"
        val run = migrate(old, new)
        assertEquals(1, run.exitCode)
        assertTrue(run.stderr.contains("SCH2106"), run.stderr)
        assertNull(run.fileOrNull("migrate/s.sql"))
    }

    @Test
    fun `json carries changes steps files and the exit code`() {
        val new = old.replace("  #3 note string?\n", "")
        val run = migrate(old, new, "--allow-destructive", "--format", "json")
        assertEquals(2, run.exitCode)
        val json = run.stdout
        assertTrue(json.contains("\"kind\":\"field.removed\""), json)
        assertTrue(json.contains("\"steps\": ["), json)
        assertTrue(json.contains("\"kind\":\"dropColumn\""), json)
        assertTrue(json.contains("\"risk\":\"destructive\""), json)
        assertTrue(json.contains("\"files\": ["), json)
        assertTrue(json.contains("\"exitCode\": 2"), json)
        assertEquals("", run.stderr)
    }

    @Test
    fun `a side that does not analyse is SCH2503 as in diff`() {
        val run = migrate(old, "schema s\nmodel Customer { #1 id uuid #1 dup uuid }")
        assertEquals(1, run.exitCode)
        assertTrue(run.stderr.contains("error[SCH2503]: NEW:"), run.stderr)
    }

    @Test
    fun `allow-destructive under strict exits 1 and writes nothing`() {
        val new = old.replace("  #3 note string?\n", "")
        val run = migrate(old, new, "--allow-destructive", "--strict")
        assertEquals(1, run.exitCode)
        assertNull(run.fileOrNull("migrate/s.sql"))
    }

    @Test
    fun `a may-fail step writes the file and exits 2`() {
        val new = old.replace("#3 note string?", "#3 note string")
        val run = migrate(old, new)
        assertEquals(2, run.exitCode)
        assertTrue(run.file("migrate/s.sql").contains("SET NOT NULL"))
        assertTrue(run.stderr.contains("warning[SCH2702]"), run.stderr)
    }

    @Test
    fun `a nested namespace writes under its path`() {
        val nested = old.replace("schema s", "schema shop.orders")
        val run =
            migrate(nested, nested.replace("#3 note string?", "#3 note string?\n  #4 tier int32?"))
        assertEquals(0, run.exitCode, run.stderr)
        assertTrue(run.file("migrate/shop/orders.sql").contains("ADD COLUMN \"tier\""))
    }

    @Test
    fun `json gives a blocked destructive step its code message and help`() {
        val new = old.replace("  #3 note string?\n", "")
        val run = migrate(old, new, "--format", "json")
        assertEquals(1, run.exitCode)
        val json = run.stdout
        assertTrue(json.contains("\"risk\":\"destructive\",\"path\":\"s.Customer.note\""), json)
        assertTrue(json.contains("\"code\":\"SCH2701\""), json)
        assertTrue(
            json.contains(
                "\"message\":\"s.Customer.note: DROP COLUMN \\\"note\\\" loses every value the column holds\""
            ),
            json,
        )
        assertTrue(
            json.contains(
                "\"help\":\"rerun with --allow-destructive once the data is migrated or no longer needed\""
            ),
            json,
        )
        assertTrue(json.contains("\"files\": []"), json)
        assertFalse(json.contains("\"errors\""), json)
        assertTrue(json.contains("\"exitCode\": 1"), json)
    }

    @Test
    fun `json leaves a clean step's code message and help null`() {
        val new = old.replace("#3 note string?", "#3 note string?\n  #4 tier int32?")
        val json = migrate(old, new, "--format", "json").stdout
        assertTrue(json.contains("\"code\":null,\"message\":null,\"help\":null"), json)
    }

    @Test
    fun `json for a side that does not analyse has no steps or files and the SCH2503 error`() {
        val run =
            migrate(old, "schema s\nmodel Customer { #1 id uuid #1 dup uuid }", "--format", "json")
        assertEquals(1, run.exitCode)
        val json = run.stdout
        assertTrue(json.contains("\"steps\": []"), json)
        assertTrue(json.contains("\"files\": []"), json)
        assertTrue(json.contains("\"errors\": [{\"code\":\"SCH2503\""), json)
    }

    @Test
    fun `json for a side with sql errors lists only the errors`() {
        // keyless and unused: SCH2106; a unique over the primary key: a warning compile reports
        val new =
            old.replace("#1 id uuid { id }", "#1 id uuid { id, unique }") +
                "\nmodel Orphan { #1 x int32 }\n"
        val run = migrate(old, new, "--format", "json")
        assertEquals(1, run.exitCode)
        val json = run.stdout
        assertTrue(json.contains("\"steps\": []"), json)
        assertTrue(json.contains("\"files\": []"), json)
        assertTrue(json.contains("\"code\":\"SCH2106\""), json)
        val errors = json.lines().single { it.trimStart().startsWith("\"errors\"") }
        assertEquals(1, Regex("\"code\":").findAll(errors).count(), errors)
    }
}
