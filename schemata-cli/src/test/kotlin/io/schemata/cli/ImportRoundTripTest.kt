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

    /**
     * The one diagnostic a faithful proto round trip can produce: the note that a name which does
     * not invert (an rpc `GetURL` lowered to `get_url` with `@proto(name: "GetURL")`) was renamed.
     * Only the imported proto cases tolerate it; every other diagnostic fails the case.
     */
    private val protoRename = Regex("SCH2402 .*: renamed to '.*'")

    /** An xsd import names a schema after its file when the Schemata source never declared one. */
    private val xsdSchemaName = Regex("SCH2402 .*: schema name '.*' was derived from the file name")

    /**
     * A union or element member's own name is dropped, and the regenerated element is named after
     * its type.
     */
    private val xsdMemberName =
        Regex(
            "SCH2403 (union|element) '.*': member element name '.*' has no Schemata equivalent and is dropped; .*"
        )

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
            schema shop

            model Cfg {
              a     int32
              b     string?
              tags  string[] @xsd(list)
              sizes int32[]? { minItems 1, maxItems 4 } @xsd(list) @xsd(attribute)
              kinds Kind[]?  @xsd(list)

              @@xsd(all)
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

    /**
     * The `.schemata` files of every imported proto case's `expected/` tree compile to proto,
     * import back, and regenerate that proto byte for byte. A Schemata file imports what its
     * declarations reference, and the importer writes the same: a proto import whose types are
     * never used, an option-only import and an `import public` produce no line, so the round trip
     * is exact.
     */
    @TestFactory
    fun `imported proto cases regenerate their proto byte for byte`(): List<DynamicTest> =
        importedCases(
            { it.name.startsWith("proto-") },
            "proto",
            ProtoTarget,
            ProtoImporter,
            listOf(protoRename),
            File("src/test/resources/import"),
        )

    /**
     * The `.schemata` files of every imported xsd case's `expected/` tree compile to xsd, import
     * back, and regenerate that xsd byte for byte. The notes an xsd round trip may carry are the
     * schema name derived from a file name and the dropped name of a union or element member, which
     * the regenerated xsd names after its type.
     */
    @TestFactory
    fun `imported xsd cases regenerate their xsd byte for byte`(): List<DynamicTest> =
        importedCases(
            { case ->
                val inputs =
                    case
                        .walkTopDown()
                        .filter {
                            it.isFile &&
                                it.extension != "txt" &&
                                !it.relativeTo(case).path.startsWith("expected") &&
                                !it.relativeTo(case).path.startsWith("include")
                        }
                        .toList()
                inputs.isNotEmpty() && inputs.all { it.extension == "xsd" }
            },
            "xsd",
            XsdTarget,
            XsdImporter,
            listOf(xsdSchemaName, xsdMemberName),
            File("src/test/resources/import"),
        )

    private fun importedCases(
        selects: (File) -> Boolean,
        format: String,
        target: Target<*>,
        importer: Importer,
        allowed: List<Regex>,
        root: File,
    ): List<DynamicTest> =
        root
            .listFiles { f -> f.isDirectory && File(f, "expected").isDirectory && selects(f) }!!
            .sortedBy { it.name }
            .map { case ->
                DynamicTest.dynamicTest(case.name) {
                    val expected = File(case, "expected")
                    val sources =
                        expected
                            .walkTopDown()
                            .filter { it.isFile && it.extension == "schemata" }
                            .sortedBy { it.relativeTo(expected).path }
                            .map {
                                SourceInput(
                                    it.relativeTo(expected).path.replace(File.separatorChar, '/'),
                                    it.readText(),
                                )
                            }
                            .toList()
                    roundTrip(
                        case.name,
                        sources,
                        format,
                        target,
                        importer,
                        allowed,
                        File(expected, "sql-errors.txt"),
                    )
                }
            }

    private fun check(case: File, format: String, target: Target<*>, importer: Importer) =
        roundTrip(
            case.name,
            TestSources.of(case),
            format,
            target,
            importer,
            listOfNotNull(tolerated[format]),
        )

    private fun roundTrip(
        name: String,
        sources: List<SourceInput>,
        format: String,
        target: Target<*>,
        importer: Importer,
        allowed: List<Regex>,
        sqlErrorsFile: File? = null,
    ) {
        val original = Pipeline.compile(sources, listOf(target))
        assertFalse(original.hasErrors)
        val output = original.files.map { ImportInput(it.file.path, it.file.content, it.file.path) }
        val imported = importer.import(output)
        assertEquals(
            emptyList(),
            imported.diagnostics
                .map { "${it.code.id} ${it.message}" }
                .filterNot { line -> allowed.any { it.matches(line) } },
            "import of ${name}",
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
            "regenerated $format for ${name}",
        )
        val importedSources = imported.files.map { SourceInput(it.path, it.content) }
        val noSql =
            Pipeline.compile(importedSources, listOf(ProtoTarget, XsdTarget, JsonSchemaTarget))
        assertFalse(
            noSql.hasErrors,
            "imported ${name} under proto, xsd, and jsonschema: " +
                noSql.diagnostics
                    .filter { it.severity == Severity.ERROR }
                    .joinToString("\n") { "${it.code.id} ${it.message}" },
        )
        val sqlErrors =
            Pipeline.compile(importedSources, listOf(SqlTarget)).diagnostics.filter {
                it.severity == Severity.ERROR
            }
        // A case that records the sql errors its import has (namespaces sharing a last segment
        // lower to one Postgres schema) expects exactly those; any other must have none.
        val expectedSqlErrors =
            sqlErrorsFile?.takeIf { it.isFile }?.readText()?.lines()?.filter { it.isNotEmpty() }
                ?: emptyList()
        assertEquals(
            expectedSqlErrors,
            sqlErrors
                .filter { it.code != SqlCodes.MISSING_KEY }
                .map { "${it.code.id} ${it.message}" },
            "imported ${name} under sql had an error other than a missing key",
        )
    }
}
