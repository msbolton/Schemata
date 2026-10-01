package io.schemata.importer.xsd

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class XsdReaderTest {
    private fun read(xml: String, path: String = "s.xsd") = XsdReader.read(path, xml)

    private val xs = "xmlns:xs=\"http://www.w3.org/2001/XMLSchema\""

    @Test
    fun `reads namespaces imports and documentation with line numbers`() {
        val r =
            read(
                """
                <?xml version="1.0"?>
                <xs:schema $xs xmlns:tns="urn:schemata:shop.orders" xmlns:c="urn:schemata:shop.customers" targetNamespace="urn:schemata:shop.orders">
                  <xs:annotation><xs:documentation>Orders.</xs:documentation></xs:annotation>
                  <xs:import namespace="urn:schemata:shop.customers" schemaLocation="customers.xsd"/>
                  <xs:include schemaLocation="more.xsd"/>
                  <xs:complexType name="OrderType">
                    <xs:annotation><xs:documentation>A customer's order.</xs:documentation></xs:annotation>
                    <xs:sequence>
                      <xs:element name="customer" type="c:CustomerType"/>
                      <xs:element name="note" type="xs:string" minOccurs="0"/>
                      <xs:element name="lines" type="tns:OrderLineType" maxOccurs="unbounded"/>
                    </xs:sequence>
                    <xs:attribute name="id" type="xs:long" use="required"/>
                  </xs:complexType>
                  <xs:element name="order" type="tns:OrderType"/>
                </xs:schema>
                """
                    .trimIndent()
            )
        val doc = r.doc!!
        assertEquals(emptyList(), r.diagnostics)
        assertEquals("urn:schemata:shop.orders", doc.targetNamespace)
        assertEquals("Orders.", doc.doc)
        assertEquals(
            listOf(XImport("urn:schemata:shop.customers", "customers.xsd", 4)),
            doc.imports,
        )
        assertEquals(listOf("more.xsd"), doc.includes)
        val order = doc.complexTypes.single()
        assertEquals("OrderType", order.name)
        assertEquals("A customer's order.", order.doc)
        assertEquals(6, order.line)
        val seq = order.content as XContent.Sequence
        val customer = (seq.particles[0] as XParticle.Element).element
        assertEquals(QName("urn:schemata:shop.customers", "CustomerType"), customer.type)
        assertEquals(9, customer.line)
        val note = (seq.particles[1] as XParticle.Element).element
        assertEquals(QName("http://www.w3.org/2001/XMLSchema", "string"), note.type)
        assertEquals(0, note.minOccurs)
        assertEquals(1, note.maxOccurs)
        val lines = (seq.particles[2] as XParticle.Element).element
        assertEquals(1, lines.minOccurs)
        assertNull(lines.maxOccurs)
        val id = (order.attributes.single() as XAttributeUse.Attribute).attribute
        assertEquals("required", id.use)
        assertEquals("order", doc.elements.single().name)
    }

    @Test
    fun `reads inline simple types facets enumerations and their docs`() {
        val r =
            read(
                """
                <xs:schema $xs targetNamespace="urn:x">
                  <xs:simpleType name="StatusType">
                    <xs:restriction base="xs:string">
                      <xs:enumeration value="pending"><xs:annotation><xs:documentation>Not paid.</xs:documentation></xs:annotation></xs:enumeration>
                      <xs:enumeration value="paid"/>
                    </xs:restriction>
                  </xs:simpleType>
                  <xs:element name="total">
                    <xs:simpleType>
                      <xs:restriction base="xs:decimal">
                        <xs:totalDigits value="19"/>
                        <xs:fractionDigits value="4"/>
                      </xs:restriction>
                    </xs:simpleType>
                  </xs:element>
                </xs:schema>
                """
                    .trimIndent()
            )
        val status = r.doc!!.simpleTypes.single()
        val restriction = status.variety as XVariety.Restriction
        assertEquals(QName("http://www.w3.org/2001/XMLSchema", "string"), restriction.base)
        assertEquals(listOf("enumeration", "enumeration"), restriction.facets.map { it.name })
        assertEquals("Not paid.", restriction.facets[0].doc)
        val total = r.doc!!.elements.single().inlineSimple!!
        val facets = (total.variety as XVariety.Restriction).facets
        assertEquals(
            listOf("totalDigits" to "19", "fractionDigits" to "4"),
            facets.map { it.name to it.value },
        )
    }

    @Test
    fun `reads choice extension groups any and unique`() {
        val r =
            read(
                """
                <xs:schema $xs targetNamespace="urn:x" xmlns:tns="urn:x">
                  <xs:group name="g"><xs:sequence><xs:element name="a" type="xs:int"/></xs:sequence></xs:group>
                  <xs:attributeGroup name="ag"><xs:attribute name="x" type="xs:string"/></xs:attributeGroup>
                  <xs:complexType name="PaymentType">
                    <xs:choice>
                      <xs:element name="card" type="tns:CardType"/>
                      <xs:any namespace="##other" processContents="lax"/>
                    </xs:choice>
                  </xs:complexType>
                  <xs:complexType name="DerivedType">
                    <xs:complexContent>
                      <xs:extension base="tns:BaseType">
                        <xs:sequence><xs:group ref="tns:g"/></xs:sequence>
                        <xs:attributeGroup ref="tns:ag"/>
                      </xs:extension>
                    </xs:complexContent>
                  </xs:complexType>
                  <xs:element name="counts">
                    <xs:complexType><xs:sequence><xs:element name="entry" type="xs:int" maxOccurs="unbounded"/></xs:sequence></xs:complexType>
                    <xs:unique name="k"><xs:selector xpath="tns:entry"/><xs:field xpath="@key"/></xs:unique>
                  </xs:element>
                </xs:schema>
                """
                    .trimIndent()
            )
        val doc = r.doc!!
        assertEquals("g", doc.groups.single().name)
        assertEquals("ag", doc.attributeGroups.single().name)
        val payment = doc.complexTypes[0].content as XContent.Choice
        assertTrue(payment.particles[1] is XParticle.Any)
        val derived = doc.complexTypes[1].content as XContent.Extension
        assertEquals(QName("urn:x", "BaseType"), derived.base)
        assertTrue(derived.particles.single() is XParticle.GroupRef)
        assertTrue(doc.complexTypes[1].attributes.single() is XAttributeUse.GroupRef)
        val counts = doc.elements.single()
        assertEquals(XUnique("k", "tns:entry", listOf("@key"), 20), counts.uniques.single())
    }

    @Test
    fun `treats an undeclared default namespace as null rather than empty`() {
        val r =
            read(
                """
                <xs:schema $xs targetNamespace="urn:x">
                  <xs:complexType name="A">
                    <xs:sequence>
                      <xs:element name="x" xmlns="" type="y"/>
                    </xs:sequence>
                  </xs:complexType>
                </xs:schema>
                """
                    .trimIndent()
            )
        val seq = r.doc!!.complexTypes.single().content as XContent.Sequence
        val x = (seq.particles.single() as XParticle.Element).element
        assertEquals(QName(null, "y"), x.type)
    }

    @Test
    fun `scopes a prefix declared on one complexType to that complexType alone`() {
        val r =
            read(
                """
                <xs:schema $xs targetNamespace="urn:x" xmlns:p="urn:a">
                  <xs:complexType name="A" xmlns:p="urn:b">
                    <xs:sequence><xs:element name="x" type="p:Foo"/></xs:sequence>
                  </xs:complexType>
                  <xs:complexType name="B">
                    <xs:sequence><xs:element name="y" type="p:Bar"/></xs:sequence>
                  </xs:complexType>
                </xs:schema>
                """
                    .trimIndent()
            )
        val doc = r.doc!!
        val a = doc.complexTypes[0].content as XContent.Sequence
        val x = (a.particles.single() as XParticle.Element).element
        assertEquals(QName("urn:b", "Foo"), x.type)
        val b = doc.complexTypes[1].content as XContent.Sequence
        val y = (b.particles.single() as XParticle.Element).element
        assertEquals(QName("urn:a", "Bar"), y.type)
    }

    @Test
    fun `keeps extension content that is a choice as one nested particle`() {
        val r =
            read(
                """
                <xs:schema $xs targetNamespace="urn:x" xmlns:tns="urn:x">
                  <xs:complexType name="DerivedType">
                    <xs:complexContent>
                      <xs:extension base="tns:BaseType">
                        <xs:choice>
                          <xs:element name="a" type="xs:int"/>
                          <xs:element name="b" type="xs:int"/>
                        </xs:choice>
                      </xs:extension>
                    </xs:complexContent>
                  </xs:complexType>
                </xs:schema>
                """
                    .trimIndent()
            )
        val ext = r.doc!!.complexTypes.single().content as XContent.Extension
        val nested = ext.particles.single() as XParticle.Nested
        val choice = nested.content as XContent.Choice
        assertEquals(2, choice.particles.size)
        assertTrue(choice.particles.all { it is XParticle.Element })
    }

    @Test
    fun `malformed xml and a non schema root are reported`() {
        val bad = read("<xs:schema $xs><xs:element name=\"a\"")
        assertNull(bad.doc)
        assertEquals("SCH2401", bad.diagnostics.single().code.id)
        val notSchema = read("<root/>")
        assertNull(notSchema.doc)
        assertEquals("SCH2401", notSchema.diagnostics.single().code.id)
        assertEquals(
            "s.xsd: the root element is not xs:schema",
            notSchema.diagnostics.single().message,
        )
    }
}
