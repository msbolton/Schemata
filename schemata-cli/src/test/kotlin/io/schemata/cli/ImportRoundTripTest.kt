package io.schemata.cli

import io.schemata.importer.ImportInput
import io.schemata.importer.xsd.XsdImporter
import io.schemata.lang.Severity
import io.schemata.target.jsonschema.JsonSchemaTarget
import io.schemata.target.proto.ProtoTarget
import io.schemata.target.sql.SqlCodes
import io.schemata.target.sql.SqlTarget
import io.schemata.target.xsd.XsdTarget
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * Every corpus and example case with an `expected/xsd` tree round trips: compiling it under the xsd
 * target, importing that output back, and compiling the import regenerates the same xsd byte for
 * byte, with no import diagnostics, and the import compiles cleanly under proto, xsd, and
 * jsonschema; the sql target cannot carry a key the xsd never declared, so it is checked
 * separately, only for errors other than a missing key.
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

    @Test
    fun `all records and list fields round trip`() {
        val source =
            """
            namespace shop

            @xsd(all)
            record Cfg {
              a: int32
              b: string?
              @xsd(list)
              tags: list<string>
              @xsd(list)
              @xsd(attribute)
              sizes: list<int32>(min = 1, max = 4)?
              @xsd(list)
              kinds: list<Kind>?
            }

            enum Kind { small large }
            """
                .trimIndent()
        val original =
            Pipeline.compile(listOf(SourceInput("shop.schemata", source)), listOf(XsdTarget))
        assertFalse(original.hasErrors, original.diagnostics.joinToString("\n") { it.message })
        val xsd = original.files.map { ImportInput(it.file.path, it.file.content) }
        val imported = XsdImporter.import(xsd)
        assertEquals(emptyList(), imported.diagnostics.map { "${it.code.id} ${it.message}" })
        val again =
            Pipeline.compile(
                imported.files.map { SourceInput(it.path, it.content) },
                listOf(XsdTarget),
            )
        assertEquals(
            original.files.associate { it.file.path to it.file.content },
            again.files.associate { it.file.path to it.file.content },
        )
    }

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
        val sources = imported.files.map { SourceInput(it.path, it.content) }
        val noSql = Pipeline.compile(sources, listOf(ProtoTarget, XsdTarget, JsonSchemaTarget))
        assertFalse(
            noSql.hasErrors,
            "imported ${case.name} under proto, xsd, and jsonschema: " +
                noSql.diagnostics
                    .filter { it.severity == Severity.ERROR }
                    .joinToString("\n") { "${it.code.id} ${it.message}" },
        )
        val sqlErrors =
            Pipeline.compile(sources, listOf(SqlTarget)).diagnostics.filter {
                it.severity == Severity.ERROR
            }
        assertTrue(
            sqlErrors.all { it.code == SqlCodes.MISSING_KEY },
            "imported ${case.name} under sql had an error other than a missing key: " +
                sqlErrors.joinToString("\n") { "${it.code.id} ${it.message}" },
        )
    }
}
