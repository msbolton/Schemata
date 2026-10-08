package io.schemata.cli

import io.schemata.cli.report.JsonRenderer
import io.schemata.cli.report.Report
import io.schemata.lang.format.FormatResult
import io.schemata.lang.upgrade.Upgrader
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * `schemata upgrade` changes the meaning of a 1.x schema in no way but the ones 2.0 announces. Each
 * example and corpus case is taken as it stood at the `v1.4.0` tag, compiled to every target by the
 * released 1.4.0 jar, upgraded in memory, and compiled again by this compiler. The two output trees
 * and the two diagnostic lists (code, severity, target, message; positions move with the layout)
 * are compared file by file. A file whose content differs must be named in `equivalence/allow.txt`
 * with the reason, one of:
 * - `reference by key`: a field, list element, union member, or map value typed as a keyed model
 *   carries its key (`<field>_<key>`, or a `<Target>Key` object for a composite key) on Protobuf,
 *   XSD, JSON Schema, and OpenAPI;
 * - `on delete`: an `ON DELETE` clause on a foreign key;
 * - `type notes`: the `schemata:` notes on an emitted field are written in 2.0 spelling;
 * - `message text`: a diagnostic says `model` or `schema`, spells a type as 2.0 does, or is a
 *   warning on a field a reference now emits;
 * - `keyword rename`: an identifier that is a 2.0 keyword is renamed `<name>_value`.
 *
 * A path whose only reasons are `type notes` and `on delete` must match once the notes and the
 * clauses are masked, so those reasons cannot hide anything else. A path present in one tree only,
 * a difference with no line, and a line that matches no difference all fail: the list is the
 * complete set of output changes. The test is skipped without the jar (the build fetches it; an
 * offline build has none), without `git`, or in a clone that lacks the tag.
 */
class UpgradeEquivalenceTest {
    private val repoRoot = File("..")
    private val jar = File(System.getProperty("schemata.v1Jar") ?: "build/v1/schemata-1.4.0.jar")
    private val allow: Map<String, Set<String>> by lazy { readAllowList() }

    private val diagnosticsFile = "diagnostics"

    @TestFactory
    fun `every 1x case compiles to the 1x output but for the listed changes`(): List<DynamicTest> {
        val cases = cases()
        return cases.map { case -> DynamicTest.dynamicTest(case) { check(case) } }
    }

    @Test
    fun `every allow-list line names a case and a known reason`() {
        val known = cases().toSet()
        allow.forEach { (path, reasons) ->
            assertTrue(known.any { path.startsWith("$it/") }, "allow.txt: no case holds $path")
            assertTrue(
                reasons.all { it in REASONS },
                "allow.txt: unknown reason for $path: ${reasons - REASONS}",
            )
        }
    }

    private fun cases(): List<String> {
        assumeTrue(jar.isFile, "the 1.4.0 jar is not at $jar")
        assumeTrue(git("rev-parse", "HEAD") != null, "git is not available")
        assumeTrue(
            git("rev-parse", "--is-shallow-repository")?.trim() != "true",
            "a shallow clone holds no history",
        )
        assumeTrue(git("rev-parse", "--verify", "$TAG^{commit}") != null, "no $TAG tag")
        val listing = git("ls-tree", "-r", "--name-only", TAG, "--", "examples", CORPUS)
        assertNotNull(listing, "git ls-tree failed for $TAG")
        return listing
            .lineSequence()
            .filter { it.endsWith(".schemata") }
            .map { caseName(it.substringBeforeLast('/')) }
            .distinct()
            .sorted()
            .toList()
    }

    private fun caseName(dir: String): String = dir.removePrefix("schemata-cli/src/test/resources/")

    private fun repoDir(case: String): String =
        if (case.startsWith("examples/")) case else "schemata-cli/src/test/resources/$case"

    private fun check(case: String) {
        val sources = revision(repoDir(case))
        val work = Files.createTempDirectory("equivalence").toFile()
        try {
            val old = compileOld(sources, work)
            val new = compileNew(sources)
            compare(case, old, new)
        } finally {
            work.deleteRecursively()
        }
    }

    /** The case's `.schemata` files at the tag, by file name. */
    private fun revision(dir: String): Map<String, String> {
        val listing = git("ls-tree", "-r", "--name-only", TAG, "--", dir)
        assertNotNull(listing, "git ls-tree failed for $dir")
        return listing
            .lineSequence()
            .filter { it.endsWith(".schemata") && it.substringBeforeLast('/') == dir }
            .associate { path ->
                val content = git("show", "$TAG:$path")
                assertNotNull(content, "git show failed for $TAG:$path")
                File(path).name to content
            }
    }

