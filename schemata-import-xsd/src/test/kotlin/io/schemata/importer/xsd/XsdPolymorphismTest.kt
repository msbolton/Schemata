package io.schemata.importer.xsd

import io.schemata.importer.Imported
import io.schemata.importer.SchemataUnit
import io.schemata.importer.UnitRecord
import io.schemata.importer.UnitType
import io.schemata.importer.UnitUnion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val GML =
    """
    <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:gml="urn:schemata:gml" targetNamespace="urn:schemata:gml" elementFormDefault="qualified">
      <xs:complexType name="AbstractFeatureType" abstract="true">
        <xs:sequence><xs:element name="id" type="xs:string"/></xs:sequence>
      </xs:complexType>
      <xs:element name="AbstractFeature" type="gml:AbstractFeatureType" abstract="true"/>
      <xs:complexType name="FeatureCollectionType">
        <xs:sequence><xs:element ref="gml:AbstractFeature" minOccurs="0" maxOccurs="unbounded"/></xs:sequence>
      </xs:complexType>
      <xs:element name="FeatureCollection" type="gml:FeatureCollectionType"/>
    </xs:schema>
    """

private const val APP =
    """
    <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:gml="urn:schemata:gml" xmlns:app="urn:schemata:app" targetNamespace="urn:schemata:app" elementFormDefault="qualified">
      <xs:import namespace="urn:schemata:gml" schemaLocation="gml.xsd"/>
      <xs:complexType name="BuildingType">
        <xs:complexContent><xs:extension base="gml:AbstractFeatureType">
          <xs:sequence><xs:element name="floors" type="xs:int"/></xs:sequence>
        </xs:extension></xs:complexContent>
      </xs:complexType>
      <xs:element name="Building" type="app:BuildingType" substitutionGroup="gml:AbstractFeature"/>
      <xs:complexType name="RoadType">
        <xs:complexContent><xs:extension base="gml:AbstractFeatureType">
          <xs:sequence><xs:element name="lanes" type="xs:int"/></xs:sequence>
        </xs:extension></xs:complexContent>
      </xs:complexType>
      <xs:element name="Road" type="app:RoadType" substitutionGroup="gml:AbstractFeature"/>
    </xs:schema>
    """

class XsdPolymorphismTest {
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
    fun `a substitution group head becomes a union of its members across namespaces`() {
        val imported = lower(docs("gml.xsd" to GML, "app.xsd" to APP))
        val gml = unit(imported, "gml")
        val union =
            gml.declarations.filterIsInstance<UnitUnion>().single { it.name == "AbstractFeature" }
        assertEquals(
            listOf("app.Building", "app.Road"),
            union.members.map { (it.type as UnitType.Ref).name },
        )
        assertTrue("app" in gml.imports)
        val collection =
            gml.declarations.filterIsInstance<UnitRecord>().single {
                it.name == "FeatureCollection"
            }
        val member = collection.fields.single()
        assertEquals(
            UnitType.ListOf(UnitType.Ref("AbstractFeature"), false, emptyList()),
            member.type,
        )
        assertFalse(gml.declarations.any { it.name == "AbstractFeature" && it is UnitRecord })
        val app = unit(imported, "app")
        val building =
            app.declarations.filterIsInstance<UnitRecord>().single { it.name == "Building" }
        assertEquals(listOf("id", "floors"), building.fields.map { it.name })
        assertTrue(
            "SCH2403 element 'AbstractFeature': substitution group 'AbstractFeature' imported as " +
                "union 'AbstractFeature' of 2 member types; the regenerated XSD uses a choice" in
                messages(imported)
        )
        assertFalse(messages(imported).any { "substitution group" in it && "dropped" in it })
        assertFalse(messages(imported).any { "abstract dropped" in it })
    }

