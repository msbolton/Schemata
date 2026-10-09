package io.schemata.cli

import io.schemata.core.ir.Schema
import io.schemata.evolution.Evolution
import io.schemata.evolution.Rulebooks
import io.schemata.lang.Severity
import io.schemata.lang.format.FormatResult
import io.schemata.lang.upgrade.Upgrader
import io.schemata.migrate.MigrateCodes
import io.schemata.target.sql.SqlTarget
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * Each example, diffed between its first commit and HEAD, should show nothing but `doc.changed`:
 * the examples only ever gained prose and formatting over their history, never a breaking change,
 * so this is the roadmap's own done-when for the corpus; the one exception is [additions], a change
 * an example is known to have gained since. The same history must also migrate under the SQL target
 * without a destructive step, each revision that lowers cleanly to the next one, the last to the
 * working tree (an example that only gained its keys later starts there). A dynamic test is skipped
 * with `assumeTrue` only when `git` is unavailable or the repository holds no history for the
 * example (a shallow clone); a side that fails to analyze fails the test, so drift in the language
 * cannot turn the check into a silent skip. Revisions older than 2.0 are 1.x text and are upgraded
 * before they are analysed.
 */
class ExamplesHistoryTest {
    private val repoRoot = File("..")
    private val examplesRoot = File("../examples")

    /**
     * The kinds of change, besides `doc.changed`, an example may show between its first commit and
     * HEAD. The ledger's `Entry` gained a `tenant_id` of its own, so a journal entry names the
     * tenant its lines' accounts belong to.
     */
    private val additions = mapOf("ledger" to setOf("field.added"))

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
            kinds.all { it == "doc.changed" || it in additions[name].orEmpty() },
            "$relative changed by more than docs between $sha and HEAD: ${kinds.distinct()}",
        )
        val revisions = git("log", "--format=%H", "--reverse", "--", relative)
        assertNotNull(revisions, "git log failed for $relative")
        val lowering =
            revisions
                .lineSequence()
                .filter { it.isNotBlank() }
                .mapNotNull { commit -> lowered(revision(commit, relative))?.let { commit to it } }
                .toList()
        assertTrue(lowering.isNotEmpty(), "no revision of $relative analyzes and lowers under sql")
        (lowering + ("the working tree" to new.schema!!)).zipWithNext().forEach { (from, to) ->
            val migrated = migrate(from.second, to.second, allowDestructive = false)
            assertTrue(
                migrated.lowered,
                "$relative: a revision has sql errors: ${migrated.diagnostics}",
            )
            assertTrue(
                migrated.diagnostics.none { it.code.id == MigrateCodes.DESTRUCTIVE.id },
                "$relative migrates destructively from ${from.first} to ${to.first}: ${migrated.diagnostics}",
            )
        }
    }

    /**
     * The `.schemata` files of [relative] at [sha], paths kept under `old/`. A revision written in
     * the 1.x surface is upgraded in memory first, as `schemata upgrade` would rewrite it.
     */
    private fun revision(sha: String, relative: String): List<SourceInput> {
        val listing = git("ls-tree", "-r", "--name-only", sha, "--", relative)
        assertNotNull(listing, "git ls-tree failed for $sha")
        val paths = listing.lineSequence().filter { it.endsWith(".schemata") }.toList()
        assertTrue(paths.isNotEmpty(), "no .schemata files at $sha for $relative")
        return paths.map { path ->
            val content = git("show", "$sha:$path")
            assertNotNull(content, "git show failed for $sha:$path")
            val name = "old/${File(path).name}"
            val text =
                when (val upgraded = Upgrader.upgrade(content, name)) {
                    is FormatResult.Formatted -> upgraded.text
                    is FormatResult.Failed ->
                        fail("upgrade of $sha:$path failed: ${upgraded.diagnostics}")
                }
            SourceInput(name, text)
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