    /** The 1.4.0 jar's output tree, plus its diagnostics under [diagnosticsFile]. */
    private fun compileOld(sources: Map<String, String>, work: File): Map<String, String> {
        val src = File(work, "src").apply { mkdirs() }
        sources.forEach { (name, content) -> File(src, name).writeText(content) }
        val out = File(work, "out")
        val java = File(System.getProperty("java.home"), "bin/java").path
        val process =
            ProcessBuilder(
                    java,
                    "-jar",
                    jar.absolutePath,
                    "compile",
                    "--out",
                    out.path,
                    "--format",
                    "json",
                    src.path,
                )
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        val json = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(120, TimeUnit.SECONDS), "the 1.4.0 jar did not finish")
        val tree =
            out.walkTopDown()
                .filter { it.isFile }
                .associate { it.relativeTo(out).invariantSeparatorsPath to it.readText() }
        return tree + (diagnosticsFile to diagnostics(json))
    }

    /** This compiler's output tree for the upgraded sources, plus its diagnostics. */
    private fun compileNew(sources: Map<String, String>): Map<String, String> {
        val upgraded =
            sources.map { (name, content) ->
                when (val result = Upgrader.upgrade(content, name)) {
                    is FormatResult.Formatted -> SourceInput(name, result.text)
                    is FormatResult.Failed -> fail("$name does not upgrade: ${result.diagnostics}")
                }
            }
        val result = Pipeline.compile(upgraded, Pipeline.targets)
        val report = Report.of(result, strict = false, checkOnly = false)
        val written = report.written.map { it.target }.toSet()
        val tree =
            result.targets
                .filter { it.name in written }
                .flatMap { t -> t.files.map { "${t.name}/${it.path}" to it.content } }
                .toMap()
        return tree + (diagnosticsFile to diagnostics(JsonRenderer.report(report, "out")))
    }

    /**
     * One line per diagnostic of a JSON report, `severity code target: message`, sorted: the report
     * shape is the same in 1.4.0 and here, and a position is not part of a diagnostic's meaning.
     */
    private fun diagnostics(json: String): String =
        DIAGNOSTIC.findAll(json)
            .map { m ->
                val (code, severity, target, message) = m.destructured
                "$severity $code ${target.trim('"')}: $message"
            }
            .sorted()
            .joinToString("\n")

    private fun compare(case: String, old: Map<String, String>, new: Map<String, String>) {
        val problems = mutableListOf<String>()
        (old.keys - new.keys).sorted().forEach { problems += "only 1.4.0 writes $case/$it" }
        (new.keys - old.keys).sorted().forEach { problems += "only 2.0 writes $case/$it" }
        val differing = mutableSetOf<String>()
        (old.keys intersect new.keys).sorted().forEach { path ->
            val before = old.getValue(path)
            val after = new.getValue(path)
            if (before == after) return@forEach
            val key = "$case/$path"
            differing += key
            val reasons = allow[key]
            when {
                reasons == null ->
                    problems +=
                        "$key differs and allow.txt has no line for it\n" + diff(before, after)
                reasons.all { it in MASKABLE } && mask(before, reasons) != mask(after, reasons) ->
                    problems +=
                        "$key differs beyond ${reasons.joinToString(" and ")}\n" +
                            diff(mask(before, reasons), mask(after, reasons))
                path == diagnosticsFile -> problems += lostDiagnostics(key, before, after)
            }
        }
        allow.keys
            .filter { it.startsWith("$case/") && it !in differing }
            .sorted()
            .forEach { problems += "allow.txt lists $it, which does not differ" }
        assertEquals(emptyList(), problems, problems.joinToString("\n\n"))
    }

    /**
     * A diagnostic list may change its text, and gain warnings for the fields a reference now
     * emits, but every code 1.4.0 reported must still be reported, and nothing new is an error.
     */
    private fun lostDiagnostics(key: String, before: String, after: String): List<String> {
        fun codes(text: String) =
            text.lines().filter { it.isNotBlank() }.map { it.substringBefore(':') }
        val remaining = codes(after).toMutableList()
        val lost = codes(before).filterNot { remaining.remove(it) }
        val errors = remaining.filter { it.startsWith("error ") }
        return listOfNotNull(
            lost.takeIf { it.isNotEmpty() }?.let { "$key loses diagnostics: $it" },
            errors.takeIf { it.isNotEmpty() }?.let { "$key gains errors: $it" },
        )
    }

    private fun mask(text: String, reasons: Set<String>): String {
        var out = text
        if ("type notes" in reasons) out = out.replace(NOTE, "$1…")
        if ("on delete" in reasons) out = out.replace(ON_DELETE, "")
        return out
    }

    private fun diff(before: String, after: String): String {
        val a = before.lines()
        val b = after.lines()
        return (0 until maxOf(a.size, b.size))
            .filter { a.getOrNull(it) != b.getOrNull(it) }
            .take(12)
            .joinToString("\n") {
                "  ${it + 1}: - ${a.getOrNull(it)}\n  ${it + 1}: + ${b.getOrNull(it)}"
            }
    }

    private fun readAllowList(): Map<String, Set<String>> {
        val text =
            javaClass.getResource("/equivalence/allow.txt")?.readText()
                ?: fail("equivalence/allow.txt is missing")
        val out = linkedMapOf<String, MutableSet<String>>()
        text
            .lines()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .forEach { line ->
                val path = line.substringBefore(' ')
                val reason = line.substringAfter(' ', "").trim()
                assertTrue(reason.isNotEmpty(), "allow.txt: no reason for $path")
                assertTrue(
                    out.getOrPut(path) { mutableSetOf() }.add(reason),
                    "allow.txt: $line twice",
                )
            }
        return out
    }

    private fun git(vararg args: String): String? =
        try {
            val process =
                ProcessBuilder(listOf("git") + args)
                    .directory(repoRoot)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
            val out = process.inputStream.bufferedReader().readText()
            val finished = process.waitFor(30, TimeUnit.SECONDS)
            if (!finished || process.exitValue() != 0) null else out
        } catch (e: IOException) {
            null
        }

    private companion object {
        const val TAG = "v1.4.0"
        const val CORPUS = "schemata-cli/src/test/resources/corpus"
        val REASONS =
            setOf("reference by key", "on delete", "type notes", "message text", "keyword rename")
        val MASKABLE = setOf("type notes", "on delete")
        val NOTE = Regex("""((?://|--) schemata: ).*""")
        val ON_DELETE = Regex(""" ON DELETE (?:CASCADE|RESTRICT|SET NULL)""")
        val DIAGNOSTIC =
            Regex(
                """\{"code":"(SCH\d+)","severity":"(\w+)","category":"\w+","promoted":(?:true|false),"target":(null|"\w+"),"message":"((?:[^"\\]|\\.)*)""""
            )
    }
}
