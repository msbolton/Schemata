package io.schemata.core

import io.schemata.core.annotations.AnnotationRegistry
import io.schemata.core.annotations.AnnotationSpec
import io.schemata.core.annotations.CoreAnnotations
import io.schemata.core.annotations.Element
import io.schemata.core.annotations.Role
import io.schemata.core.annotations.ValueKind
import io.schemata.lang.Parser
import kotlin.test.Test
import kotlin.test.assertEquals

class HelpTextTest {
    // A core-only registry has no target-style key (every core spec has target ""), so
    // UNKNOWN_ANNOTATION_KEY can never fire; add one fake target key to reach it.
    private val registryWithTarget =
        AnnotationRegistry(
            CoreAnnotations.specs +
                listOf(
                    AnnotationSpec(
                        target = "sql",
                        key = "key",
                        elements = setOf(Element.FIELD),
                        valueKind = ValueKind.FLAG,
                        role = Role.STRATEGY,
                    )
                )
        )

    private fun analyze(text: String, strict: Boolean = false) =
        Analyzer.analyze(
            listOf(Parser.parse(text, "t.schemata").file!!),
            AnalysisOptions(strictOrdinals = strict, annotations = registryWithTarget),
        )

    private fun help(text: String, code: String, strict: Boolean = false): String? =
        analyze(text, strict).diagnostics.single { it.code.id == code }.help

    @Test
    fun `null default suggests a nullable type`() {
        assertEquals(
            "add `?` to the field's type and drop the default; a nullable field is null when absent",
            help("namespace t\nrecord R { #1 x: string = null }", "SCH1044"),
        )
    }

    @Test
    fun `unknown refinement names an allowed one`() {
        assertEquals(
            "write one of the allowed refinements, for example `string(max = ...)`",
            help("namespace t\nrecord R { #1 x: string(size = 3) }", "SCH1037"),
        )
        assertEquals(
            "remove the parentheses; `bool` takes no refinements",
            help("namespace t\nrecord R { #1 x: bool(max = 3) }", "SCH1037"),
        )
    }

    @Test
    fun `unknown annotation key points at the targets command`() {
        assertEquals(
            "use one of the keys listed, or run `schemata targets` to see every key with its element and value",
            help("namespace t\nrecord R { @sql(bogus) #1 x: bool }", "SCH1016"),
        )
    }

    @Test
    fun `implicit ordinal under strict tells how to number`() {
        assertEquals(
            "write `#n` before every field and enum value, starting at #1 in declaration order",
            help("namespace t\nrecord R { x: bool }", "SCH1014", strict = true),
        )
    }
}