    @Test
    fun `an abstract type used directly becomes the same union`() {
        val xml =
            """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:t="urn:schemata:t" targetNamespace="urn:schemata:t">
              <xs:complexType name="ShapeType" abstract="true"><xs:sequence/></xs:complexType>
              <xs:complexType name="CircleType"><xs:complexContent><xs:extension base="t:ShapeType"><xs:sequence><xs:element name="r" type="xs:int"/></xs:sequence></xs:extension></xs:complexContent></xs:complexType>
              <xs:complexType name="SquareType"><xs:complexContent><xs:extension base="t:ShapeType"><xs:sequence><xs:element name="side" type="xs:int"/></xs:sequence></xs:extension></xs:complexContent></xs:complexType>
              <xs:complexType name="DrawingType"><xs:sequence><xs:element name="main" type="t:ShapeType"/><xs:element name="others" type="t:ShapeType" maxOccurs="unbounded"/></xs:sequence></xs:complexType>
              <xs:element name="drawing" type="t:DrawingType"/>
            </xs:schema>
            """
        val imported = lower(docs("t.xsd" to xml))
        val t = unit(imported, "t")
        val shape = t.declarations.filterIsInstance<UnitUnion>().single { it.name == "Shape" }
        assertEquals(
            listOf("Circle", "Square"),
            shape.members.map { (it.type as UnitType.Ref).name },
        )
        val drawing = t.declarations.filterIsInstance<UnitRecord>().single { it.name == "Drawing" }
        assertEquals(UnitType.Ref("Shape"), drawing.fields[0].type)
        assertEquals(
            UnitType.ListOf(UnitType.Ref("Shape"), false, listOf("min" to "1")),
            drawing.fields[1].type,
        )
        assertTrue(
            "SCH2403 complex type 'ShapeType': abstract type 'ShapeType' imported as union " +
                "'Shape' of 2 concrete types; the regenerated XSD uses a choice" in
                messages(imported)
        )
    }

    private val derivedShapes =
        """
        <xs:complexType name="CircleType"><xs:complexContent><xs:extension base="t:ShapeType"><xs:sequence><xs:element name="r" type="xs:int"/></xs:sequence></xs:extension></xs:complexContent></xs:complexType>
        <xs:complexType name="SquareType"><xs:complexContent><xs:extension base="t:ShapeType"><xs:sequence><xs:element name="side" type="xs:int"/></xs:sequence></xs:extension></xs:complexContent></xs:complexType>
        """

    @Test
    fun `an abstract type nothing references writes no union`() {
        val xml =
            """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:t="urn:schemata:t" targetNamespace="urn:schemata:t">
              <xs:complexType name="BaseType" abstract="true"><xs:sequence><xs:element name="id" type="xs:string"/></xs:sequence></xs:complexType>
              <xs:complexType name="CircleType"><xs:complexContent><xs:extension base="t:BaseType"><xs:sequence><xs:element name="r" type="xs:int"/></xs:sequence></xs:extension></xs:complexContent></xs:complexType>
              <xs:complexType name="SquareType"><xs:complexContent><xs:extension base="t:BaseType"><xs:sequence><xs:element name="side" type="xs:int"/></xs:sequence></xs:extension></xs:complexContent></xs:complexType>
            </xs:schema>
            """
        val imported = lower(docs("t.xsd" to xml))
        val declarations = unit(imported, "t").declarations
        assertEquals(emptyList(), declarations.filterIsInstance<UnitUnion>())
        assertEquals(
            listOf("Base", "Circle", "Square"),
            declarations.filterIsInstance<UnitRecord>().map { it.name },
        )
        assertEquals(
            listOf("id", "r"),
            declarations
                .filterIsInstance<UnitRecord>()
                .single { it.name == "Circle" }
                .fields
                .map { it.name },
        )
        val messages = messages(imported)
        assertTrue(
            "SCH2405 complex type 'BaseType': abstract type is not referenced; no union written" in
                messages
        )
        assertEquals(1, messages.count { "BaseType" in it && "not referenced" in it })
        assertFalse(messages.any { "abstract dropped" in it })
        assertFalse(messages.any { "imported as union" in it })
    }

    @Test
    fun `an abstract type referenced only by a head element keeps its union`() {
        val xml =
            """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:t="urn:schemata:t" targetNamespace="urn:schemata:t">
              <xs:complexType name="ShapeType" abstract="true"><xs:sequence/></xs:complexType>
              $derivedShapes
              <xs:element name="shape" type="t:ShapeType" abstract="true"/>
              <xs:element name="circle" type="t:CircleType" substitutionGroup="t:shape"/>
              <xs:element name="square" type="t:SquareType" substitutionGroup="t:shape"/>
              <xs:complexType name="DrawingType"><xs:sequence><xs:element ref="t:shape"/></xs:sequence></xs:complexType>
            </xs:schema>
            """
        val imported = lower(docs("t.xsd" to xml))
        val t = unit(imported, "t")
        assertEquals(
            listOf("Circle", "Square"),
            t.declarations
                .filterIsInstance<UnitUnion>()
                .single { it.name == "Shape" }
                .members
                .map { (it.type as UnitType.Ref).name },
        )
        val drawing = t.declarations.filterIsInstance<UnitRecord>().single { it.name == "Drawing" }
        assertEquals(UnitType.Ref("Shape"), drawing.fields.single { it.name == "shape" }.type)
        assertFalse(messages(imported).any { "not referenced" in it })
    }

