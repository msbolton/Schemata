package io.schemata.cli

import io.schemata.core.ir.Schema
import io.schemata.evolution.Evolution
import io.schemata.evolution.Rulebooks
import io.schemata.lang.Severity
import io.schemata.migrate.MigrateCodes
import io.schemata.target.sql.SqlTarget
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * Each example, diffed between its first commit and HEAD, should show nothing but `doc.changed`:
 * the examples only ever gained prose and formatting over their history, never a breaking change,
 * so this is the roadmap's own done-when for the corpus. The same history must also migrate under
 * the SQL target without a destructive step, from the first revision that lowers cleanly (an
 * example that only gained its keys later starts there). A dynamic test is skipped with
 * `assumeTrue` only when `git` is unavailable or the repository holds no history for the example (a
 * shallow clone); a side that fails to analyze fails the test, so drift in the language cannot turn
 * the check into a silent skip.
 */
class ExamplesHistoryTest {
    private val repoRoot = File("..")
    private val examplesRoot = File("../examples")

    @TestFactory
    fun `each example's history is silent but for doc changes and migrates cleanly`():
        List<DynamicTest> =
        examplesRoot
            .listFiles { f -> f.isDirectory }!!
            .sortedBy { it.name }
            .map { dir -> DynamicTest.dynamicTest(dir.name) { check(dir.name) } }

    private fun check(name: String) {
        val relative = "examples/$name"
        assumeTrue(git("rev-parse", "HEAD") != null, "git is not available")
        assumeTrue(
            git("rev-parse", "--is-shallow-repository")?.trim() != "true",
            "a shallow clone holds no history for $relative",
        )
        val log = git("log", "--format=%H", "--diff-filter=A", "--reverse", "--", relative)
        assertNotNull(log, "git log failed for $relative")
        val sha = log.lineSequence().firstOrNull { it.isNotBlank() }
        assertNotNull(sha, "no first commit found for $relative")
        val oldSources = revision(sha, relative)
        val newSources =
            TestSources.of(File(examplesRoot, name)).map {
                SourceInput("new/${it.path}", it.content)
            }
        val old = analyzeSide(oldSources)
        val new = analyzeSide(newSources)
        assertNotNull(
            old.schema,
            "the first commit of $relative ($sha) does not analyze: ${old.diagnostics}",
        )
        assertNotNull(new.schema, "HEAD of $relative does not analyze: ${new.diagnostics}")
        val kinds =
            Evolution.compare(old.schema!!, new.schema!!, Rulebooks.all).judged.map {
                it.change.kind
            }
        assertTrue(
            kinds.all { it == "doc.changed" },
            "$relative changed by more than docs between $sha and HEAD: ${kinds.distinct()}",
        )
        val revisions = git("log", "--format=%H", "--reverse", "--", relative)
        assertNotNull(revisions, "git log failed for $relative")
        val from =
            revisions
                .lineSequence()
                .filter { it.isNotBlank() }
                .mapNotNull { commit -> lowered(revision(commit, relative))?.let { commit to it } }
                .firstOrNull()
        assertNotNull(from, "no revision of $relative analyzes and lowers under sql")
        val (base, schema) = from
        val migrated = migrate(schema, new.schema!!, allowDestructive = false)
        assertTrue(
            migrated.lowered,
            "$relative: a revision has sql errors: ${migrated.diagnostics}",
        )
        assertTrue(
            migrated.diagnostics.none { it.code.id == MigrateCodes.DESTRUCTIVE.id },
            "$relative migrates destructively between $base and HEAD: ${migrated.diagnostics}",
        )
    }

    /** The `.schemata` files of [relative] at [sha], paths kept under `old/`. */
    private fun revision(sha: String, relative: String): List<SourceInput> {
        val listing = git("ls-tree", "-r", "--name-only", sha, "--", relative)
        assertNotNull(listing, "git ls-tree failed for $sha")
        val paths = listing.lineSequence().filter { it.endsWith(".schemata") }.toList()
        assertTrue(paths.isNotEmpty(), "no .schemata files at $sha for $relative")
        return paths.map { path ->
            val content = git("show", "$sha:$path")
            assertNotNull(content, "git show failed for $sha:$path")
            SourceInput("old/${File(path).name}", content)
        }
    }

    /** The analyzed schema of [sources] when it lowers under sql without an error, else null. */
    private fun lowered(sources: List<SourceInput>): Schema? {
        val schema = analyzeSide(sources).schema ?: return null
        val errors = SqlTarget.lower(schema).diagnostics.any { it.severity == Severity.ERROR }
        return if (errors) null else schema
    }

    private fun git(vararg args: String): String? =
        try {
            val process =
                ProcessBuilder(listOf("git") + args)
                    .directory(repoRoot)
                    .redirectErrorStream(true)
                    .start()
            val out = process.inputStream.bufferedReader().readText()
            val finished = process.waitFor(30, TimeUnit.SECONDS)
            if (!finished || process.exitValue() != 0) null else out
        } catch (e: IOException) {
            null
        }
}
