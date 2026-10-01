package io.schemata.cli

import io.schemata.importer.xsd.ImportInput
import io.schemata.importer.xsd.XsdImporter
import io.schemata.lang.Severity
import io.schemata.target.xsd.XsdTarget
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * Every corpus and example case with an `expected/xsd` tree round trips: compiling it under the xsd
 * target, importing that output back, and compiling the import regenerates the same xsd byte for
 * byte, with no import diagnostics, and the import also compiles cleanly under every target.
 */
class ImportRoundTripTest {
    private val corpus = File("src/test/resources/corpus")
    private val examples = File("../examples")

    @TestFactory
    fun `importing the xsd target's output regenerates it byte for byte`(): List<DynamicTest> =
        (corpus.listFiles { f -> File(f, "expected/xsd").isDirectory }!! +
                examples.listFiles { f -> File(f, "expected/xsd").isDirectory }!!)
            .sortedBy { it.name }
            .map { case -> DynamicTest.dynamicTest(case.name) { check(case) } }

    private fun check(case: File) {
        val original = Pipeline.compile(TestSources.of(case), listOf(XsdTarget))
        assertFalse(original.hasErrors)
        val xsd = original.files.map { ImportInput(it.file.path, it.file.content) }
        val imported = XsdImporter.import(xsd)
        assertEquals(
            emptyList(),
            imported.diagnostics.map { "${it.code.id} ${it.message}" },
            "import of ${case.name}",
        )
        val again =
            Pipeline.compile(
                imported.files.map { SourceInput(it.path, it.content) },
                listOf(XsdTarget),
            )
        assertFalse(
            again.hasErrors,
            again.diagnostics.joinToString("\n") { "${it.code.id} ${it.message}" },
        )
        assertEquals(
            original.files.associate { it.file.path to it.file.content },
            again.files.associate { it.file.path to it.file.content },
            "regenerated xsd for ${case.name}",
        )
        val all =
            Pipeline.compile(
                imported.files.map { SourceInput(it.path, it.content) },
                Pipeline.targets,
            )
        assertFalse(
            all.hasErrors,
            "imported ${case.name} under all targets: " +
                all.diagnostics
                    .filter { it.severity == Severity.ERROR }
                    .joinToString("\n") { "${it.code.id} ${it.message}" },
        )
    }
}
