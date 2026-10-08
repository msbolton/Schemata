package io.schemata.cli

import io.schemata.cli.report.JsonRenderer
import io.schemata.cli.report.Report
import io.schemata.core.ir.AnnotationValue
import io.schemata.core.ir.ListOf
import io.schemata.core.ir.MapOf
import io.schemata.core.ir.RecordType
import io.schemata.core.ir.Ref
import io.schemata.core.ir.Schema
import io.schemata.core.ir.Type
import io.schemata.core.ir.UnionType
import io.schemata.core.ir.keyFields
import io.schemata.core.ir.selfAndNested
import io.schemata.core.ir.storedFields
import io.schemata.lang.format.FormatResult
import io.schemata.lang.upgrade.Upgrader
import io.schemata.target.Names
import io.schemata.target.keyRecordName
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.Future
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
 * `schemata upgrade` changes the meaning of a 1.x schema in no way but the ones 2.0 announces.
 * Every 1.x `.schemata` file in the repository at the `v1.4.0` tag is taken as it stood there:
 * - each example and corpus case, and each side (`old/`, `new/`) of each evolution case, is
 *   compiled to every target by the released 1.4.0 jar, upgraded in memory, and compiled again by
 *   this compiler. The two output trees and the two diagnostic lists (code, severity, target,
 *   message; positions move with the layout) are compared file by file;
 * - each diagnostics fixture that is checked rather than imported, diffed, or migrated is checked
 *   by both, with its own targets and `--strict`, and the two lists of codes are compared; a code
 *   2.0 retired counts as the code that took its place ([RETIRED]).
 *
 * A file whose content differs, or a fixture whose codes differ, must be named in
 * `equivalence/allow.txt` with the reason, one of:
 * - `reference by key`: a field, list element, union member, or map value typed as a keyed model
 *   carries its key (`<field>_<key>`, or a `<Target>Key` object for a composite key) on Protobuf,
 *   XSD, JSON Schema, and OpenAPI;
 * - `on delete`: an `ON DELETE` clause on a foreign key;
 * - `type notes`: the `schemata:` notes on an emitted field are written in 2.0 spelling;
 * - `message text`: a diagnostic says `model` or `schema`, spells a type as 2.0 does, or is a
 *   warning on a field a reference now emits;
 * - `keyword rename`: an identifier that is a 2.0 keyword is renamed `<name>_value`;
 * - `retired check`: a fixture whose 1.x check 2.0 moved into an option or dropped, deleted with
 *   it, so its codes differ;
 * - `upgrade refuses`: a fixture writing something 2.0 cannot say, which `upgrade` reports.
 *
 * Every reason but `message text`, `keyword rename`, and the two fixture reasons is masked: a path
 * whose reasons are only those must match once the notes, the clauses, and the lines of each
 * reference field (by its 1.x name and its 2.0 one, with every line indented under it), each
 * `<Target>Key` declaration, and the import lines are left out, so a listed reason cannot hide
 * anything else. A diagnostic list may change its text, but keeps every code 1.4.0 reported, gains
 * no error, and gains a warning only about a reference field. A path present in one tree only, a
 * difference with no line, and a line that matches no difference all fail: the list is the complete
 * set of changes.
 *
 * The test is skipped without the jar (the build fetches it; an offline build has none), without
 * `git`, or in a clone that lacks the tag; under `-Pschemata.requireV1Jar=true`, as CI runs it,
 * each of those fails instead.
 */
class UpgradeEquivalenceTest {
    private val repoRoot = File("..")
    private val jar = File(System.getProperty("schemata.v1Jar") ?: "build/v1/schemata-1.4.0.jar")
    private val required = System.getProperty("schemata.requireV1Jar") == "true"
    private val allow: Map<String, Set<String>> by lazy { readAllowList() }

    private val diagnosticsFile = "diagnostics"
    private val codesFile = "codes"

    /**
     * One 1.x case: [dir] is its directory at the tag, [name] how `allow.txt` names it; [codes]
     * when only the code lists are compared, under [targets] (null for all) and [strict].
     */
    private data class Case(
        val name: String,
        val dir: String,
        val codes: Boolean = false,
        val targets: List<String>? = null,
        val strict: Boolean = false,
    )

