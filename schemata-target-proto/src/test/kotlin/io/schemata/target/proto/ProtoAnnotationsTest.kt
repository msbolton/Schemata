package io.schemata.target.proto

import io.schemata.core.AnalysisOptions
import io.schemata.core.Analyzer
import io.schemata.core.annotations.AnnotationRegistry
import io.schemata.core.annotations.CoreAnnotations
import io.schemata.lang.Parser
import io.schemata.target.bool
import kotlin.test.Test
import kotlin.test.assertEquals

class ProtoAnnotationsTest {
    private data class Checked(val diagnostics: List<String>, val flag: Boolean?)

    private fun check(source: String): Checked {
        val file = Parser.parse(source, "shop.schemata").file!!
        val analysis =
            Analyzer.analyze(
                listOf(file),
                AnalysisOptions(
                    annotations = AnnotationRegistry(CoreAnnotations.specs + ProtoAnnotations.specs)
                ),
            )
        return Checked(
            analysis.diagnostics.map { "${it.code.id} ${it.message}" },
            analysis.schema
                ?.namespaces
                ?.singleOrNull()
                ?.annotations
                ?.bool("proto", "java_multiple_files"),
        )
    }

    @Test
    fun `language options are schema keys the core checks`() {
        val ok = check("schema shop @proto(java_multiple_files: true)\n\nmodel A { #1 id uuid }\n")
        assertEquals(emptyList(), ok.diagnostics)
        assertEquals(true, ok.flag)

        assertEquals(
            listOf("SCH1018 @proto(java_multiple_files) expects true or false"),
            check("schema shop @proto(java_multiple_files: \"yes\")\n\nmodel A { #1 id uuid }\n")
                .diagnostics,
        )
        assertEquals(
            listOf("SCH1018 @proto(java_package) expects a string"),
            check("schema shop @proto(java_package: true)\n\nmodel A { #1 id uuid }\n").diagnostics,
        )
        val onModel =
            check("schema shop\n\nmodel A { #1 id uuid  @@proto(java_package: \"x\") }\n")
                .diagnostics
        assertEquals(1, onModel.size)
        assertEquals(
            true,
            onModel.single().startsWith("SCH1017 ") &&
                onModel.single().endsWith("not allowed on a model; allowed on: schema"),
        )
    }
}
