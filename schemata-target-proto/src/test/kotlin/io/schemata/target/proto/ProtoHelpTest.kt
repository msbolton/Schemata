package io.schemata.target.proto

import io.schemata.core.AnalysisOptions
import io.schemata.core.Analyzer
import io.schemata.core.annotations.AnnotationRegistry
import io.schemata.core.annotations.CoreAnnotations
import io.schemata.lang.Diagnostic
import io.schemata.lang.DiagnosticCode
import io.schemata.lang.Parser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProtoHelpTest {
    private fun diagnostics(text: String, code: DiagnosticCode): List<Diagnostic> {
        val schema =
            Analyzer.analyze(
                    listOf(Parser.parse(text, "t.schemata").file!!),
                    AnalysisOptions(
                        annotations =
                            AnnotationRegistry(CoreAnnotations.specs + ProtoAnnotations.specs)
                    ),
                )
                .schema!!
        return ProtoTarget.lower(schema).diagnostics.filter { it.code == code }
    }

    private fun lossyHelp(text: String): List<String?> =
        diagnostics(text, ProtoCodes.LOSSY).map { it.help }

    @Test
    fun `every lossy diagnostic carries help`() {
        val helps =
            lossyHelp(
                """
                namespace t

                enum Color { #1 red }

                record R {
                  #1 name: string(max = 3) = "ab"
                  #2 tags: list<string>?
                  #3 attrs: map<string, string?>
                  #4 amount: decimal(10, 2)
                }
                """
                    .trimIndent()
            )
        assertEquals(emptyList(), helps.filter { it == null })
        assertEquals(
            listOf(
                "keep the synthesized zero value; proto3 reads an unset enum as 0",
                "enforce the refinement in application code; Protobuf carries no constraints",
                "drop the default or apply it in application code; proto3 has no field defaults",
                "declare the list as `list<T>` with non-nullable elements; an empty list already means absent",
                "declare the map as `map<K, V>` with non-nullable values; a missing key already means absent",
            ),
            helps.take(5),
        )
    }

    @Test
    fun `nested collections carry help to wrap the element in a record`() {
        val found =
            diagnostics(
                """
                namespace t

                record R { #1 g: list<list<int32>> }
                """
                    .trimIndent(),
                ProtoCodes.UNSUPPORTED_NESTING,
            )
        assertEquals(1, found.size)
        assertTrue(found.single().message.endsWith("has a collection element"))
        assertEquals("wrap the element in a record", found.single().help)
    }

    @Test
    fun `invalid field numbers carry help`() {
        val found =
            diagnostics(
                """
                namespace t

                record R { #600000000 x: bool }
                """
                    .trimIndent(),
                ProtoCodes.INVALID_FIELD_NUMBER,
            )
        assertEquals(1, found.size)
        assertTrue(found.single().help != null)
    }
}