    @Test
    fun `an abstract type referenced by a local element keeps its union`() {
        val xml =
            """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:t="urn:schemata:t" targetNamespace="urn:schemata:t">
              <xs:complexType name="ShapeType" abstract="true"><xs:sequence/></xs:complexType>
              $derivedShapes
              <xs:complexType name="DrawingType"><xs:sequence><xs:element name="main" type="t:ShapeType"/></xs:sequence></xs:complexType>
            </xs:schema>
            """
        val imported = lower(docs("t.xsd" to xml))
        val t = unit(imported, "t")
        assertEquals(1, t.declarations.filterIsInstance<UnitUnion>().count { it.name == "Shape" })
        assertFalse(messages(imported).any { "not referenced" in it })
    }

    @Test
    fun `a head with one member lowers to that member and a head with none stays a record`() {
        val xml =
            """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:t="urn:schemata:t" targetNamespace="urn:schemata:t">
              <xs:complexType name="OneType" abstract="true"><xs:sequence/></xs:complexType>
              <xs:complexType name="OnlyType"><xs:complexContent><xs:extension base="t:OneType"><xs:sequence/></xs:extension></xs:complexContent></xs:complexType>
              <xs:complexType name="NoneType" abstract="true"><xs:sequence/></xs:complexType>
              <xs:complexType name="ItemType"><xs:sequence/></xs:complexType>
              <xs:element name="Thing" abstract="true"/>
              <xs:element name="Item" type="t:ItemType" substitutionGroup="t:Thing"/>
              <xs:complexType name="UseType"><xs:sequence><xs:element name="a" type="t:OneType"/><xs:element name="b" type="t:NoneType"/><xs:element ref="t:Thing"/></xs:sequence></xs:complexType>
              <xs:element name="use" type="t:UseType"/>
            </xs:schema>
            """
        val imported = lower(docs("t.xsd" to xml))
        val t = unit(imported, "t")
        val use = t.declarations.filterIsInstance<UnitRecord>().single { it.name == "Use" }
        assertEquals(UnitType.Ref("Only"), use.fields[0].type)
        assertEquals(UnitType.Ref("None"), use.fields[1].type)
        assertTrue(t.declarations.any { it is UnitRecord && it.name == "None" })
        assertFalse(t.declarations.any { it.name == "One" })
        assertTrue(
            "SCH2403 complex type 'OneType': abstract type 'OneType' imported as its one concrete " +
                "type 'Only'" in messages(imported)
        )
        assertTrue("SCH2405 complex type 'NoneType': abstract dropped" in messages(imported))
        assertEquals(UnitType.Ref("Item"), use.fields[2].type)
        assertFalse(t.declarations.any { it.name == "Thing" })
        assertTrue(
            "SCH2403 element 'Thing': substitution group 'Thing' imported as its one member type " +
                "'Item'" in messages(imported)
        )
        assertFalse(messages(imported).any { "'Thing'" in it && "abstract dropped" in it })
    }

    private fun shapes(substituting: List<String>): String =
        """
        <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:t="urn:schemata:t" targetNamespace="urn:schemata:t">
          <xs:complexType name="ShapeType" abstract="true"><xs:sequence/></xs:complexType>
          <xs:element name="Shape" type="t:ShapeType" abstract="true"/>
          <xs:complexType name="CircleType"><xs:complexContent><xs:extension base="t:ShapeType"><xs:sequence><xs:element name="r" type="xs:int"/></xs:sequence></xs:extension></xs:complexContent></xs:complexType>
          <xs:complexType name="SquareType"><xs:complexContent><xs:extension base="t:ShapeType"><xs:sequence><xs:element name="side" type="xs:int"/></xs:sequence></xs:extension></xs:complexContent></xs:complexType>
          <xs:complexType name="TriangleType"><xs:complexContent><xs:extension base="t:ShapeType"><xs:sequence><xs:element name="base" type="xs:int"/></xs:sequence></xs:extension></xs:complexContent></xs:complexType>
          ${substituting.joinToString("\n") { """<xs:element name="$it" type="t:${it}Type" substitutionGroup="t:Shape"/>""" }}
          <xs:complexType name="DrawingType"><xs:sequence><xs:element ref="t:Shape" maxOccurs="unbounded"/><xs:element name="main" type="t:ShapeType"/></xs:sequence></xs:complexType>
          <xs:element name="drawing" type="t:DrawingType"/>
        </xs:schema>
        """

