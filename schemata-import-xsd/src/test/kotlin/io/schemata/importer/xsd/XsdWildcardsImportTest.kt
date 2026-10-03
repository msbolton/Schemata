package io.schemata.importer.xsd

import io.schemata.importer.ImportInput
import io.schemata.importer.Imported
import io.schemata.importer.SchemataUnit
import io.schemata.importer.UnitAnnotation
import io.schemata.importer.UnitRecord
import io.schemata.importer.UnitType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class XsdWildcardsImportTest {
    private fun docs(vararg pathAndXml: Pair<String, String>): List<XsdDoc> =
        pathAndXml.map { (path, xml) ->
            val result = XsdReader.read(path, xml.trimIndent())
            assertEquals(emptyList(), result.diagnostics)
            result.doc!!
        }

    private fun lower(docs: List<XsdDoc>): Imported = XsdImport.lower(docs, null)

    private fun unit(imported: Imported, namespace: String): SchemataUnit =
        imported.units.single { it.namespace == namespace }

    private fun messages(imported: Imported): List<String> =
        imported.diagnostics.map { "${it.code.id} ${it.message}" }

    @Test
    fun `wildcards mixed content and anyType import as annotated strings`() {
        val xml =
            """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns="urn:schemata:t" targetNamespace="urn:schemata:t" elementFormDefault="qualified">
              <xs:complexType name="ParaType" mixed="true">
                <xs:sequence>
                  <xs:element name="bold" type="xs:string" maxOccurs="unbounded"/>
                  <xs:any namespace="##other" processContents="strict" minOccurs="0" maxOccurs="unbounded"/>
                  <xs:any processContents="lax"/>
                  <xs:element name="blob" type="xs:anyType"/>
                  <xs:element name="untyped"/>
                </xs:sequence>
                <xs:attribute name="lang" type="xs:string"/>
                <xs:anyAttribute processContents="skip"/>
              </xs:complexType>
              <xs:element name="para" type="ParaType"/>
            </xs:schema>
            """
        val imported = lower(docs("t.xsd" to xml))
        val para = unit(imported, "t").declarations.filterIsInstance<UnitRecord>().single()
        assertEquals(
            listOf("bold", "any", "any_2", "blob", "untyped", "text", "lang", "attributes"),
            para.fields.map { it.name },
        )
        val any = para.fields[1]
        assertEquals(
            UnitType.ListOf(UnitType.Scalar("string", emptyList()), false, emptyList()),
            any.type,
        )
        assertEquals(
            listOf(
                UnitAnnotation("xsd", "any", null),
                UnitAnnotation("xsd", "process", "\"strict\""),
                UnitAnnotation("xsd", "wildcard", "\"##other\""),
            ),
            any.annotations,
        )
        assertEquals(listOf(UnitAnnotation("xsd", "any", null)), para.fields[2].annotations)
        assertFalse(para.fields[2].nullable)
        assertEquals(listOf(UnitAnnotation("xsd", "any_type", null)), para.fields[3].annotations)
        assertEquals(listOf(UnitAnnotation("xsd", "any_type", null)), para.fields[4].annotations)
        val text = para.fields[5]
        assertTrue(text.nullable)
        assertEquals(listOf(UnitAnnotation("xsd", "mixed", null)), text.annotations)
        val attrs = para.fields[7]
        assertEquals(
            UnitType.MapOf(
                UnitType.Scalar("string", emptyList()),
                UnitType.Scalar("string", emptyList()),
                false,
                emptyList(),
            ),
            attrs.type,
        )
        assertEquals(
            listOf(
                UnitAnnotation("xsd", "any_attribute", null),
                UnitAnnotation("xsd", "process", "\"skip\""),
            ),
            attrs.annotations,
        )
        assertEquals(emptyList(), messages(imported).filter { "dropped" in it || "anyType" in it })
    }

    @Test
    fun `an unqualified schema carries its forms on the namespace`() {
        val xml =
            """<xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" targetNamespace="urn:schemata:t" attributeFormDefault="qualified"><xs:complexType name="AType"><xs:sequence/><xs:attribute name="x" type="xs:string" form="unqualified"/></xs:complexType></xs:schema>"""
        val imported = lower(docs("t.xsd" to xml))
        val t = unit(imported, "t")
        assertEquals(
            listOf(
                UnitAnnotation("xsd", "element_form", "\"unqualified\""),
                UnitAnnotation("xsd", "attribute_form", "\"qualified\""),
            ),
            t.annotations,
        )
        assertTrue(
            "SCH2403 attribute 'x': form 'unqualified' differs from the schema default; dropped" in
                messages(imported)
        )
    }

    @Test
    fun `a wildcard without processContents is strict`() {
        val xml =
            """<xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" targetNamespace="urn:schemata:t"><xs:complexType name="AType"><xs:sequence><xs:any/></xs:sequence></xs:complexType></xs:schema>"""
        val a =
            unit(lower(docs("t.xsd" to xml)), "t")
                .declarations
                .filterIsInstance<UnitRecord>()
                .single()
        assertEquals(
            listOf(
                UnitAnnotation("xsd", "any", null),
                UnitAnnotation("xsd", "process", "\"strict\""),
            ),
            a.fields.single().annotations,
        )
    }

    @Test
    fun `an include with other form defaults is noted once`() {
        val main =
            ImportInput(
                "main.xsd",
                """<xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" targetNamespace="urn:schemata:shop" elementFormDefault="qualified"><xs:include schemaLocation="parts.xsd"/><xs:include schemaLocation="same.xsd"/></xs:schema>""",
            )
        val parts =
            ImportInput(
                "parts.xsd",
                """<xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" targetNamespace="urn:schemata:shop"><xs:complexType name="PartType"><xs:sequence><xs:element name="n" type="xs:string"/></xs:sequence></xs:complexType></xs:schema>""",
            )
        val same =
            ImportInput(
                "same.xsd",
                """<xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" targetNamespace="urn:schemata:shop" elementFormDefault="qualified"/>""",
            )
        val result = XsdImporter.import(listOf(main, parts, same))
        assertEquals(
            listOf("SCH2403 parts.xsd: form defaults differ from the including document; dropped"),
            result.diagnostics.map { "${it.code.id} ${it.message}" },
        )
    }
}
