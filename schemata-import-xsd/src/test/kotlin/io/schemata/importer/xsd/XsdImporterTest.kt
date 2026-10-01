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

    @Test
    fun `an include brings its own imports along`() {
        val aXsd =
            """
            <?xml version="1.0"?>
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" targetNamespace="urn:schemata:a">
              <xs:include schemaLocation="b.xsd"/>
            </xs:schema>
            """
                .trimIndent()
        val bXsd =
            """
            <?xml version="1.0"?>
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:c="urn:schemata:c" targetNamespace="urn:schemata:a">
              <xs:import namespace="urn:schemata:c" schemaLocation="c.xsd"/>
              <xs:complexType name="BType">
                <xs:sequence>
                  <xs:element name="thing" type="c:CType"/>
                </xs:sequence>
              </xs:complexType>
            </xs:schema>
            """
                .trimIndent()
        val cXsd =
            """
            <?xml version="1.0"?>
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" targetNamespace="urn:schemata:c">
              <xs:complexType name="CType">
                <xs:sequence>
                  <xs:element name="value" type="xs:string"/>
                </xs:sequence>
              </xs:complexType>
            </xs:schema>
            """
                .trimIndent()
        val result =
            XsdImporter.import(
                listOf(ImportInput("a.xsd", aXsd)),
                locate = { path ->
                    when (path) {
                        "b.xsd" -> ImportInput("b.xsd", bXsd)
                        "c.xsd" -> ImportInput("c.xsd", cXsd)
                        else -> null
                    }
                },
            )
        assertEquals(emptyList(), result.diagnostics)
        val a = result.files.single { it.path == "a.schemata" }
        assertTrue(a.content.contains("import c"), a.content)
        assertTrue(a.content.contains("thing: c.C"), a.content)
    }

    @Test
    fun `chained includes merge through more than one level`() {
        val aXsd =
            """
            <?xml version="1.0"?>
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" targetNamespace="urn:schemata:chain">
              <xs:include schemaLocation="b.xsd"/>
            </xs:schema>
            """
                .trimIndent()
        val bXsd =
            """
            <?xml version="1.0"?>
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" targetNamespace="urn:schemata:chain">
              <xs:include schemaLocation="d.xsd"/>
            </xs:schema>
            """
                .trimIndent()
        val dXsd =
            """
            <?xml version="1.0"?>
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" targetNamespace="urn:schemata:chain">
              <xs:complexType name="DeepType">
                <xs:sequence>
                  <xs:element name="n" type="xs:int"/>
                </xs:sequence>
              </xs:complexType>
            </xs:schema>
            """
                .trimIndent()
        val result =
            XsdImporter.import(
                listOf(ImportInput("a.xsd", aXsd)),
                locate = { path ->
                    when (path) {
                        "b.xsd" -> ImportInput("b.xsd", bXsd)
                        "d.xsd" -> ImportInput("d.xsd", dXsd)
                        else -> null
                    }
                },
            )
        assertEquals(emptyList(), result.diagnostics)
        val file = result.files.single()
        assertTrue(file.content.contains("record Deep"), file.content)
    }

    @Test
    fun `a cyclic include terminates and merges both declaration sets once`() {
        val aXsd =
            """
            <?xml version="1.0"?>
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" targetNamespace="urn:schemata:cyclic">
              <xs:include schemaLocation="b.xsd"/>
              <xs:complexType name="AType">
                <xs:sequence>
                  <xs:element name="x" type="xs:int"/>
                </xs:sequence>
              </xs:complexType>
            </xs:schema>
            """
                .trimIndent()
        val bXsd =
            """
            <?xml version="1.0"?>
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" targetNamespace="urn:schemata:cyclic">
              <xs:include schemaLocation="a.xsd"/>
              <xs:complexType name="BType">
                <xs:sequence>
                  <xs:element name="y" type="xs:int"/>
                </xs:sequence>
              </xs:complexType>
            </xs:schema>
            """
                .trimIndent()
        val result =
            XsdImporter.import(
                listOf(ImportInput("a.xsd", aXsd)),
                locate = { path -> if (path == "b.xsd") ImportInput("b.xsd", bXsd) else null },
            )
        assertEquals(emptyList(), result.diagnostics)
        val file = result.files.single()
        assertEquals(1, Regex("record A \\{").findAll(file.content).count())
        assertEquals(1, Regex("record B \\{").findAll(file.content).count())
    }
}
