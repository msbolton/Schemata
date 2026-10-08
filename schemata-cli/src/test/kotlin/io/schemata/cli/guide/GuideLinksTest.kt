package io.schemata.cli.guide

import io.schemata.cli.Pipeline
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * Every relative link in the README and the guide points at a path that exists, every `#fragment`
 * at a heading, and none into `docs/`, which is not shipped; the README's quoted compiler output is
 * what the compiler writes.
 */
class GuideLinksTest {
    private val files =
        listOf(File(Guide.root, "README.md")) +
            Guide.dir.listFiles { f -> f.extension == "md" }!!.sortedBy { it.name }

    @TestFactory
    fun `relative links resolve`(): List<DynamicTest> =
        files.flatMap { file ->
            Guide.relativeLinks(file.readText()).map { link ->
                DynamicTest.dynamicTest("${file.name} -> $link") {
                    val target = File(file.parentFile, link).canonicalFile
                    assertTrue(target.exists(), "${file.name} links to missing $link")
                }
            }
        }

    @TestFactory
    fun `cross-references resolve to a heading`(): List<DynamicTest> =
        files.flatMap { file ->
            Guide.crossReferences(file.readText()).map { ref ->
                DynamicTest.dynamicTest("${file.name} -> ${ref.path}#${ref.fragment}") {
                    val target = if (ref.path.isEmpty()) file else File(file.parentFile, ref.path)
                    assertTrue(target.isFile, "${file.name} links to missing ${ref.path}")
                    assertTrue(
                        ref.fragment in Guide.headingAnchors(target.readText()),
                        "${file.name} links to ${ref.path}#${ref.fragment}, which is no heading",
                    )
                }
            }
        }

    @Test
    fun `heading anchors follow the rules the links assume`() {
        val anchors =
            Guide.headingAnchors(
                "# Top\n## 23. Upgrading from 1.x\n## `@proto` keys\n## Same\n## Same\n" +
                    "```text\n# not a heading\n```\n"
            )
        assertEquals(setOf("top", "23-upgrading-from-1x", "proto-keys", "same", "same-1"), anchors)
        assertFalse("nope" in anchors)
    }

    @Test
    fun `no page links into the docs directory`() {
        val docs = File(Guide.root, "docs").canonicalFile
        for (file in files) {
            Guide.relativeLinks(file.readText()).forEach { link ->
                val target = File(file.parentFile, link).canonicalFile
                assertFalse(
                    target == docs || target.path.startsWith(docs.path + File.separator),
                    "${file.name} links into docs/: $link",
                )
            }
        }
    }

    /**
     * The README quotes the start of each generated file under "`out/<target>/<file>` begins:";
     * those lines are the first lines of what compiling the quick start's schema writes. The
     * command blocks, and the install and usage blocks, show no output and are not compared.
     */
    @Test
    fun `README output blocks begin the files the quick start compiles to`() {
        val readme = File(Guide.root, "README.md").readText()
        val lines = readme.lines()
        val schema = Guide.blocks(readme, "schemata").single()
        val compiled = Pipeline.compile(Guide.sources(schema.body), Pipeline.targets)
        assertFalse(compiled.hasErrors, "the quick start schema does not compile")
        val written = compiled.files.associate { "${it.target}/${it.file.path}" to it.file.content }
        val labelled =
            listOf("proto", "sql", "xml", "json")
                .flatMap { language -> Guide.blocks(readme, language) }
                .sortedBy { it.line }
        assertTrue(labelled.isNotEmpty(), "the README quotes no output")
        for (block in labelled) {
            val intro = lines[block.line - 4]
            val path =
                Regex("^`out/([^`]+)` begins:$").find(intro)?.groupValues?.get(1)
                    ?: error("README.md:${block.line} has no \"`out/<file>` begins:\" line")
            val content = written[path] ?: error("README.md:${block.line}: $path is not written")
            assertTrue(
                content.startsWith(block.body.trimEnd()),
                "README.md:${block.line} is not the start of $path:\n${content.take(600)}",
            )
        }
    }

    @Test
    fun `the stability page is linked from the readme and the reference`() {
        assertTrue(File(Guide.dir, "stability.md").isFile, "guide/stability.md is missing")
        assertTrue(File(Guide.root, "README.md").readText().contains("](guide/stability.md)"))
        val opening = File(Guide.dir, "reference.md").readLines().take(12).joinToString("\n")
        assertTrue(opening.contains("](stability.md)"), "the reference's opening does not link it")
    }

    @Test
    fun `the stability page names every code family and every target`() {
        val text = File(Guide.dir, "stability.md").readText()
        listOf(
                "SCH0",
                "SCH1",
                "SCH20",
                "SCH21",
                "SCH22",
                "SCH23",
                "SCH24",
                "SCH25",
                "SCH26",
                "SCH27",
            )
            .forEach { assertTrue(text.contains(it), "$it is not mentioned") }
        val families =
            "Every family stays where it is: SCH0 for syntax, SCH1 for the language and core " +
                "checks, SCH20, SCH21, SCH22, and SCH23 for the Protobuf, Postgres, XML Schema, " +
                "and JSON Schema targets, SCH24 for import, SCH25 for evolution, SCH26 for the " +
                "OpenAPI target, SCH27 for migration."
        assertTrue(
            text.replace(Regex("\\s+"), " ").contains(families),
            "the families sentence is missing or changed",
        )
    }
}
