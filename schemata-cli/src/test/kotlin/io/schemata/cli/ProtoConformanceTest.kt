package io.schemata.cli

import io.schemata.target.Target
import io.schemata.target.proto.ProtoLayout
import io.schemata.target.proto.ProtoTarget
import io.schemata.testkit.Protoc
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * Every corpus case renders exactly its expected tree and compiles under real protoc. Cases live in
 * `src/test/resources/corpus/<case>/` with `.schemata` inputs at the root and the expected files
 * under `expected/proto/` (and, for a case that pins the per-type layout, `expected/proto-type/`);
 * `SCHEMATA_GOLDEN_UPDATE=1` rewrites the expected tree.
 */
class ProtoConformanceTest {
    private val corpus = File("src/test/resources/corpus")

    @TestFactory
    fun `corpus cases render their expected tree and compile under protoc`(): List<DynamicTest> =
        corpus
            .listFiles { f -> f.isDirectory && File(f, "expected/proto").isDirectory }!!
            .sortedBy { it.name }
            .map { case ->
                DynamicTest.dynamicTest(case.name) { check(case, ProtoTarget, "proto") }
            }

    @TestFactory
    fun `corpus cases with a type-layout tree render it and compile under protoc`():
        List<DynamicTest> =
        corpus
            .listFiles { f -> f.isDirectory && File(f, "expected/proto-type").isDirectory }!!
            .sortedBy { it.name }
            .map { case ->
                DynamicTest.dynamicTest(case.name) {
                    check(case, ProtoTarget(ProtoLayout.TYPE), "proto-type")
                }
            }

    private fun check(case: File, target: Target<*>, expectedName: String) {
        val inputs =
            case
                .listFiles { f -> f.extension == "schemata" }!!
                .sortedBy { it.name }
                .map { SourceInput(it.name, it.readText()) }
        val result = Pipeline.compile(inputs, listOf(target))
        assertFalse(
            result.hasErrors,
            result.diagnostics.joinToString("\n") { "${it.code.id} ${it.message}" },
        )
        val actual = result.files.associate { it.file.path to it.file.content }
        val expectedDir = File(case, "expected/$expectedName")
        if (System.getenv("SCHEMATA_GOLDEN_UPDATE") == "1") {
            expectedDir.deleteRecursively()
            actual.forEach { (path, content) ->
                File(expectedDir, path).apply {
                    parentFile.mkdirs()
                    writeText(content)
                }
            }
        }
        val expected =
            expectedDir
                .walkTopDown()
                .filter { it.isFile }
                .associate {
                    it.relativeTo(expectedDir).path.replace(File.separatorChar, '/') to
                        it.readText()
                }
        assertEquals(expected.keys, actual.keys, "output paths for ${case.name}")
        expected.forEach { (path, content) ->
            assertEquals(
                content,
                actual.getValue(path),
                "$path in ${case.name}; run with SCHEMATA_GOLDEN_UPDATE=1 to accept",
            )
        }
        assertNull(Protoc.compile(actual))
    }
}
