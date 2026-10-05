package io.schemata.cli

import io.schemata.importer.ImportInput
import io.schemata.importer.Importer
import io.schemata.importer.proto.ProtoImporter
import io.schemata.importer.sql.SqlImporter
import io.schemata.importer.xsd.XsdImporter
import io.schemata.lang.Severity
import io.schemata.target.Target
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
 * Every corpus and example case with an `expected/xsd`, `expected/proto`, or `expected/sql` tree
 * round trips through that format: compiling it under the format's target, importing that output
 * back (each file named by its output path, which is also its path under the output root), and
 * compiling the import regenerates the same files byte for byte, with no import diagnostics, and
 * the import compiles cleanly under proto, xsd, and jsonschema; the sql target cannot carry a key
 * the format never declared, so it is checked separately, only for errors other than a missing key.
 * The one diagnostic a round trip may report is SQL's for a record or union stored as json, whose
 * fields the DDL never held. A SQL file holding nothing but its `CREATE SCHEMA` (a namespace whose
 * every record is embedded elsewhere) imports as no namespace, so it is not expected back.
 */
class ImportRoundTripTest {
    private val corpus = File("src/test/resources/corpus")
    private val examples = File("../examples")

    private val formats: List<Triple<String, Target<*>, Importer>> =
        listOf(
            Triple("xsd", XsdTarget, XsdImporter),
            Triple("proto", ProtoTarget, ProtoImporter),
            Triple("sql", SqlTarget, SqlImporter),
        )

    /** A SQL file that creates its schema and nothing else. */
    private val schemaOnly = Regex("CREATE SCHEMA IF NOT EXISTS \"[^\"]+\";\\s*")

    /** The import diagnostics a format's round trip may report, by format. */
    private val tolerated: Map<String, Regex> =
        mapOf("sql" to Regex("SCH2403 .*: '.*' is stored as json; its fields are not in the DDL"))

    @TestFactory
    fun `importing a target's output regenerates it byte for byte`(): List<DynamicTest> =
        formats.flatMap { (format, target, importer) ->
            (corpus.listFiles { f -> File(f, "expected/$format").isDirectory }!! +
                    examples.listFiles { f -> File(f, "expected/$format").isDirectory }!!)
                .sortedBy { it.name }
                .map { case ->
                    DynamicTest.dynamicTest("$format ${case.name}") {
                        check(case, format, target, importer)
                    }
                }
        }

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

    private fun check(case: File, format: String, target: Target<*>, importer: Importer) {
        val original = Pipeline.compile(TestSources.of(case), listOf(target))
        assertFalse(original.hasErrors)
        val output = original.files.map { ImportInput(it.file.path, it.file.content, it.file.path) }
        val imported = importer.import(output)
        val allowed = tolerated[format]
        assertEquals(
            emptyList(),
            imported.diagnostics
                .map { "${it.code.id} ${it.message}" }
                .filterNot { allowed?.matches(it) == true },
            "import of ${case.name}",
        )
        val again =
            Pipeline.compile(
                imported.files.map { SourceInput(it.path, it.content) },
                listOf(target),
            )
        assertFalse(
            again.hasErrors,
            again.diagnostics.joinToString("\n") { "${it.code.id} ${it.message}" },
        )
        assertEquals(
            original.files
                .filterNot { format == "sql" && schemaOnly.matches(it.file.content) }
                .associate { it.file.path to it.file.content },
            again.files.associate { it.file.path to it.file.content },
            "regenerated $format for ${case.name}",
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