    @TestFactory
    fun `every 1x case compiles to the 1x output but for the listed changes`(): List<DynamicTest> {
        val cases = cases()
        val pool = Executors.newFixedThreadPool(4)
        val old = cases.associateWith { case -> pool.submit<Map<String, String>> { runOld(case) } }
        pool.shutdown()
        return cases.map { case -> DynamicTest.dynamicTest(case.name) { check(case, old) } }
    }

    @Test
    fun `every allow-list line names a case and a known reason`() {
        val known = cases().map { it.name }.toSet()
        allow.forEach { (path, reasons) ->
            assertTrue(known.any { path.startsWith("$it/") }, "allow.txt: no case holds $path")
            assertTrue(
                reasons.all { it in REASONS },
                "allow.txt: unknown reason for $path: ${reasons - REASONS}",
            )
        }
    }

    private fun need(condition: Boolean, message: String) {
        if (required) assertTrue(condition, "$message (-Pschemata.requireV1Jar=true)")
        else assumeTrue(condition, message)
    }

    private fun cases(): List<Case> {
        need(jar.isFile, "the 1.4.0 jar is not at $jar")
        need(git("rev-parse", "HEAD") != null, "git is not available")
        need(
            git("rev-parse", "--is-shallow-repository")?.trim() != "true",
            "a shallow clone holds no history",
        )
        need(git("rev-parse", "--verify", "$TAG^{commit}") != null, "no $TAG tag")
        val listing = git("ls-tree", "-r", "--name-only", TAG, "--", "examples", RESOURCES)
        assertNotNull(listing, "git ls-tree failed for $TAG")
        val dirs =
            listing
                .lineSequence()
                .filter { it.endsWith(".schemata") }
                .map { it.substringBeforeLast('/') }
                .distinct()
                .toList()
        val outputs =
            dirs
                .filter {
                    it.startsWith("examples/") ||
                        it.startsWith("$RESOURCES/corpus/") ||
                        Regex("$RESOURCES/evolution/[^/]+/(old|new)").matches(it)
                }
                .map { Case(caseName(it), it) }
        val fixtures =
            dirs
                .filter { Regex("$RESOURCES/diagnostics/[^/]+").matches(it) }
                .mapNotNull { fixture(it, listing) }
        return (outputs + fixtures).sortedBy { it.name }
    }

    /**
     * A diagnostics fixture checked as a schema is, or null for one that imports, diffs, or
     * migrates: its header names its targets and `strict`, as the fixture runner reads them.
     */
    private fun fixture(dir: String, listing: String): Case? {
        val files = listing.lineSequence().filter { it.substringBeforeLast('/') == dir }.toList()
        if (files.any { it.substringAfterLast('.') in setOf("xsd", "proto", "sql") }) return null
        val header =
            files
                .firstOrNull { it.endsWith("/expected.txt") }
                ?.let { git("show", "$TAG:$it") }
                ?.lineSequence()
                ?.firstOrNull()
                ?.takeIf { it.startsWith("#") }
        if (header != null && ("diff=" in header || "migrate=" in header || "import=" in header))
            return null
        val targets =
            header?.let { Regex("targets=([a-z,]+)").find(it) }?.groupValues?.get(1)?.split(',')
        return Case(caseName(dir), dir, codes = true, targets, header?.contains("strict") == true)
    }

    private fun caseName(dir: String): String = dir.removePrefix("$RESOURCES/")

