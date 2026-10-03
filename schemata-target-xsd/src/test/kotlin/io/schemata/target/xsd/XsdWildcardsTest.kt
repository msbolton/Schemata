package io.schemata.target.xsd

import io.schemata.core.AnalysisOptions
import io.schemata.core.Analyzer
import io.schemata.core.annotations.AnnotationRegistry
import io.schemata.core.annotations.CoreAnnotations
import io.schemata.lang.Diagnostic
import io.schemata.lang.Parser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class XsdWildcardsTest {
    /** The analysis diagnostics and, when analysis succeeded, the lowering's and the text. */
    private fun compile(text: String): Pair<List<Diagnostic>, String?> {
        val analysis =
            Analyzer.analyze(
                listOf(Parser.parse(text.trimIndent(), "t.schemata").file!!),
                AnalysisOptions(
                    annotations = AnnotationRegistry(CoreAnnotations.specs + XsdAnnotations.specs)
                ),
            )
        val schema = analysis.schema ?: return analysis.diagnostics to null
        val lowered = XsdTarget.lower(schema)
        val rendered = XsdTarget.render(lowered.model).joinToString("\n") { it.content }
        return analysis.diagnostics + lowered.diagnostics to rendered
    }

    private fun render(text: String): String {
        val (diagnostics, rendered) = compile(text)
        assertEquals(emptyList(), diagnostics.map { "${it.code.id} ${it.message}" })
        return rendered!!
    }

    private fun diagnosticsOf(text: String): List<String> =
        compile(text).first.map { "${it.code.id} ${it.message}" }

    @Test
    fun `any fields render as wildcards in place`() {
        val xsd =
            render(
                """
                namespace t
                record Doc {
                  head: string
                  @xsd(any)
                  @xsd(process = "strict")
                  @xsd(wildcard = "##other")
                  extras: list<string>(min = 1)
                  @xsd(any)
                  tail: string?
                }
                """
            )
        assertTrue("""<xs:element name="head" type="xs:string"/>""" in xsd)
        assertTrue(
            """<xs:any maxOccurs="unbounded" namespace="##other" processContents="strict"/>""" in
                xsd
        )
        assertTrue("""<xs:any minOccurs="0" processContents="lax"/>""" in xsd)
    }

    @Test
    fun `an any attribute and mixed text render on the complex type`() {
        val xsd =
            render(
                """
                namespace t
                record Para {
                  @xsd(mixed)
                  text: string?
                  bold: list<string>
                  @xsd(any_attribute)
                  attributes: map<string, string>
                }
                """
            )
        assertTrue("""<xs:complexType name="ParaType" mixed="true">""" in xsd)
        assertFalse("""name="text"""" in xsd)
        assertTrue("""<xs:anyAttribute processContents="lax"/>""" in xsd)
    }

    @Test
    fun `any type renders the builtin and forms render on the schema element`() {
        val xsd =
            render(
                """
                @xsd(element_form = "unqualified")
                @xsd(attribute_form = "qualified")
                namespace t
                record Box { @xsd(any_type) content: string }
                """
            )
        assertTrue("""elementFormDefault="unqualified"""" in xsd)
        assertTrue("""attributeFormDefault="qualified"""" in xsd)
        assertTrue("""<xs:element name="content" type="xs:anyType"/>""" in xsd)
    }

    @Test
    fun `any type on a list repeats the element`() {
        val xsd =
            render(
                """
                namespace t
                record Box {
                  @xsd(any_type) parts: list<string>(min = 1)
                  @xsd(any_type) holes: list<string?>
                }
                """
            )
        assertTrue("""<xs:element name="parts" type="xs:anyType" maxOccurs="unbounded"/>""" in xsd)
        assertTrue(
            """<xs:element name="holes" type="xs:anyType" minOccurs="0" maxOccurs="unbounded" nillable="true"/>""" in
                xsd
        )
    }

    @Test
    fun `representation keys on the wrong shape are errors`() {
        val diagnostics =
            diagnosticsOf(
                """
                namespace t
                record R {
                  @xsd(any) n: int32
                  @xsd(any_attribute) m: map<string, int32>
                  @xsd(mixed) l: list<string>
                  @xsd(process = "lax") p: string
                }
                """
            )
        assertEquals(
            listOf(
                "SCH2204 field 'R.n': @xsd(any) is not allowed on a int32; it takes a string, string?, or list<string>",
                "SCH2204 field 'R.m': @xsd(any_attribute) is not allowed on a map<string, int32>; it takes a map<string, string>",
                "SCH2204 field 'R.l': @xsd(mixed) is not allowed on a list<string>; it takes a string or string?",
                "SCH2204 field 'R.p': @xsd(process) needs @xsd(any) or @xsd(any_attribute) on the same field",
            ),
            diagnostics,
        )
    }
}