    @Test
    fun `an element head sharing a type head's name reuses the union when the members agree`() {
        val t = unit(lower(docs("t.xsd" to shapes(listOf("Circle", "Square", "Triangle")))), "t")
        assertEquals(1, t.declarations.count { it is UnitUnion && it.name == "Shape" })
        assertFalse(t.declarations.any { it.name == "ShapeChoice" })
        val drawing = t.declarations.filterIsInstance<UnitRecord>().single { it.name == "Drawing" }
        assertEquals(
            UnitType.ListOf(UnitType.Ref("Shape"), false, listOf("min" to "1")),
            drawing.fields[0].type,
        )
        assertEquals(UnitType.Ref("Shape"), drawing.fields[1].type)
    }

    @Test
    fun `an element head sharing a type head's name with different members is suffixed`() {
        val t = unit(lower(docs("t.xsd" to shapes(listOf("Circle", "Square")))), "t")
        val shape = t.declarations.filterIsInstance<UnitUnion>().single { it.name == "Shape" }
        assertEquals(
            listOf("Circle", "Square", "Triangle"),
            shape.members.map { (it.type as UnitType.Ref).name },
        )
        val choice =
            t.declarations.filterIsInstance<UnitUnion>().single { it.name == "ShapeChoice" }
        assertEquals(
            listOf("Circle", "Square"),
            choice.members.map { (it.type as UnitType.Ref).name },
        )
        val drawing = t.declarations.filterIsInstance<UnitRecord>().single { it.name == "Drawing" }
        assertEquals(
            UnitType.ListOf(UnitType.Ref("ShapeChoice"), false, listOf("min" to "1")),
            drawing.fields[0].type,
        )
        assertEquals(UnitType.Ref("Shape"), drawing.fields[1].type)
    }

    @Test
    fun `extension of anyType is an empty base and a restriction inherits attributes`() {
        val xml =
            """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:t="urn:schemata:t" targetNamespace="urn:schemata:t">
              <xs:complexType name="OpenType"><xs:complexContent><xs:extension base="xs:anyType"><xs:sequence><xs:element name="x" type="xs:int"/></xs:sequence></xs:extension></xs:complexContent></xs:complexType>
              <xs:complexType name="BaseType"><xs:sequence><xs:element name="a" type="xs:int"/><xs:element name="b" type="xs:int" minOccurs="0"/></xs:sequence><xs:attribute name="k" type="xs:string"/><xs:attribute name="gone" type="xs:string"/></xs:complexType>
              <xs:complexType name="NarrowType"><xs:complexContent><xs:restriction base="t:BaseType"><xs:sequence><xs:element name="a" type="xs:int"/></xs:sequence><xs:attribute name="gone" use="prohibited"/></xs:restriction></xs:complexContent></xs:complexType>
              <xs:element name="open" type="t:OpenType"/><xs:element name="narrow" type="t:NarrowType"/>
            </xs:schema>
            """
        val imported = lower(docs("t.xsd" to xml))
        val t = unit(imported, "t")
        val open = t.declarations.filterIsInstance<UnitRecord>().single { it.name == "Open" }
        assertEquals(listOf("x"), open.fields.map { it.name })
        val narrow = t.declarations.filterIsInstance<UnitRecord>().single { it.name == "Narrow" }
        assertEquals(listOf("a", "k"), narrow.fields.map { it.name })
        assertFalse(messages(imported).any { "anyType" in it && "cannot be resolved" in it })
    }

    @Test
    fun `a substitution member of a simple or unresolved type is dropped from the union`() {
        val xml =
            """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:t="urn:schemata:t" targetNamespace="urn:schemata:t">
              <xs:complexType name="CountType"><xs:sequence><xs:element name="n" type="xs:int"/></xs:sequence></xs:complexType>
              <xs:complexType name="TextType"><xs:sequence><xs:element name="s" type="xs:string"/></xs:sequence></xs:complexType>
              <xs:simpleType name="LabelType"><xs:restriction base="xs:string"/></xs:simpleType>
              <xs:element name="AbstractValue" abstract="true"/>
              <xs:element name="count" type="t:CountType" substitutionGroup="t:AbstractValue"/>
              <xs:element name="text" type="t:TextType" substitutionGroup="t:AbstractValue"/>
              <xs:element name="code" type="xs:string" substitutionGroup="t:AbstractValue"/>
              <xs:element name="label" type="t:LabelType" substitutionGroup="t:AbstractValue"/>
              <xs:element name="AbstractOne" abstract="true"/>
              <xs:element name="only" type="t:CountType" substitutionGroup="t:AbstractOne"/>
              <xs:element name="ghost" type="t:Nowhere" substitutionGroup="t:AbstractOne"/>
              <xs:complexType name="UseType"><xs:sequence><xs:element ref="t:AbstractValue"/><xs:element ref="t:AbstractOne"/></xs:sequence></xs:complexType>
              <xs:element name="use" type="t:UseType"/>
            </xs:schema>
            """
        val imported = lower(docs("t.xsd" to xml))
        val t = unit(imported, "t")
        val union =
            t.declarations.filterIsInstance<UnitUnion>().single { it.name == "AbstractValue" }
        assertEquals(
            listOf(UnitType.Ref("Count"), UnitType.Ref("Text")),
            union.members.map { it.type },
        )
        val use = t.declarations.filterIsInstance<UnitRecord>().single { it.name == "Use" }
        assertEquals(UnitType.Ref("Count"), use.fields[1].type)
        val messages = messages(imported)
        assertTrue(
            "SCH2405 element 'code': substitution member of simple type dropped from union " +
                "'AbstractValue'" in messages
        )
        assertTrue(
            "SCH2405 element 'label': substitution member of simple type dropped from union " +
                "'AbstractValue'" in messages
        )
        assertTrue(
            "SCH2405 element 'ghost': substitution member of unresolved type dropped from " +
                "substitution group 'AbstractOne'" in messages
        )
    }