    private fun check(case: Case, old: Map<Case, Future<Map<String, String>>>) {
        val before = old.getValue(case).get(5, TimeUnit.MINUTES)
        val sources = revision(case.dir)
        if (case.codes) compareCodes(case, before, newCodes(case, sources))
        else {
            val (after, tokens) = compileNew(sources)
            compare(case.name, before, after, tokens)
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

    /**
     * What the 1.4.0 jar makes of [case]: its output tree plus its diagnostics under
     * [diagnosticsFile], or for a fixture its codes alone under [codesFile].
     */
    private fun runOld(case: Case): Map<String, String> {
        val sources = revision(case.dir)
        val work = Files.createTempDirectory("equivalence").toFile()
        try {
            val src = File(work, "src").apply { mkdirs() }
            sources.forEach { (name, content) -> File(src, name).writeText(content) }
            val out = File(work, "out")
            val command =
                if (case.codes)
                    listOf("check", "--format", "json") +
                        (case.targets?.let { listOf("--target", it.joinToString(",")) }
                            ?: emptyList()) +
                        (if (case.strict) listOf("--strict") else emptyList())
                else listOf("compile", "--out", out.path, "--format", "json")
            val java = File(System.getProperty("java.home"), "bin/java").path
            val process =
                ProcessBuilder(listOf(java, "-jar", jar.absolutePath) + command + src.path)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
            val json = process.inputStream.bufferedReader().readText()
            assertTrue(process.waitFor(120, TimeUnit.SECONDS), "the 1.4.0 jar did not finish")
            if (case.codes) return mapOf(codesFile to codes(json))
            val tree =
                out.walkTopDown()
                    .filter { it.isFile }
                    .associate { it.relativeTo(out).invariantSeparatorsPath to it.readText() }
            return tree + (diagnosticsFile to diagnostics(json))
        } finally {
            work.deleteRecursively()
        }
    }

    private fun upgraded(sources: Map<String, String>): List<SourceInput> =
        sources.map { (name, content) ->
            when (val result = Upgrader.upgrade(content, name, schemaNames(sources))) {
                is FormatResult.Formatted -> SourceInput(name, result.text)
                is FormatResult.Failed -> fail("$name does not upgrade: ${result.diagnostics}")
            }
        }

    private fun schemaNames(sources: Map<String, String>): Set<String> =
        sources.mapNotNull { (name, content) -> Upgrader.schemaName(content, name) }.toSet()

    /**
     * This compiler's output tree for the upgraded sources, plus its diagnostics, and the names the
     * reference-by-key mask leaves out.
     */
    private fun compileNew(sources: Map<String, String>): Pair<Map<String, String>, Set<String>> {
        val inputs = upgraded(sources)
        val result = Pipeline.compile(inputs, Pipeline.targets)
        val report = Report.of(result, strict = false, checkOnly = false)
        val written = report.written.map { it.target }.toSet()
        val tree =
            result.targets
                .filter { it.name in written }
                .flatMap { t -> t.files.map { "${t.name}/${it.path}" to it.content } }
                .toMap()
        val tokens = Pipeline.analyze(inputs).schema?.let(::referenceNames) ?: emptySet()
        return tree + (diagnosticsFile to diagnostics(JsonRenderer.report(report, "out"))) to tokens
    }

    /**
     * The codes this compiler reports for a fixture's sources: the upgrade's own when a file does
     * not upgrade (a 1.x syntax error stays one), else those of checking the upgraded files.
     */
    private fun newCodes(case: Case, sources: Map<String, String>): String {
        val failed =
            sources.flatMap { (name, content) ->
                (Upgrader.upgrade(content, name, schemaNames(sources)) as? FormatResult.Failed)
                    ?.diagnostics
                    .orEmpty()
            }
        if (failed.isNotEmpty()) return failed.map { it.code.id }.sorted().joinToString(" ")
        val targets =
            case.targets?.map { Pipeline.targetNamed(it) ?: fail("unknown target $it") }
                ?: Pipeline.targets
        val result = Pipeline.check(upgraded(sources), targets, case.strict)
        return result.diagnostics.map { it.code.id }.sorted().joinToString(" ")
    }

    /**
     * The names a reference by key changes in the document targets' output: each stored field,
     * list, or map typed as a keyed model, by its own name and its 2.0 one, with every target's
     * `name` override of either; and each `<Target>Key` a composite key declares.
     */
    private fun referenceNames(schema: Schema): Set<String> {
        val out = mutableSetOf<String>()
        fun keyed(type: Type): RecordType? =
            when (type) {
                is Ref ->
                    (schema.lookupOrNull(type.target) as? RecordType)?.takeIf {
                        !type.relation.embed && it.keyFields().isNotEmpty()
                    }
                is ListOf -> keyed(type.element)
                is MapOf -> keyed(type.value)
                else -> null
            }
        fun keyRecord(model: RecordType) {
            out += keyRecordName(model.qualifiedName).simpleName
            model.annotations.entries.values.forEach { keys ->
                (keys["name"] as? AnnotationValue.Str)?.let { out += it.value + "Key" }
            }
        }
        schema.namespaces
            .flatMap { ns -> ns.declarations.flatMap { it.selfAndNested() } }
            .forEach { decl ->
                when (decl) {
                    is RecordType ->
                        decl.storedFields.forEach { field ->
                            val model = keyed(field.type) ?: return@forEach
                            val key = model.keyFields()
                            val overrides =
                                field.annotations.entries.values.mapNotNull {
                                    (it["name"] as? AnnotationValue.Str)?.value
                                }
                            (listOf(field.name) + overrides).forEach { name ->
                                out += name
                                if (key.size == 1 && field.type is Ref)
                                    out += "${name}_${key.single().name}"
                            }
                            if (key.size > 1) keyRecord(model)
                        }
                    is UnionType ->
                        decl.members.forEach { member ->
                            val model = keyed(member.type) ?: return@forEach
                            out += Names.snakeCase(model.name)
                            if (model.keyFields().size > 1) keyRecord(model)
                        }
                    else -> Unit
                }
            }
        return out
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

    /** The codes of a JSON report, sorted and space-separated. */
    private fun codes(json: String): String =
        DIAGNOSTIC.findAll(json).map { it.groupValues[1] }.sorted().joinToString(" ")

    private fun compareCodes(case: Case, old: Map<String, String>, new: String) {
        val before =
            old.getValue(codesFile)
                .split(' ')
                .filter { it.isNotEmpty() }
                .map { RETIRED[it] ?: it }
                .sorted()
                .joinToString(" ")
        val key = "${case.name}/$codesFile"
        val listed = allow[key]
        val problems =
            when {
                before != new && listed == null ->
                    listOf(
                        "$key differs and allow.txt has no line for it: 1.4.0 [$before], 2.0 [$new]"
                    )
                before == new && listed != null ->
                    listOf("allow.txt lists $key, which does not differ")
                // a retired check takes its fixture with it
                listed?.contains("retired check") == true &&
                    File("src/test/resources/${case.name}").isDirectory ->
                    listOf("$key is listed as a retired check, but its fixture still exists")
                else -> emptyList()
            }
        assertEquals(emptyList(), problems, problems.joinToString("\n"))
    }

    private fun compare(
        case: String,
        old: Map<String, String>,
        new: Map<String, String>,
        tokens: Set<String>,
    ) {
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
                path == diagnosticsFile -> problems += lostDiagnostics(key, before, after, tokens)
                reasons.all { it in MASKABLE } &&
                    mask(before, reasons, tokens) != mask(after, reasons, tokens) ->
                    problems +=
                        "$key differs beyond ${reasons.joinToString(" and ")}\n" +
                            diff(mask(before, reasons, tokens), mask(after, reasons, tokens))
            }
        }
        allow.keys
            .filter { it.startsWith("$case/") && it !in differing }
            .sorted()
            .forEach { problems += "allow.txt lists $it, which does not differ" }
        assertEquals(emptyList(), problems, problems.joinToString("\n\n"))
    }

    /**
     * A diagnostic list may change its text, and gain warnings about the fields a reference now
     * emits, but every code 1.4.0 reported must still be reported, and nothing new is an error or a
     * warning about anything else.
     */
    private fun lostDiagnostics(
        key: String,
        before: String,
        after: String,
        tokens: Set<String>,
    ): List<String> {
        fun lines(text: String) = text.lines().filter { it.isNotBlank() }
        fun code(line: String) = line.substringBefore(':')
        fun about(line: String) = tokens.any { mentions(line, it) }
        val remaining = lines(after).toMutableList()
        // a diagnostic unchanged matches itself; one whose text changed matches by code, a
        // diagnostic about no reference field first, since one about a reference may be new
        val changed = lines(before).filterNot { remaining.remove(it) }
        val lost =
            changed.filterNot { line ->
                val match =
                    remaining.firstOrNull { code(it) == code(line) && !about(it) }
                        ?: remaining.firstOrNull { code(it) == code(line) }
                match != null && remaining.remove(match)
            }
        val errors = remaining.filter { it.startsWith("error ") }
        val strays = remaining.filter { it.startsWith("warning ") && !about(it) }
        return listOfNotNull(
            lost.takeIf { it.isNotEmpty() }?.let { "$key loses diagnostics: ${it.map(::code)}" },
            errors.takeIf { it.isNotEmpty() }?.let { "$key gains errors: $it" },
            strays.takeIf { it.isNotEmpty() }?.let { "$key gains warnings about no reference: $it" },
        )
    }

    private fun mask(text: String, reasons: Set<String>, tokens: Set<String>): String {
        var out = text
        if ("type notes" in reasons) out = out.replace(NOTE, "$1…")
        if ("on delete" in reasons) out = out.replace(ON_DELETE, "")
        if ("reference by key" in reasons) out = references(out, tokens)
        return out
    }

    /**
     * [text] without each line naming one of [tokens] or importing another file, nor the lines
     * indented under it and the closing line that ends them; blank runs collapse and trailing
     * commas go, since a removed last member moves a comma.
     */
    private fun references(text: String, tokens: Set<String>): String {
        val lines = text.lines()
        val kept = mutableListOf<String>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (!IMPORT.containsMatchIn(line) && tokens.none { mentions(line, it) }) {
                kept += line
                i++
                continue
            }
            val indent = indentOf(line)
            i++
            while (i < lines.size && (lines[i].isBlank() || indentOf(lines[i]) > indent)) {
                if (lines[i].isBlank() && (i + 1 >= lines.size || indentOf(lines[i + 1]) <= indent))
                    break
                i++
            }
            if (i < lines.size && indentOf(lines[i]) == indent && CLOSER.matches(lines[i].trim()))
                i++
        }
        return kept
            .map { it.trimEnd().removeSuffix(",") }
            .fold(mutableListOf<String>()) { acc, l ->
                if (!(l.isBlank() && acc.lastOrNull()?.isBlank() == true)) acc += l
                acc
            }
            .joinToString("\n")
    }

