package io.schemata.cli

import io.schemata.evolution.Evolution
import io.schemata.evolution.Rulebooks
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * Each example, diffed between its first commit and HEAD, should show nothing but `doc.changed`:
 * the examples only ever gained prose and formatting over their history, never a breaking change,
 * so this is the roadmap's own done-when for the corpus. Every dynamic test is skipped with
 * `assumeTrue` when `git` is unavailable, the repository holds no history for the example (a
 * shallow clone), or either side fails to analyze.
 */
class ExamplesHistoryTest {
    private val repoRoot = File("..")
    private val examplesRoot = File("../examples")

    @TestFactory
    fun `each example's history is silent but for doc changes`(): List<DynamicTest> =
        examplesRoot
            .listFiles { f -> f.isDirectory }!!
            .sortedBy { it.name }
            .map { dir -> DynamicTest.dynamicTest(dir.name) { check(dir.name) } }

    private fun check(name: String) {
        val relative = "examples/$name"
        assumeTrue(git("rev-parse", "HEAD") != null, "git is not available")
        val log = git("log", "--format=%H", "--diff-filter=A", "--reverse", "--", relative)
        assumeTrue(log != null, "git log failed for $relative")
        val sha = log!!.lineSequence().firstOrNull { it.isNotBlank() }
        assumeTrue(sha != null, "no first commit found for $relative (a shallow clone?)")
        val listing = git("ls-tree", "-r", "--name-only", sha!!, "--", relative)
        assumeTrue(listing != null, "git ls-tree failed for $sha")
        val oldPaths = listing!!.lineSequence().filter { it.endsWith(".schemata") }.toList()
        assumeTrue(oldPaths.isNotEmpty(), "no .schemata files at $sha for $relative")
        val oldSources = mutableListOf<SourceInput>()
        for (path in oldPaths) {
            val content = git("show", "$sha:$path")
            assumeTrue(content != null, "git show failed for $sha:$path")
            oldSources += SourceInput("old/${File(path).name}", content!!)
        }
        val newSources =
            TestSources.of(File(examplesRoot, name)).map {
                SourceInput("new/${it.path}", it.content)
            }
        val old = Pipeline.analyze(oldSources)
        val new = Pipeline.analyze(newSources)
        assumeTrue(old.schema != null, "the first commit of $relative does not analyze cleanly")
        assumeTrue(new.schema != null, "HEAD of $relative does not analyze cleanly")
        val kinds =
            Evolution.compare(old.schema!!, new.schema!!, Rulebooks.all).judged.map {
                it.change.kind
            }
        assertTrue(
            kinds.all { it == "doc.changed" },
            "$relative changed by more than docs between $sha and HEAD: ${kinds.distinct()}",
        )
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
