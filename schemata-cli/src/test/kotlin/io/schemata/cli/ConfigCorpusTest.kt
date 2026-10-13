package io.schemata.cli

import io.schemata.config.ConfigLoader
import io.schemata.config.ProjectConfig
import io.schemata.target.ProtoLayout
import io.schemata.target.Target
import io.schemata.target.proto.ProtoTarget
import io.schemata.testkit.Protoc
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * Every configuration case compiles with its `schemata.toml` to exactly its expected trees
 * (SCH-102). Cases live in `src/test/resources/config/<case>/` with the file and the `.schemata`
 * inputs at the root and one `expected/<target>/` tree per target the file selects; a target that
 * writes nothing has no tree. The file's `[compile]` table picks the targets, the strictness and
 * the Protobuf layout as `compile` would; the Protobuf tree must compile under protoc.
 * `SCHEMATA_GOLDEN_UPDATE=1` rewrites the expected trees.
 */
class ConfigCorpusTest {
    private val corpus = File("src/test/resources/config")

    @TestFactory
    fun `configured cases render their expected trees and compile under protoc`():
        List<DynamicTest> =
        corpus
            .listFiles { f -> f.isDirectory && File(f, ConfigLoader.FILE_NAME).isFile }!!
            .sortedBy { it.name }
            .map { case -> DynamicTest.dynamicTest(case.name) { check(case) } }

    private fun check(case: File) {
        val config = load(case)
        val inputs =
            case
                .listFiles { f -> f.extension == "schemata" }!!
                .sortedBy { it.name }
                .map { SourceInput(it.name, it.readText()) }
        val strict = config.compile.strict?.value == true
        val result = Pipeline.compile(inputs, targets(config), strict, config)
        assertFalse(
            result.hasErrors,
            result.diagnostics.joinToString("\n") { "${it.code.id} ${it.message}" },
        )
        val actual =
            result.targets.associate { t -> t.name to t.files.associate { it.path to it.content } }
        val expectedRoot = File(case, "expected")
        if (System.getenv("SCHEMATA_GOLDEN_UPDATE") == "1") {
            expectedRoot.deleteRecursively()
            actual.forEach { (target, files) ->
                files.forEach { (path, content) ->
                    File(expectedRoot, "$target/$path").apply {
                        parentFile.mkdirs()
                        writeText(content)
                    }
                }
            }
        }
        val trees = expectedRoot.listFiles { f -> f.isDirectory }.orEmpty().map { it.name }.toSet()
        assertEquals(
            actual.filterValues { it.isNotEmpty() }.keys,
            trees,
            "expected trees for ${case.name}",
        )
        actual.forEach { (target, files) ->
            val expectedDir = File(expectedRoot, target)
            val expected =
                expectedDir
                    .walkTopDown()
                    .filter { it.isFile }
                    .associate {
                        it.relativeTo(expectedDir).path.replace(File.separatorChar, '/') to
                            it.readText()
                    }
            assertEquals(expected.keys, files.keys, "$target output paths for ${case.name}")
            expected.forEach { (path, content) ->
                assertEquals(
                    content,
                    files.getValue(path),
                    "$target/$path in ${case.name}; run with SCHEMATA_GOLDEN_UPDATE=1 to accept",
                )
            }
        }
        actual[ProtoTarget.name]?.takeIf { it.isNotEmpty() }?.let { assertNull(Protoc.compile(it)) }
    }

    private fun load(case: File): ProjectConfig {
        val loaded =
            ConfigLoader.load(
                File(case, ConfigLoader.FILE_NAME).toPath(),
                Pipeline.annotations,
                Pipeline.targets.map { it.name },
            )
        return assertNotNull(
            loaded.config,
            loaded.diagnostics.joinToString("\n") { "${it.code.id} ${it.message}" },
        )
    }

    /** `[compile] targets`, else every target, with `[compile] proto_layout` on the proto one. */
    private fun targets(config: ProjectConfig): List<Target<*>> {
        val selected =
            config.compile.targets?.value?.map { assertNotNull(Pipeline.targetNamed(it)) }
                ?: Pipeline.targets
        val layout =
            config.compile.protoLayout?.value?.let { ProtoLayout.of(it) } ?: return selected
        return selected.map { if (it.name == ProtoTarget.name) ProtoTarget(layout) else it }
    }
}