    @Test
    fun `block and final are reported once per document and an inline member is dropped`() {
        val xml =
            """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:t="urn:schemata:t" targetNamespace="urn:schemata:t" blockDefault="#all">
              <xs:complexType name="ShapeType" abstract="true" final="extension"><xs:sequence/></xs:complexType>
              <xs:element name="Shape" type="t:ShapeType" abstract="true" block="substitution"/>
              <xs:complexType name="CircleType"><xs:complexContent><xs:extension base="t:ShapeType"><xs:sequence/></xs:extension></xs:complexContent></xs:complexType>
              <xs:complexType name="SquareType"><xs:complexContent><xs:extension base="t:ShapeType"><xs:sequence/></xs:extension></xs:complexContent></xs:complexType>
              <xs:element name="Circle" type="t:CircleType" substitutionGroup="t:Shape"/>
              <xs:element name="Square" type="t:SquareType" substitutionGroup="t:Shape"/>
              <xs:element name="Blob" substitutionGroup="t:Shape"><xs:complexType><xs:sequence/></xs:complexType></xs:element>
            </xs:schema>
            """
        val imported = lower(docs("t.xsd" to xml))
        val messages = messages(imported)
        assertEquals(1, messages.count { it == "SCH2405 schema: block and final dropped" })
        assertTrue(
            "SCH2405 element 'Blob': substitution member with an inline type dropped from union " +
                "'Shape'" in messages
        )
    }

    private fun typelessHead(members: String): Imported =
        lower(
            docs(
                "t.xsd" to
                    """
                    <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:tns="urn:schemata:t" targetNamespace="urn:schemata:t">
                      <xs:complexType name="QType"><xs:sequence><xs:element name="q" type="xs:int"/></xs:sequence></xs:complexType>
                      <xs:complexType name="RType"><xs:sequence><xs:element name="r" type="xs:int"/></xs:sequence></xs:complexType>
                      <xs:element name="P" abstract="true"/>
                      $members
                      <xs:complexType name="UseType"><xs:sequence><xs:element ref="tns:P"/></xs:sequence></xs:complexType>
                    </xs:schema>
                    """
            )
        )

    @Test
    fun `a typeless abstract head with one member takes its type`() {
        val imported =
            typelessHead("""<xs:element name="Q" type="tns:QType" substitutionGroup="tns:P"/>""")
        val use =
            unit(imported, "t").declarations.filterIsInstance<UnitRecord>().single {
                it.name == "Use"
            }
        assertEquals(UnitType.Ref("Q"), use.fields.single().type)
        assertEquals("p", use.fields.single().name)
        assertTrue(
            "SCH2403 element 'P': substitution group 'P' imported as its one member type 'Q'" in
                messages(imported)
        )
        assertFalse(messages(imported).any { "dropped" in it })
    }

    @Test
    fun `a typeless abstract head with two members is their union`() {
        val imported =
            typelessHead(
                """<xs:element name="Q" type="tns:QType" substitutionGroup="tns:P"/>
                   <xs:element name="R" type="tns:RType" substitutionGroup="tns:P"/>"""
            )
        val t = unit(imported, "t")
        val union = t.declarations.filterIsInstance<UnitUnion>().single { it.name == "P" }
        assertEquals(listOf("Q", "R"), union.members.map { (it.type as UnitType.Ref).name })
        assertFalse(messages(imported).any { "dropped" in it })
    }
}
