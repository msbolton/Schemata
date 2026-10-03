package io.schemata.importer.xsd

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
    fun `the xml prefix is bound without a declaration`() {
        val r =
            read(
                """
                <xs:schema $xs targetNamespace="urn:x">
                  <xs:complexType name="A"><xs:attribute ref="xml:lang"/></xs:complexType>
                </xs:schema>
                """
                    .trimIndent()
            )
        val use = r.doc!!.complexTypes.single().attributes.single() as XAttributeUse.Attribute
        assertEquals(QName("http://www.w3.org/XML/1998/namespace", "lang"), use.attribute.ref)
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

    @Test
    fun `the reader keeps forms block final mixed and wildcard attributes`() {
        val r =
            read(
                """
                <xs:schema $xs targetNamespace="urn:t"
                           elementFormDefault="unqualified" attributeFormDefault="qualified"
                           blockDefault="extension" finalDefault="#all">
                  <xs:complexType name="A" block="restriction" final="extension">
                    <xs:complexContent mixed="true">
                      <xs:extension base="xs:anyType">
                        <xs:sequence>
                          <xs:any minOccurs="0" maxOccurs="unbounded" namespace="##other" processContents="lax"/>
                        </xs:sequence>
                        <xs:anyAttribute namespace="##any" processContents="skip"/>
                      </xs:extension>
                    </xs:complexContent>
                  </xs:complexType>
                  <xs:element name="e" type="xs:string" form="qualified" block="substitution"/>
                  <xs:simpleType name="L"><xs:list><xs:simpleType><xs:restriction base="xs:int"/></xs:simpleType></xs:list></xs:simpleType>
                  <xs:simpleType name="U"><xs:union memberTypes="xs:int"><xs:simpleType><xs:restriction base="xs:string"><xs:enumeration value="a"/></xs:restriction></xs:simpleType></xs:union></xs:simpleType>
                  <xs:complexType name="S"><xs:simpleContent><xs:restriction base="xs:string"><xs:maxLength value="3"/></xs:restriction></xs:simpleContent></xs:complexType>
                </xs:schema>
                """
                    .trimIndent(),
                path = "test.xsd",
            )
        assertEquals(emptyList(), messages(r))
        val doc = r.doc!!
        assertEquals("unqualified", doc.elementFormDefault)
        assertEquals("qualified", doc.attributeFormDefault)
        assertEquals("extension", doc.blockDefault)
        assertEquals("#all", doc.finalDefault)
        val a = doc.complexTypes.single { it.name == "A" }
        assertEquals("restriction", a.block)
        assertEquals("extension", a.final)
        assertTrue(a.mixed)
        val ext = a.content as XContent.Extension
        val any = ext.particles.single() as XParticle.Any
        assertEquals(0, any.minOccurs)
        assertNull(any.maxOccurs)
        assertEquals("##other", any.namespace)
        assertEquals("lax", any.processContents)
        val anyAttr = a.attributes.single() as XAttributeUse.AnyAttribute
        assertEquals("##any", anyAttr.namespace)
        assertEquals("skip", anyAttr.processContents)
        val e = doc.elements.single()
        assertEquals("qualified", e.form)
        assertEquals("substitution", e.block)
        assertEquals("test.xsd", e.path)
        val l = doc.simpleTypes.single { it.name == "L" }.variety as XVariety.ListOf
        assertNull(l.itemType)
        assertNotNull(l.inlineItem)
        val u = doc.simpleTypes.single { it.name == "U" }.variety as XVariety.Union
        assertEquals(1, u.memberTypes.size)
        assertEquals(1, u.inlineMembers.size)
        val s = doc.complexTypes.single { it.name == "S" }.content as XContent.Restriction
        assertEquals(listOf("maxLength"), s.facets.map { it.name })
        assertEquals("test.xsd", a.path)
        assertEquals("test.xsd", doc.simpleTypes.single { it.name == "L" }.path)
    }

    @Test
    fun `rebasing binds unqualified references to the namespace and leaves qualified ones`() {
        val doc =
            read(
                    """
                    <xs:schema $xs>
                      <xs:complexType name="A">
                        <xs:complexContent>
                          <xs:extension base="B">
                            <xs:sequence>
                              <xs:element name="x" type="X"/>
                              <xs:element ref="r"/>
                              <xs:group ref="G"/>
                              <xs:choice><xs:element name="y" type="xs:int"/></xs:choice>
                            </xs:sequence>
                            <xs:attribute name="at" type="T"/>
                            <xs:attributeGroup ref="AG"/>
                          </xs:extension>
                        </xs:complexContent>
                      </xs:complexType>
                      <xs:simpleType name="L"><xs:list itemType="I"/></xs:simpleType>
                      <xs:simpleType name="U"><xs:union memberTypes="M xs:int"/></xs:simpleType>
                      <xs:element name="e" substitutionGroup="H"><xs:complexType><xs:sequence><xs:element name="z" type="Z"/></xs:sequence></xs:complexType></xs:element>
                      <xs:group name="G"><xs:sequence><xs:element name="g" type="GT"/></xs:sequence></xs:group>
                      <xs:attributeGroup name="AG"><xs:attribute name="ag" type="AGT"/></xs:attributeGroup>
                    </xs:schema>
                    """
                        .trimIndent()
                )
                .doc!!
                .rebased("urn:n")
        fun n(local: String) = QName("urn:n", local)
        assertEquals("urn:n", doc.targetNamespace)
        val a = doc.complexTypes.single()
        val ext = a.content as XContent.Extension
        assertEquals(n("B"), ext.base)
        assertEquals(n("X"), (ext.particles[0] as XParticle.Element).element.type)
        assertEquals(n("r"), (ext.particles[1] as XParticle.Element).element.ref)
        assertEquals(n("G"), (ext.particles[2] as XParticle.GroupRef).ref)
        val nested = (ext.particles[3] as XParticle.Nested).content as XContent.Choice
        assertEquals(
            QName(XsdReader.XS, "int"),
            (nested.particles.single() as XParticle.Element).element.type,
        )
        assertEquals(n("T"), (a.attributes[0] as XAttributeUse.Attribute).attribute.type)
        assertEquals(n("AG"), (a.attributes[1] as XAttributeUse.GroupRef).ref)
        assertEquals(n("I"), (doc.simpleTypes[0].variety as XVariety.ListOf).itemType)
        assertEquals(
            listOf(n("M"), QName(XsdReader.XS, "int")),
            (doc.simpleTypes[1].variety as XVariety.Union).memberTypes,
        )
        val e = doc.elements.single()
        assertEquals(n("H"), e.substitutionGroup)
        val inner = e.inlineComplex!!.content as XContent.Sequence
        assertEquals(n("Z"), (inner.particles.single() as XParticle.Element).element.type)
        val g = doc.groups.single().content as XContent.Sequence
        assertEquals(n("GT"), (g.particles.single() as XParticle.Element).element.type)
        val ag = doc.attributeGroups.single().attributes.single() as XAttributeUse.Attribute
        assertEquals(n("AGT"), ag.attribute.type)
    }

    private fun messages(r: ReadResult) = r.diagnostics.map { "${it.code.id} ${it.message}" }

    private fun schema(body: String) =
        read(
            "<xs:schema $xs xmlns:tns=\"urn:schemata:s\" targetNamespace=\"urn:schemata:s\">\n" +
                body.trimIndent() +
                "\n</xs:schema>"
        )

    @Test
    fun `a document type declaration with only an internal subset is read`() {
        val r =
            read(
                """
                <?xml version="1.0"?>
                <!DOCTYPE schema [
                  <!ATTLIST schema xmlns:tns CDATA #FIXED "urn:schemata:s">
                  <!ENTITY ns 'urn:schemata:s'>
                ]>
                <xs:schema $xs targetNamespace="&ns;"><xs:complexType name="A"/></xs:schema>
                """
                    .trimIndent()
            )
        assertEquals(emptyList(), r.diagnostics)
        assertEquals("urn:schemata:s", r.doc!!.targetNamespace)
        assertEquals("A", r.doc!!.complexTypes.single().name)
    }

    @Test
    fun `a document type declaration naming an external dtd is refused`() {
        listOf(
                "<!DOCTYPE schema SYSTEM \"http://example.com/schema.dtd\">",
                "<!DOCTYPE schema PUBLIC \"-//W3C//DTD XMLSchema 200102//EN\" \"XMLSchema.dtd\">",
            )
            .forEach { doctype ->
                val r = read("<?xml version=\"1.0\"?>\n$doctype\n<xs:schema $xs/>")
                assertNull(r.doc, doctype)
                val d = r.diagnostics.single()
                assertEquals("SCH2401", d.code.id)
                assertEquals("s.xsd: external DTD refused", d.message)
            }
    }

    @Test
    fun `an external entity in an internal subset is never read`() {
        val r =
            read(
                """
                <?xml version="1.0"?>
                <!DOCTYPE schema [<!ENTITY e SYSTEM "file:///etc/passwd">]>
                <xs:schema $xs><xs:annotation><xs:documentation>&e;</xs:documentation></xs:annotation></xs:schema>
                """
                    .trimIndent()
            )
        assertEquals(emptyList(), r.diagnostics)
        assertTrue(r.doc!!.doc.orEmpty().isEmpty(), r.doc!!.doc)
    }

    @Test
    fun `a top level complex type with no name is reported and skipped`() {
        val r =
            schema(
                """
                <xs:complexType><xs:sequence/></xs:complexType>
                """
            )
        assertEquals(listOf("SCH2401 s.xsd: xs:complexType at line 2 has no name"), messages(r))
        assertEquals(emptyList(), r.doc!!.complexTypes)
    }

    @Test
    fun `a group with no name is reported and skipped`() {
        val r = schema("<xs:group><xs:sequence/></xs:group>")
        assertEquals(listOf("SCH2401 s.xsd: xs:group at line 2 has no name"), messages(r))
        assertEquals(emptyList(), r.doc!!.groups)
    }

    @Test
    fun `an attribute group with no name is reported and skipped`() {
        val r = schema("<xs:attributeGroup><xs:attribute name=\"a\"/></xs:attributeGroup>")
        assertEquals(listOf("SCH2401 s.xsd: xs:attributeGroup at line 2 has no name"), messages(r))
        assertEquals(emptyList(), r.doc!!.attributeGroups)
    }

    @Test
    fun `an extension with no base is reported and skipped`() {
        val r =
            schema(
                """
                <xs:complexType name="DType">
                  <xs:complexContent><xs:extension/></xs:complexContent>
                </xs:complexType>
                """
            )
        assertEquals(listOf("SCH2401 s.xsd: xs:extension at line 3 has no base"), messages(r))
        assertEquals(XContent.Empty, r.doc!!.complexTypes.single().content)
    }

    @Test
    fun `a restriction with no base is reported and skipped`() {
        val r =
            schema(
                """
                <xs:complexType name="DType">
                  <xs:simpleContent><xs:restriction/></xs:simpleContent>
                </xs:complexType>
                """
            )
        assertEquals(listOf("SCH2401 s.xsd: xs:restriction at line 3 has no base"), messages(r))
        assertEquals(XContent.Empty, r.doc!!.complexTypes.single().content)
    }

    @Test
    fun `a group reference with no ref is reported and skipped`() {
        val r =
            schema(
                """
                <xs:complexType name="AType"><xs:group/></xs:complexType>
                <xs:complexType name="BType">
                  <xs:sequence><xs:group minOccurs="0"/><xs:element name="x" type="xs:int"/></xs:sequence>
                </xs:complexType>
                """
            )
        assertEquals(
            listOf(
                "SCH2401 s.xsd: xs:group at line 2 has no ref",
                "SCH2401 s.xsd: xs:group at line 4 has no ref",
            ),
            messages(r),
        )
        val (a, b) = r.doc!!.complexTypes
        assertEquals(XContent.Empty, a.content)
        assertEquals(1, (b.content as XContent.Sequence).particles.size)
    }

    @Test
    fun `an attribute group reference with no ref is reported and skipped`() {
        val r =
            schema(
                """
                <xs:complexType name="AType"><xs:attributeGroup/></xs:complexType>
                """
            )
        assertEquals(listOf("SCH2401 s.xsd: xs:attributeGroup at line 2 has no ref"), messages(r))
        assertEquals(emptyList(), r.doc!!.complexTypes.single().attributes)
    }

    @Test
    fun `a unique constraint with no name is reported and skipped`() {
        val r =
            schema(
                """
                <xs:element name="e" type="xs:string">
                  <xs:unique><xs:selector xpath="a"/><xs:field xpath="@k"/></xs:unique>
                </xs:element>
                """
            )
        assertEquals(listOf("SCH2401 s.xsd: xs:unique at line 3 has no name"), messages(r))
        assertEquals(emptyList(), r.doc!!.elements.single().uniques)
    }

    @Test
    fun `an element with neither name nor ref is reported and skipped`() {
        val r =
            schema(
                """
                <xs:complexType name="AType">
                  <xs:sequence><xs:element type="xs:int"/></xs:sequence>
                </xs:complexType>
                <xs:element type="xs:int"/>
                """
            )
        assertEquals(
            listOf(
                "SCH2401 s.xsd: xs:element at line 3 has no name",
                "SCH2401 s.xsd: xs:element at line 5 has no name",
            ),
            messages(r),
        )
        assertEquals(
            emptyList(),
            (r.doc!!.complexTypes.single().content as XContent.Sequence).particles,
        )
        assertEquals(emptyList(), r.doc!!.elements)
    }

    @Test
    fun `occurrence values that do not parse fall back to one`() {
        val r =
            schema(
                """
                <xs:complexType name="AType">
                  <xs:sequence>
                    <xs:element name="x" type="xs:int" minOccurs="none" maxOccurs="many"/>
                  </xs:sequence>
                </xs:complexType>
                """
            )
        assertEquals(
            listOf(
                "SCH2404 s.xsd: facet minOccurs value 'none' dropped",
                "SCH2404 s.xsd: facet maxOccurs value 'many' dropped",
            ),
            messages(r),
        )
        val x =
            ((r.doc!!.complexTypes.single().content as XContent.Sequence).particles.single()
                    as XParticle.Element)
                .element
        assertEquals(1, x.minOccurs)
        assertEquals(1, x.maxOccurs)
    }

    @Test
    fun `an element with both a type and an inline type keeps the type`() {
        val r =
            schema(
                """
                <xs:element name="e" type="tns:FooType">
                  <xs:complexType><xs:sequence/></xs:complexType>
                </xs:element>
                """
            )
        assertEquals(
            listOf("SCH2403 element 'e': inline type ignored in favour of type 'FooType'"),
            messages(r),
        )
        val e = r.doc!!.elements.single()
        assertEquals(QName("urn:schemata:s", "FooType"), e.type)
        assertNull(e.inlineComplex)
    }

    @Test
    fun `documentation lines lose their indentation`() {
        val r =
            schema(
                "<xs:complexType name=\"AType\"><xs:annotation><xs:documentation>\n" +
                    "\t\tFirst line.\n\t\t  Second line.\n\n    Third.\n" +
                    "</xs:documentation></xs:annotation></xs:complexType>"
            )
        assertEquals("First line.\nSecond line.\n\nThird.", r.doc!!.complexTypes.single().doc)
    }
}
