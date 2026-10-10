package io.schemata.importer.xsd

import io.schemata.importer.ImportInput
import io.schemata.importer.Imported
import io.schemata.importer.SchemataEmitter
import io.schemata.importer.SchemataUnit
import io.schemata.importer.UnitAnnotation
import io.schemata.importer.UnitEnum
import io.schemata.importer.UnitRecord
import io.schemata.importer.UnitType
import io.schemata.importer.UnitUnion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class XsdContentModelsTest {
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

    private val string = UnitType.Scalar("string", emptyList())

    @Test
    fun `all becomes nullable fields with the all annotation`() {
        val xml =
            """<xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns="urn:schemata:t" targetNamespace="urn:schemata:t"><xs:complexType name="CfgType"><xs:all><xs:element name="a" type="xs:int"/><xs:element name="b" type="xs:string" minOccurs="0"/></xs:all></xs:complexType><xs:element name="cfg" type="CfgType"/></xs:schema>"""
        val imported = lower(docs("t.xsd" to xml))
        val cfg = unit(imported, "t").declarations.filterIsInstance<UnitRecord>().single()
        assertEquals(listOf(UnitAnnotation("xsd", "all", null)), cfg.annotations)
        assertEquals(listOf(false, true), cfg.fields.map { it.nullable })
        assertFalse(messages(imported).any { "xs:all" in it })
    }

    @Test
    fun `a repeated nested sequence becomes a group record`() {
        val xml =
            """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns="urn:schemata:t" targetNamespace="urn:schemata:t">
              <xs:complexType name="TrackType"><xs:sequence>
                <xs:element name="name" type="xs:string"/>
                <xs:sequence minOccurs="0" maxOccurs="unbounded"><xs:element name="lat" type="xs:double"/><xs:element name="lon" type="xs:double"/></xs:sequence>
                <xs:sequence><xs:element name="flat" type="xs:int"/></xs:sequence>
              </xs:sequence></xs:complexType>
              <xs:element name="track" type="TrackType"/>
            </xs:schema>
            """
        val imported = lower(docs("t.xsd" to xml))
        val track = unit(imported, "t").declarations.filterIsInstance<UnitRecord>().single()
        assertEquals(listOf("name", "lat_group", "flat"), track.fields.map { it.name })
        assertEquals(
            UnitType.ListOf(UnitType.Ref("LatGroup"), false, emptyList()),
            track.fields[1].type,
        )
        assertEquals(
            listOf("lat", "lon"),
            (track.nested.single() as UnitRecord).fields.map { it.name },
        )
        assertTrue(
            "SCH2403 complex type 'TrackType': nested sequence imported as model 'LatGroup' in field 'lat_group'" in
                messages(imported)
        )
        assertFalse(messages(imported).any { "flattened" in it })
    }

    @Test
    fun `optional nested sequences starting alike get numbered group records`() {
        val xml =
            """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns="urn:schemata:t" targetNamespace="urn:schemata:t">
              <xs:complexType name="PType"><xs:sequence>
                <xs:sequence minOccurs="0"><xs:element name="x" type="xs:int"/><xs:element name="a" type="xs:int"/></xs:sequence>
                <xs:element name="b" type="xs:int"/>
                <xs:sequence minOccurs="0"><xs:element name="x" type="xs:int"/><xs:element name="c" type="xs:int"/></xs:sequence>
              </xs:sequence></xs:complexType>
            </xs:schema>
            """
        val imported = lower(docs("t.xsd" to xml))
        val p = unit(imported, "t").declarations.filterIsInstance<UnitRecord>().single()
        assertEquals(listOf("x_group", "b", "x_group_2"), p.fields.map { it.name })
        assertEquals(UnitType.Ref("XGroup"), p.fields[0].type)
        assertTrue(p.fields[0].nullable)
        assertEquals(UnitType.Ref("XGroup2"), p.fields[2].type)
        assertEquals(listOf("XGroup", "XGroup2"), p.nested.map { it.name })
        assertFalse(messages(imported).any { it.startsWith("SCH2401") })
        assertTrue(
            "SCH2403 complex type 'PType': nested sequence imported as model 'XGroup2' in " +
                "field 'x_group_2'" in messages(imported)
        )
    }

    @Test
    fun `branch records starting alike get numbered across unions`() {
        val xml =
            """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns="urn:schemata:t" targetNamespace="urn:schemata:t">
              <xs:complexType name="AType"><xs:choice>
                <xs:element name="n" type="xs:int"/>
                <xs:sequence><xs:element name="w" type="xs:int"/><xs:element name="h" type="xs:int"/></xs:sequence>
              </xs:choice></xs:complexType>
              <xs:complexType name="BType"><xs:choice>
                <xs:element name="s" type="xs:string"/>
                <xs:sequence><xs:element name="w" type="xs:int"/><xs:element name="d" type="xs:int"/></xs:sequence>
              </xs:choice></xs:complexType>
            </xs:schema>
            """
        val imported = lower(docs("t.xsd" to xml))
        val t = unit(imported, "t")
        val unions = t.declarations.filterIsInstance<UnitUnion>().associateBy { it.name }
        assertEquals(UnitType.Ref("WGroup"), unions.getValue("A").members[1].type)
        assertEquals(UnitType.Ref("WGroup2"), unions.getValue("B").members[1].type)
        assertEquals(
            listOf("WGroup", "WGroup2"),
            t.declarations.filterIsInstance<UnitRecord>().map { it.name },
        )
        assertFalse(messages(imported).any { it.startsWith("SCH2401") })
    }

    @Test
    fun `a choice branch that is a sequence becomes a group member and a nested choice flattens`() {
        val xml =
            """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns="urn:schemata:t" targetNamespace="urn:schemata:t">
              <xs:complexType name="ShapeType"><xs:choice>
                <xs:element name="circle" type="xs:double"/>
                <xs:sequence><xs:element name="w" type="xs:int"/><xs:element name="h" type="xs:int"/></xs:sequence>
                <xs:choice><xs:element name="dot" type="xs:boolean"/></xs:choice>
              </xs:choice></xs:complexType>
            </xs:schema>
            """
        val imported = lower(docs("t.xsd" to xml))
        val t = unit(imported, "t")
        val shape = t.declarations.filterIsInstance<UnitUnion>().single()
        assertEquals(
            listOf(
                UnitType.Scalar("float64", emptyList()),
                UnitType.Ref("WGroup"),
                UnitType.Scalar("bool", emptyList()),
            ),
            shape.members.map { it.type },
        )
        assertTrue(t.declarations.any { it is UnitRecord && it.name == "WGroup" })
        assertTrue(
            "SCH2403 union 'Shape': choice branch imported as model 'WGroup'" in messages(imported)
        )
    }

    @Test
    fun `two inline choices in nested sequences get distinct names`() {
        val xml =
            """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns="urn:schemata:t" targetNamespace="urn:schemata:t">
              <xs:complexType name="AType"><xs:sequence/></xs:complexType>
              <xs:complexType name="RType"><xs:sequence>
                <xs:choice><xs:element name="a" type="AType"/><xs:element name="b" type="AType"/></xs:choice>
                <xs:sequence><xs:choice><xs:element name="c" type="AType"/><xs:element name="d" type="AType"/></xs:choice></xs:sequence>
              </xs:sequence></xs:complexType>
            </xs:schema>
            """
        val imported = lower(docs("t.xsd" to xml))
        val r =
            unit(imported, "t").declarations.filterIsInstance<UnitRecord>().single {
                it.name == "R"
            }
        assertEquals(listOf("choice", "choice_2"), r.fields.map { it.name })
        assertFalse(messages(imported).any { it.startsWith("SCH2401") })
    }

    @Test
    fun `a repeated group becomes a record named after the group`() {
        val xml =
            """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns="urn:schemata:t" targetNamespace="urn:schemata:t">
              <xs:group name="pair"><xs:sequence><xs:element name="k" type="xs:string"/><xs:element name="v" type="xs:string"/></xs:sequence></xs:group>
              <xs:complexType name="BagType"><xs:sequence><xs:group ref="pair" maxOccurs="unbounded"/></xs:sequence></xs:complexType>
            </xs:schema>
            """
        val imported = lower(docs("t.xsd" to xml))
        val bag =
            unit(imported, "t").declarations.filterIsInstance<UnitRecord>().single {
                it.name == "Bag"
            }
        assertEquals("pair", bag.fields.single().name)
        // the group reference's own minOccurs is 1, which the list keeps as its lower bound
        assertEquals(
            UnitType.ListOf(UnitType.Ref("Pair"), false, listOf("min" to "1")),
            bag.fields.single().type,
        )
        assertTrue(
            "SCH2403 complex type 'BagType': repeated group 'pair' imported as model 'Pair' in field 'pair'" in
                messages(imported)
        )
    }

    @Test
    fun `list and union simple types`() {
        val xml =
            """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns="urn:schemata:t" targetNamespace="urn:schemata:t">
              <xs:simpleType name="Ints"><xs:list itemType="xs:int"/></xs:simpleType>
              <xs:simpleType name="Size"><xs:restriction base="xs:string"><xs:enumeration value="s"/><xs:enumeration value="m"/></xs:restriction></xs:simpleType>
              <xs:simpleType name="Extra"><xs:restriction base="xs:string"><xs:enumeration value="xl"/></xs:restriction></xs:simpleType>
              <xs:simpleType name="AnySize"><xs:union memberTypes="Size Extra"/></xs:simpleType>
              <xs:simpleType name="Num"><xs:union memberTypes="xs:int xs:short"/></xs:simpleType>
              <xs:simpleType name="Mixed"><xs:union memberTypes="xs:int xs:string"/></xs:simpleType>
              <xs:complexType name="RType"><xs:sequence>
                <xs:element name="ints" type="Ints"/><xs:element name="size" type="AnySize"/><xs:element name="num" type="Num"/><xs:element name="mixed" type="Mixed"/>
              </xs:sequence><xs:attribute name="more" type="Ints"/></xs:complexType>
            </xs:schema>
            """
        val imported = lower(docs("t.xsd" to xml))
        val t = unit(imported, "t")
        val r = t.declarations.filterIsInstance<UnitRecord>().single { it.name == "R" }
        assertEquals(
            UnitType.ListOf(UnitType.Scalar("int32", emptyList()), false, emptyList()),
            r.fields[0].type,
        )
        assertEquals(listOf(UnitAnnotation("xsd", "list", null)), r.fields[0].annotations)
        assertEquals(UnitType.Ref("AnySize"), r.fields[1].type)
        // a named union simple type is a declaration of its own, so its enum is a top-level one
        val anySize = t.declarations.filterIsInstance<UnitEnum>().single { it.name == "AnySize" }
        assertEquals(listOf("s", "m", "xl"), anySize.values.map { it.name })
        assertEquals(UnitType.Scalar("int32", emptyList()), r.fields[2].type)
        assertEquals(string, r.fields[3].type)
        assertEquals(
            listOf(UnitAnnotation("xsd", "list", null), UnitAnnotation("xsd", "attribute", null)),
            r.fields[4].annotations,
        )
        assertTrue(
            messages(imported).count { "union simple type" in it && it.startsWith("SCH2403") } == 3
        )
    }

    @Test
    fun `an inline union of enumerations nests in the using record and a restricted list keeps its bounds`() {
        val xml =
            """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns="urn:schemata:t" targetNamespace="urn:schemata:t">
              <xs:simpleType name="Ints"><xs:list itemType="xs:int"/></xs:simpleType>
              <xs:simpleType name="FewInts"><xs:restriction base="Ints"><xs:minLength value="1"/><xs:maxLength value="3"/><xs:pattern value="[0-9 ]*"/></xs:restriction></xs:simpleType>
              <xs:complexType name="RType"><xs:sequence>
                <xs:element name="tone"><xs:simpleType><xs:union>
                  <xs:simpleType><xs:restriction base="xs:string"><xs:enumeration value="hi"/><xs:enumeration value="lo"/></xs:restriction></xs:simpleType>
                  <xs:simpleType><xs:restriction base="xs:string"><xs:enumeration value="lo"/><xs:enumeration value="mid"/></xs:restriction></xs:simpleType>
                </xs:union></xs:simpleType></xs:element>
                <xs:element name="few" type="FewInts"/>
              </xs:sequence></xs:complexType>
            </xs:schema>
            """
        val imported = lower(docs("t.xsd" to xml))
        val r = unit(imported, "t").declarations.filterIsInstance<UnitRecord>().single()
        assertEquals(UnitType.Ref("Tone"), r.fields[0].type)
        assertEquals(
            listOf("hi", "lo", "mid"),
            (r.nested.single() as UnitEnum).values.map { it.name },
        )
        assertEquals(
            UnitType.ListOf(
                UnitType.Scalar("int32", emptyList()),
                false,
                listOf("min" to "1", "max" to "3"),
            ),
            r.fields[1].type,
        )
        assertEquals(
            listOf(
                "SCH2403 element 'tone': union simple type imported as enum 'Tone'",
                "SCH2404 element 'few': facet pattern dropped",
            ),
            messages(imported),
        )
    }

    @Test
    fun `simple content facets refine the value`() {
        val xml =
            """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns="urn:schemata:t" targetNamespace="urn:schemata:t">
              <xs:complexType name="BaseType"><xs:simpleContent><xs:extension base="xs:string"><xs:attribute name="u" type="xs:string"/></xs:extension></xs:simpleContent></xs:complexType>
              <xs:complexType name="CodeType"><xs:simpleContent><xs:restriction base="xs:string"><xs:maxLength value="4"/></xs:restriction></xs:simpleContent></xs:complexType>
            </xs:schema>
            """
        val code =
            unit(lower(docs("t.xsd" to xml)), "t")
                .declarations
                .filterIsInstance<UnitRecord>()
                .single { it.name == "Code" }
        assertEquals(UnitType.Scalar("string", listOf("max" to "4")), code.fields.single().type)
    }

    @Test
    fun `identity constraints on global elements and fixed on repeated elements are reported`() {
        val xml =
            """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:t="urn:schemata:t" targetNamespace="urn:schemata:t">
              <xs:complexType name="RType"><xs:sequence>
                <xs:element name="tag" type="xs:string" maxOccurs="unbounded" fixed="x"/>
                <xs:element ref="t:box"/>
              </xs:sequence></xs:complexType>
              <xs:element name="r" type="t:RType"><xs:key name="k1"><xs:selector xpath="t:tag"/><xs:field xpath="."/></xs:key></xs:element>
              <xs:element name="box"><xs:complexType><xs:sequence/></xs:complexType><xs:unique name="u1"><xs:selector xpath="t:x"/><xs:field xpath="."/></xs:unique></xs:element>
            </xs:schema>
            """
        assertEquals(
            listOf(
                "SCH2405 element 'tag': fixed value dropped",
                "SCH2405 element 'r': identity constraint 'k1' dropped",
                "SCH2405 element 'box': identity constraint 'u1' dropped",
            ),
            messages(lower(docs("t.xsd" to xml))),
        )
    }

    @Test
    fun `synthetic names give way to named fields`() {
        val xml =
            """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" targetNamespace="urn:schemata:t" elementFormDefault="qualified">
              <xs:complexType name="PType" mixed="true"><xs:sequence>
                <xs:any processContents="lax"/>
                <xs:element name="any" type="xs:string"/>
              </xs:sequence>
              <xs:attribute name="text" type="xs:string"/>
              <xs:attribute name="attributes" type="xs:string"/>
              <xs:anyAttribute processContents="lax"/>
              </xs:complexType>
            </xs:schema>
            """
        val imported = lower(docs("t.xsd" to xml))
        val p = unit(imported, "t").declarations.filterIsInstance<UnitRecord>().single()
        assertEquals(
            listOf("any_2", "any", "mixed_text", "text", "attributes", "any_attributes"),
            p.fields.map { it.name },
        )
        assertEquals(emptyList(), messages(imported))
    }

    @Test
    fun `an untyped substitution member takes its head's type`() {
        val xml =
            """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:t="urn:schemata:t" targetNamespace="urn:schemata:t">
              <xs:element name="title" type="xs:int"/>
              <xs:element name="subtitle" substitutionGroup="t:title"/>
              <xs:complexType name="BookType"><xs:sequence><xs:element ref="t:subtitle"/></xs:sequence></xs:complexType>
            </xs:schema>
            """
        val book =
            unit(lower(docs("t.xsd" to xml)), "t")
                .declarations
                .filterIsInstance<UnitRecord>()
                .single { it.name == "Book" }
        assertEquals(UnitType.Scalar("int32", emptyList()), book.fields.single().type)
        assertEquals(emptyList(), book.fields.single().annotations)
    }

    @Test
    fun `documentation keeps the text of its child markup`() {
        val xml =
            """<xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:h="urn:h" targetNamespace="urn:schemata:t"><xs:complexType name="AType"><xs:annotation><xs:documentation>See <h:a href="x">the spec</h:a> for <h:b>more</h:b>.</xs:documentation></xs:annotation><xs:sequence/></xs:complexType></xs:schema>"""
        assertEquals(
            "See the spec for more.",
            docs("t.xsd" to xml).single().complexTypes.single().doc,
        )
    }

    @Test
    fun `redefine and override locations are read as includes`() {
        val xml =
            """<xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns="urn:schemata:t" targetNamespace="urn:schemata:t"><xs:include schemaLocation="a.xsd"/><xs:redefine schemaLocation="b.xsd"/><xs:override schemaLocation="c.xsd"/></xs:schema>"""
        val doc = docs("t.xsd" to xml).single()
        assertEquals(listOf("a.xsd", "b.xsd", "c.xsd"), doc.includes)
        assertEquals(listOf("xs:redefine", "xs:override"), doc.dropped.map { it.first })
    }

    @Test
    fun `a document included twice reports its reader diagnostics once`() {
        val bad =
            """<xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" targetNamespace="urn:schemata:s"><xs:complexType/></xs:schema>"""
        val main =
            ImportInput(
                "main.xsd",
                """<xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" targetNamespace="urn:schemata:s"><xs:include schemaLocation="bad.xsd"/><xs:include schemaLocation="mid.xsd"/></xs:schema>""",
            )
        val mid =
            ImportInput(
                "mid.xsd",
                """<xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" targetNamespace="urn:schemata:s"><xs:include schemaLocation="bad.xsd"/></xs:schema>""",
            )
        val result = XsdImporter.import(listOf(main, mid, ImportInput("bad.xsd", bad)))
        assertEquals(
            listOf("SCH2401 bad.xsd: xs:complexType at line 1 has no name"),
            result.diagnostics.map { "${it.code.id} ${it.message}" },
        )
    }

    @Test
    fun `an include into a document with no namespace says so`() {
        val main =
            ImportInput(
                "main.xsd",
                """<xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"><xs:include schemaLocation="other.xsd"/></xs:schema>""",
            )
        val other =
            ImportInput(
                "other.xsd",
                """<xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" targetNamespace="urn:schemata:other"/>""",
            )
        val result =
            XsdImporter.import(listOf(main), null) { if (it == "other.xsd") other else null }
        assertTrue(
            "SCH2401 main.xsd: include 'other.xsd' declares namespace 'urn:schemata:other', " +
                "but the including document has no namespace" in
                result.diagnostics.map { "${it.code.id} ${it.message}" }
        )
    }

    private fun records(imported: Imported, namespace: String): Map<String, UnitRecord> =
        unit(imported, namespace).declarations.filterIsInstance<UnitRecord>().associateBy {
            it.name
        }

    private fun listOfRef(name: String): (UnitType) -> Boolean = { t ->
        t is UnitType.ListOf && t.element == UnitType.Ref(name)
    }

    @Test
    fun `a reference to a global element with an anonymous type names its model`() {
        val xml =
            """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:tns="urn:schemata:t" targetNamespace="urn:schemata:t">
              <xs:element name="div"><xs:complexType><xs:sequence>
                <xs:element ref="tns:div" minOccurs="0" maxOccurs="unbounded"/>
              </xs:sequence></xs:complexType></xs:element>
              <xs:complexType name="PageType"><xs:sequence><xs:element ref="tns:div"/></xs:sequence></xs:complexType>
              <xs:element name="page" type="tns:PageType"/>
            </xs:schema>
            """
        val imported = lower(docs("t.xsd" to xml))
        val records = records(imported, "t")
        assertEquals(setOf("Div", "Page"), records.keys)
        val pageDiv = records.getValue("Page").fields.single()
        assertEquals("div", pageDiv.name)
        assertEquals(UnitType.Ref("Div"), pageDiv.type)
        assertFalse(pageDiv.nullable)
        val divDiv = records.getValue("Div").fields.single()
        assertTrue(listOfRef("Div")(divDiv.type))
        assertTrue(records.getValue("Div").nested.isEmpty())
        assertEquals(emptyList(), messages(imported).filter { "recursive" in it })
        assertEquals(
            emptyList(),
            imported.diagnostics.filter { "cannot be resolved" in it.message },
        )
    }

    @Test
    fun `a reference to a global element with an anonymous type names its model across namespaces`() {
        val a =
            """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" targetNamespace="urn:schemata:a">
              <xs:element name="div"><xs:complexType><xs:sequence>
                <xs:element name="text" type="xs:string"/>
              </xs:sequence></xs:complexType></xs:element>
            </xs:schema>
            """
        val b =
            """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:a="urn:schemata:a" targetNamespace="urn:schemata:b">
              <xs:import namespace="urn:schemata:a" schemaLocation="a.xsd"/>
              <xs:complexType name="PageType"><xs:sequence>
                <xs:element ref="a:div" minOccurs="0" maxOccurs="unbounded"/>
              </xs:sequence></xs:complexType>
              <xs:element name="page" type="PageType"/>
            </xs:schema>
            """
        val imported = lower(docs("a.xsd" to a, "b.xsd" to b))
        val page = records(imported, "b").getValue("Page")
        assertTrue(listOfRef("a.Div")(page.fields.single().type))
        assertTrue("a" in unit(imported, "b").imports)
        assertEquals(setOf("Div"), records(imported, "a").keys)
    }

    @Test
    fun `a reference across namespaces to a numbered element record names the numbered record`() {
        val a =
            """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" targetNamespace="urn:schemata:a">
              <xs:element name="fooBar"><xs:complexType><xs:sequence>
                <xs:element name="x" type="xs:int"/>
              </xs:sequence></xs:complexType></xs:element>
              <xs:element name="FooBar"><xs:complexType><xs:sequence>
                <xs:element name="y" type="xs:string"/>
              </xs:sequence></xs:complexType></xs:element>
            </xs:schema>
            """
        val b =
            """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:a="urn:schemata:a" targetNamespace="urn:schemata:b">
              <xs:import namespace="urn:schemata:a" schemaLocation="a.xsd"/>
              <xs:complexType name="PageType"><xs:sequence>
                <xs:element ref="a:FooBar"/>
              </xs:sequence></xs:complexType>
              <xs:element name="page" type="PageType"/>
            </xs:schema>
            """
        // b lowers first, before a's elements have claimed their record names.
        val imported = lower(docs("b.xsd" to b, "a.xsd" to a))
        val page = SchemataEmitter.emit(unit(imported, "b"))
        assertTrue("foo_bar a.FooBar2" in page, page)
        assertTrue("a" in unit(imported, "b").imports)
        val own = SchemataEmitter.emit(unit(imported, "a")).replace(Regex("\\s+"), " ")
        assertTrue("FooBar { x int32 }" in own, own)
        assertTrue("FooBar2 { y string }" in own, own)
        assertTrue(imported.diagnostics.none { it.code.id == "SCH2401" })
    }

    @Test
    fun `an xhtml shaped cycle through a group and an extension is finite`() {
        val xml =
            """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:tns="urn:schemata:t" targetNamespace="urn:schemata:t">
              <xs:group name="Flow"><xs:choice>
                <xs:element ref="tns:div"/>
              </xs:choice></xs:group>
              <xs:complexType name="Flow" mixed="true"><xs:sequence>
                <xs:element name="note" type="xs:string"/>
                <xs:group ref="tns:Flow" minOccurs="0" maxOccurs="unbounded"/>
              </xs:sequence></xs:complexType>
              <xs:element name="div"><xs:complexType><xs:complexContent>
                <xs:extension base="tns:Flow"/>
              </xs:complexContent></xs:complexType></xs:element>
            </xs:schema>
            """
        val imported = lower(docs("t.xsd" to xml))
        val div = records(imported, "t").getValue("Div")
        val fieldNames = div.fields.map { it.name }
        assertTrue("note" in fieldNames, fieldNames.toString())
        val divField = div.fields.single { it.name == "div" }
        assertTrue(listOfRef("Div")(divField.type), divField.type.toString())
    }

    @Test
    fun `an inline type extending an enclosing type is referenced by name`() {
        val xml =
            """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:tns="urn:schemata:t" targetNamespace="urn:schemata:t">
              <xs:complexType name="AType"><xs:sequence>
                <xs:element name="x" minOccurs="0"><xs:complexType><xs:complexContent>
                  <xs:extension base="tns:AType"><xs:sequence><xs:element name="y" type="xs:string"/></xs:sequence></xs:extension>
                </xs:complexContent></xs:complexType></xs:element>
              </xs:sequence></xs:complexType>
              <xs:element name="a" type="tns:AType"/>
            </xs:schema>
            """
        val imported = lower(docs("t.xsd" to xml))
        val a = records(imported, "t").getValue("A")
        val x = a.fields.single { it.name == "x" }
        assertEquals(UnitType.Ref("A"), x.type)
        assertTrue(x.nullable)
        assertTrue(a.nested.isEmpty())
        assertEquals(
            listOf("SCH2403 complex type 'AType': recursive content model; 'A' referenced by name"),
            messages(imported).filter { "recursive" in it }.map { it.substringBefore(" (") },
        )
    }

    @Test
    fun `a cycle through a hoisted choice union is cut`() {
        val xml =
            """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:tns="urn:schemata:t" targetNamespace="urn:schemata:t">
              <xs:complexType name="AType"><xs:sequence>
                <xs:element name="u" minOccurs="0"><xs:complexType><xs:choice>
                  <xs:element name="b"><xs:complexType><xs:sequence>
                    <xs:element name="c" minOccurs="0"><xs:complexType><xs:complexContent>
                      <xs:extension base="tns:AType"/>
                    </xs:complexContent></xs:complexType></xs:element>
                  </xs:sequence></xs:complexType></xs:element>
                  <xs:element name="d" type="xs:string"/>
                </xs:choice></xs:complexType></xs:element>
              </xs:sequence></xs:complexType>
              <xs:element name="a" type="tns:AType"/>
            </xs:schema>
            """
        val imported = lower(docs("t.xsd" to xml))
        val b = records(imported, "t").getValue("B")
        assertEquals(UnitType.Ref("A"), b.fields.single { it.name == "c" }.type)
        assertTrue(
            messages(imported).any { "recursive content model; 'A' referenced by name" in it }
        )
    }

    @Test
    fun `a reference to an element whose record name is numbered names the numbered record`() {
        val xml =
            """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:tns="urn:schemata:t" targetNamespace="urn:schemata:t">
              <xs:complexType name="PageType"><xs:sequence><xs:element ref="tns:foo_bar"/></xs:sequence></xs:complexType>
              <xs:element name="page" type="tns:PageType"/>
              <xs:element name="fooBar"><xs:complexType><xs:sequence><xs:element name="p" type="xs:string"/></xs:sequence></xs:complexType></xs:element>
              <xs:element name="foo_bar"><xs:complexType><xs:sequence><xs:element name="q" type="xs:string"/></xs:sequence></xs:complexType></xs:element>
            </xs:schema>
            """
        val imported = lower(docs("t.xsd" to xml))
        val records = records(imported, "t")
        assertEquals(setOf("Page", "FooBar", "FooBar2"), records.keys)
        assertEquals(UnitType.Ref("FooBar2"), records.getValue("Page").fields.single().type)
        assertEquals(
            listOf(
                "SCH2403 element 'foo_bar': element 'fooBar' already lowers to model 'FooBar'; imported as 'FooBar2'"
            ),
            messages(imported).filter { "FooBar2" in it },
        )
        assertTrue(imported.diagnostics.none { it.code.id == "SCH2401" })
    }

    @Test
    fun `a reference to an element whose name a hoisted member took names the numbered record`() {
        val xml =
            """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:tns="urn:schemata:t" targetNamespace="urn:schemata:t">
              <xs:complexType name="P1Type"><xs:sequence><xs:element ref="tns:b"/></xs:sequence></xs:complexType>
              <xs:complexType name="P2Type"><xs:sequence>
                <xs:element name="u"><xs:complexType><xs:choice>
                  <xs:element name="b"><xs:complexType><xs:sequence><xs:element name="m" type="xs:string"/></xs:sequence></xs:complexType></xs:element>
                  <xs:element name="d" type="xs:string"/>
                </xs:choice></xs:complexType></xs:element>
              </xs:sequence></xs:complexType>
              <xs:element name="p1" type="tns:P1Type"/>
              <xs:element name="p2" type="tns:P2Type"/>
              <xs:element name="b"><xs:complexType><xs:sequence><xs:element name="n" type="xs:string"/></xs:sequence></xs:complexType></xs:element>
            </xs:schema>
            """
        val imported = lower(docs("t.xsd" to xml))
        val records = records(imported, "t")
        assertEquals(UnitType.Ref("B2"), records.getValue("P1").fields.single().type)
        assertTrue("B2" in records.keys)
        assertTrue(messages(imported).any { "already lowers to model 'B'; imported as 'B2'" in it })
        assertTrue(imported.diagnostics.none { it.code.id == "SCH2401" })
    }

    @Test
    fun `an inline type whose base extends an enclosing type is referenced by name`() {
        val xml =
            """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:tns="urn:schemata:t" targetNamespace="urn:schemata:t">
              <xs:complexType name="AType"><xs:sequence>
                <xs:element name="x" minOccurs="0"><xs:complexType><xs:complexContent>
                  <xs:extension base="tns:BType"/>
                </xs:complexContent></xs:complexType></xs:element>
              </xs:sequence></xs:complexType>
              <xs:complexType name="BType"><xs:complexContent>
                <xs:extension base="tns:AType"><xs:sequence><xs:element name="y" type="xs:string"/></xs:sequence></xs:extension>
              </xs:complexContent></xs:complexType>
              <xs:element name="a" type="tns:AType"/>
            </xs:schema>
            """
        val imported = lower(docs("t.xsd" to xml))
        val a = records(imported, "t").getValue("A")
        val x = a.fields.single { it.name == "x" }
        assertEquals(UnitType.Ref("B"), x.type)
        assertTrue(x.nullable)
        assertTrue(
            imported.diagnostics.none { it.code.id == "SCH2401" },
            messages(imported).toString(),
        )
        assertEquals(
            1,
            messages(imported).count { "recursive content model; 'B' referenced by name" in it },
        )
    }

    @Test
    fun `an inline type extending the base of its enclosing type is expanded`() {
        val xml =
            """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:tns="urn:schemata:t" targetNamespace="urn:schemata:t">
              <xs:complexType name="ZType"><xs:sequence><xs:element name="z" type="xs:string"/></xs:sequence></xs:complexType>
              <xs:complexType name="AType"><xs:complexContent><xs:extension base="tns:ZType"><xs:sequence>
                <xs:element name="x" minOccurs="0"><xs:complexType><xs:complexContent>
                  <xs:extension base="tns:ZType"><xs:sequence><xs:element name="extra" type="xs:string"/></xs:sequence></xs:extension>
                </xs:complexContent></xs:complexType></xs:element>
              </xs:sequence></xs:extension></xs:complexContent></xs:complexType>
              <xs:element name="a" type="tns:AType"/>
            </xs:schema>
            """
        val imported = lower(docs("t.xsd" to xml))
        val a = records(imported, "t").getValue("A")
        val x = a.fields.single { it.name == "x" }
        assertEquals(UnitType.Ref("X"), x.type)
        val xRecord = a.nested.filterIsInstance<UnitRecord>().single { it.name == "X" }
        assertEquals(listOf("z", "extra"), xRecord.fields.map { it.name })
        assertTrue(messages(imported).none { "recursive" in it }, messages(imported).toString())
        assertTrue(imported.diagnostics.none { it.code.id == "SCH2401" })
    }

    @Test
    fun `an inline type extending a sibling of its enclosing type is expanded`() {
        val xml =
            """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:tns="urn:schemata:t" targetNamespace="urn:schemata:t">
              <xs:complexType name="ZType"><xs:sequence><xs:element name="z" type="xs:string"/></xs:sequence></xs:complexType>
              <xs:complexType name="YType"><xs:complexContent><xs:extension base="tns:ZType"/></xs:complexContent></xs:complexType>
              <xs:complexType name="AType"><xs:complexContent><xs:extension base="tns:ZType"><xs:sequence>
                <xs:element name="x" minOccurs="0"><xs:complexType><xs:complexContent>
                  <xs:extension base="tns:YType"><xs:sequence><xs:element name="extra" type="xs:string"/></xs:sequence></xs:extension>
                </xs:complexContent></xs:complexType></xs:element>
              </xs:sequence></xs:extension></xs:complexContent></xs:complexType>
              <xs:element name="a" type="tns:AType"/>
            </xs:schema>
            """
        val imported = lower(docs("t.xsd" to xml))
        val a = records(imported, "t").getValue("A")
        val x = a.fields.single { it.name == "x" }
        assertEquals(UnitType.Ref("X"), x.type)
        val xRecord = a.nested.filterIsInstance<UnitRecord>().single { it.name == "X" }
        assertEquals(listOf("z", "extra"), xRecord.fields.map { it.name })
        assertTrue(messages(imported).none { "recursive" in it }, messages(imported).toString())
        assertTrue(imported.diagnostics.none { it.code.id == "SCH2401" })
    }

    @Test
    fun `a cyclic base chain reports the chain and not a recursive content model`() {
        val xml =
            """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:tns="urn:schemata:t" targetNamespace="urn:schemata:t">
              <xs:complexType name="AType"><xs:complexContent><xs:extension base="tns:BType"/></xs:complexContent></xs:complexType>
              <xs:complexType name="BType"><xs:complexContent><xs:extension base="tns:AType"/></xs:complexContent></xs:complexType>
              <xs:element name="a" type="tns:AType"/>
            </xs:schema>
            """
        val imported = lower(docs("t.xsd" to xml))
        assertTrue(
            messages(imported).any { "the base chain is cyclic" in it },
            messages(imported).toString(),
        )
        assertTrue(messages(imported).none { "recursive" in it })
    }
}
