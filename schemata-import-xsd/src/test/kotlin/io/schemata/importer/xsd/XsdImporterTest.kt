package io.schemata.importer.xsd

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class XsdImporterTest {
    private val customersXsd =
        """
        <?xml version="1.0"?>
        <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:tns="urn:schemata:shop.customers" targetNamespace="urn:schemata:shop.customers">
          <xs:complexType name="CustomerType">
            <xs:sequence>
              <xs:element name="name" type="xs:string"/>
            </xs:sequence>
          </xs:complexType>
          <xs:element name="customer" type="tns:CustomerType"/>
        </xs:schema>
        """
            .trimIndent()

    private val ordersXsd =
        """
        <?xml version="1.0"?>
        <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:tns="urn:schemata:shop.orders" xmlns:c="urn:schemata:shop.customers" targetNamespace="urn:schemata:shop.orders">
          <xs:annotation><xs:documentation>Orders.</xs:documentation></xs:annotation>
          <xs:import namespace="urn:schemata:shop.customers" schemaLocation="customers.xsd"/>
          <xs:complexType name="OrderType">
            <xs:sequence>
              <xs:element name="customer" type="c:CustomerType"/>
              <xs:element name="note" type="xs:string" minOccurs="0"/>
            </xs:sequence>
          </xs:complexType>
          <xs:element name="order" type="tns:OrderType"/>
        </xs:schema>
        """
            .trimIndent()

    @Test
    fun `imports two namespaces with scalar and reference fields`() {
        val result =
            XsdImporter.import(
                listOf(
                    ImportInput("shop/orders.xsd", ordersXsd),
                    ImportInput("shop/customers.xsd", customersXsd),
                )
            )
        assertEquals(emptyList(), result.diagnostics)
        assertEquals(
            setOf("shop/orders.schemata", "shop/customers.schemata"),
            result.files.map { it.path }.toSet(),
        )
        val orders = result.files.single { it.path == "shop/orders.schemata" }
        assertEquals(
            """
            /// Orders.
            namespace shop.orders

            import shop.customers

            record Order { customer: shop.customers.Customer note: string? }

            """
                .trimIndent(),
            orders.content,
        )
    }

    @Test
    fun `a foreign namespace heads the output with the uri annotation`() {
        val gpxXsd =
            """
            <?xml version="1.0"?>
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" targetNamespace="http://x/gpx">
              <xs:complexType name="TrackType">
                <xs:sequence>
                  <xs:element name="name" type="xs:string"/>
                </xs:sequence>
              </xs:complexType>
            </xs:schema>
            """
                .trimIndent()
        val result = XsdImporter.import(listOf(ImportInput("gpx.xsd", gpxXsd)))
        val file = result.files.single()
        assertTrue(
            file.content.startsWith("@xsd(namespace = \"http://x/gpx\")\nnamespace gpx\n"),
            file.content,
        )
    }

    @Test
    fun `a namespace override replaces the derived name`() {
        val gpxXsd =
            """
            <?xml version="1.0"?>
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" targetNamespace="http://x/gpx">
              <xs:complexType name="TrackType">
                <xs:sequence>
                  <xs:element name="name" type="xs:string"/>
                </xs:sequence>
              </xs:complexType>
            </xs:schema>
            """
                .trimIndent()
        val result =
            XsdImporter.import(listOf(ImportInput("gpx.xsd", gpxXsd)), namespace = "tracks")
        val file = result.files.single()
        assertEquals("tracks.schemata", file.path)
        assertTrue(file.content.contains("namespace tracks"), file.content)
    }

    @Test
    fun `two inputs with the same stem in different directories are one SCH2401`() {
        val fooXsd =
            """
            <?xml version="1.0"?>
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
              <xs:complexType name="FooType">
                <xs:sequence>
                  <xs:element name="x" type="xs:int"/>
                </xs:sequence>
              </xs:complexType>
            </xs:schema>
            """
                .trimIndent()
        val result =
            XsdImporter.import(
                listOf(ImportInput("a/orders.xsd", fooXsd), ImportInput("b/orders.xsd", fooXsd))
            )
        assertEquals(1, result.diagnostics.count { it.code == ImportCodes.UNRESOLVED })
        assertEquals(1, result.files.size)
    }
}
