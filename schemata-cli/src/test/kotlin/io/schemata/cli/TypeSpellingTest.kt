package io.schemata.cli

import io.schemata.core.Analyzer
import io.schemata.core.ir.QualifiedName
import io.schemata.core.ir.RecordType
import io.schemata.importer.NoteText
import io.schemata.importer.SchemataEmitter
import io.schemata.importer.SchemataUnit
import io.schemata.importer.UnitField
import io.schemata.importer.UnitRecord
import io.schemata.lang.Parser
import io.schemata.lang.format.FormatResult
import io.schemata.lang.upgrade.Upgrader
import io.schemata.target.TypeText
import kotlin.test.assertEquals
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * The three printers that spell a type in 2.0 — the upgrader from a 1.x type, the importers'
 * emitter from an imported one, and the targets' type text from an analysed one — work on three
 * models, so one table of spellings drives all three: each row's 2.0 spelling is what every printer
 * writes for it.
 */
class TypeSpellingTest {
    /** A 1.x type and the 2.0 spelling every printer gives it. */
    private val rows =
        listOf(
            "int32" to "int32",
            "string(max = 5, pattern = \"^a\")" to "string { max 5, match \"^a\" }",
            "decimal(10, 2, min = 0)" to "decimal(10, 2) { min 0 }",
            "list<string?>?" to "string?[]?",
            "list<string(max = 5)?>(min = 1)?" to "string?[]? { minItems 1, max 5 }",
            "list<list<int32>>" to "list<int32[]>",
            "list<map<string, int32>>" to "map<string, int32>[]",
            "list<map<string, int32>(max = 3)>(max = 10)" to
                "list<map<string, int32> { maxItems 3 }> { maxItems 10 }",
            "map<string, int32(min = 1)?>(max = 4)" to
                "map<string, int32? { min 1 }> { maxItems 4 }",
        )

    @TestFactory
    fun `the upgrader spells each 1x type as the table does`(): List<DynamicTest> =
        rows.map { (v1, v2) ->
            DynamicTest.dynamicTest(v1) {
                val r = Upgrader.upgrade("namespace s\nrecord R { f: $v1 }", "s.schemata")
                val text = (r as FormatResult.Formatted).text
                assertEquals("schema s\n\nmodel R { f $v2 }\n", text)
            }
        }

    @TestFactory
    fun `the target type text spells each analysed type as the table does`(): List<DynamicTest> =
        rows.map { (_, v2) ->
            DynamicTest.dynamicTest(v2) {
                val file = Parser.parse("schema s\nmodel R { f $v2 }", "s.schemata").file!!
                val analysis = Analyzer.analyze(listOf(file))
                assertEquals(emptyList(), analysis.diagnostics.map { it.message })
                val field =
                    (analysis.schema!!.lookup(QualifiedName("s", listOf("R"))) as RecordType)
                        .fields
                        .single()
                assertEquals(v2, TypeText.of(field.type, field.nullable))
            }
        }

    @TestFactory
    fun `the emitter spells each imported type as the table does`(): List<DynamicTest> =
        rows.map { (_, v2) ->
            DynamicTest.dynamicTest(v2) {
                val parsed = NoteText.parse(v2)!!
                val unit =
                    SchemataUnit(
                        namespace = "s",
                        doc = null,
                        imports = emptyList(),
                        declarations =
                            listOf(
                                UnitRecord(
                                    "R",
                                    listOf(
                                        UnitField(
                                            "f",
                                            parsed.type!!,
                                            parsed.nullable,
                                            null,
                                            null,
                                            emptyList(),
                                        )
                                    ),
                                    emptyList(),
                                    null,
                                    emptyList(),
                                )
                            ),
                        sourcePath = "s.schemata",
                    )
                val lines = SchemataEmitter.emit(unit).lines()
                val field = lines[lines.indexOf("model R {") + 1].trim()
                assertEquals("f $v2", field)
            }
        }
}
