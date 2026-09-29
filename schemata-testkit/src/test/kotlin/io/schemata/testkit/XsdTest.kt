package io.schemata.testkit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class XsdTest {
    private val customers =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:tns="urn:c" targetNamespace="urn:c" elementFormDefault="qualified">
          <xs:complexType name="CustomerType"><xs:sequence><xs:element name="name" type="xs:string"/></xs:sequence></xs:complexType>
        </xs:schema>
        """
            .trimIndent()

    private val orders =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:tns="urn:o" xmlns:ns1="urn:c" targetNamespace="urn:o" elementFormDefault="qualified">
          <xs:import namespace="urn:c" schemaLocation="../c/customers.xsd"/>
          <xs:complexType name="OrderType"><xs:sequence><xs:element name="customer" type="ns1:CustomerType"/></xs:sequence></xs:complexType>
          <xs:element name="order" type="tns:OrderType"/>
        </xs:schema>
        """
            .trimIndent()

    @Test
    fun `a valid schema set with a relative import compiles`() {
        assertNull(Xsd.validate(mapOf("c/customers.xsd" to customers, "o/orders.xsd" to orders)))
    }

    @Test
    fun `a broken schema reports the validator's message`() {
        val message = Xsd.validate(mapOf("x.xsd" to customers.replace("xs:string", "xs:nosuch")))
        assertNotNull(message)
        assertTrue(message.contains("nosuch"), message)
    }

    @Test
    fun `an instance validates against the root schema and a bad one does not`() {
        val files = mapOf("c/customers.xsd" to customers, "o/orders.xsd" to orders)
        val good =
            "<order xmlns=\"urn:o\"><customer><name xmlns=\"urn:c\">Ada</name></customer></order>"
        assertNull(Xsd.validateDocument(files, "o/orders.xsd", good))
        val bad = "<order xmlns=\"urn:o\"><customer/></order>"
        assertNotNull(Xsd.validateDocument(files, "o/orders.xsd", bad))
    }

    @Test
    fun `resolve computes the import path relative to the importing file`() {
        assertEquals("c/customers.xsd", Xsd.resolve("o/orders.xsd", "../c/customers.xsd"))
        assertEquals("a.xsd", Xsd.resolve("x/y/b.xsd", "../../a.xsd"))
        assertEquals("b.xsd", Xsd.resolve("a.xsd", "b.xsd"))
    }
}