    private fun indentOf(line: String): Int = line.length - line.trimStart().length

    private fun mentions(line: String, token: String): Boolean =
        Regex("(?<![A-Za-z0-9_])${Regex.escape(token)}(?![A-Za-z0-9_])").containsMatchIn(line)

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
        const val RESOURCES = "schemata-cli/src/test/resources"
        val REASONS =
            setOf(
                "reference by key",
                "on delete",
                "type notes",
                "message text",
                "keyword rename",
                "retired check",
                "upgrade refuses",
            )
        val MASKABLE = setOf("type notes", "on delete", "reference by key")

        /**
         * Codes 2.0 retired, each read as the code that reports the same mistake now: an unknown
         * refinement key is an unknown option, and a nullable or non-scalar key field is an option
         * on a type that cannot carry it.
         */
        val RETIRED = mapOf("SCH1037" to "SCH1049", "SCH2107" to "SCH1049")
        val NOTE = Regex("""((?://|--) schemata: ).*""")
        val ON_DELETE = Regex(""" ON DELETE (?:CASCADE|RESTRICT|SET NULL)""")
        val IMPORT = Regex("""^\s*import "|<xs:import |xmlns:""")
        val CLOSER = Regex("""^(?:[}\]]+[,;]?|</[A-Za-z:]+>)$""")
        val DIAGNOSTIC =
            Regex(
                """\{"code":"(SCH\d+)","severity":"(\w+)","category":"\w+","promoted":(?:true|false),"target":(null|"\w+"),"message":"((?:[^"\\]|\\.)*)""""
            )
    }
}
