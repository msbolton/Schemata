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
    // UNKNOWN_ANNOTATION_KEY can never fire; add fake target keys to reach it, one per element,
    // so the choice between them can be tested too.
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
                    ),
                    AnnotationSpec(
                        target = "sql",
                        key = "table",
                        elements = setOf(Element.RECORD),
                        valueKind = ValueKind.STRING,
                        role = Role.NAME,
                    ),
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
            help("schema t\nmodel R { #1 x string = null }", "SCH1044"),
        )
    }

    @Test
    fun `an unknown or misplaced option says what to do`() {
        assertEquals(
            "write one of the listed options, or remove it",
            help("schema t\nmodel R { #1 x string { size 3 } }", "SCH1049"),
        )
        assertEquals(
            "remove the option, or move it to the element type",
            help("schema t\nmodel R { #1 x bool { max 3 } }", "SCH1049"),
        )
    }

    @Test
    fun `unknown annotation key names an allowed one for the element it was written on`() {
        assertEquals(
            "write one of the listed keys, for example `@sql(key)`",
            help("schema t\nmodel R { @sql(bogus) #1 x bool }", "SCH1016"),
        )
        assertEquals(
            "write one of the listed keys, for example `@sql(table)`",
            help("schema t\n@sql(bogus) model R { #1 x bool }", "SCH1016"),
        )
    }

    @Test
    fun `needs at least one key suggests one allowed for the element it was written on`() {
        assertEquals("write `@sql(key)`", help("schema t\nmodel R { @sql() #1 x bool }", "SCH1018"))
        assertEquals(
            "write `@sql(table = \"…\")`",
            help("schema t\n@sql() model R { #1 x bool }", "SCH1018"),
        )
    }

    @Test
    fun `implicit ordinal under strict tells how to number`() {
        assertEquals(
            "write `#n` before every field and enum value, starting at #1 in declaration order",
            help("schema t\nmodel R { x bool }", "SCH1014", strict = true),
        )
    }

    @Test
    fun `refinement and default diagnostics carry help`() {
        assertEquals(
            "write `decimal(p, s)`, for example `decimal(19, 4)`",
            help("schema t\nmodel R { #1 x decimal }", "SCH1040"),
        )
        assertEquals(
            "write `= true` or `= false`",
            help("schema t\nmodel R { #1 x bool = 1 }", "SCH1042"),
        )
        assertEquals(
            "use a default of at most 3 characters, or raise max",
            help("schema t\nmodel R { #1 x string { max 3 } = \"abcd\" }", "SCH1043"),
        )
        assertEquals(
            "keep one of them",
            help("schema t\nmodel R { @deprecated(\"a\") @deprecated(\"b\") #1 x bool }", "SCH1036"),
        )
    }

    @Test
    fun `a core key's value help spells the key without a target`() {
        assertEquals(
            "write `@deprecated(\"…\")`",
            help("schema t\nmodel R { @deprecated(3) #1 x bool }", "SCH1018"),
        )
    }
}
