package io.schemata.importer.xsd

import io.schemata.importer.ImportCodes
import io.schemata.importer.ImportInput
import io.schemata.importer.UnitRecord
import io.schemata.importer.emitUnits
import io.schemata.lang.Severity
import java.io.File
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
        <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:tns="urn:schemata:shop.orders" xmlns:c="urn:schemata:shop.customers" targetNamespace="urn:schemata:shop.orders" elementFormDefault="qualified">
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
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" targetNamespace="http://x/gpx" elementFormDefault="qualified">
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
        assertEquals(emptyList(), result.files)
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

    @Test
    fun `round trips the shop orders and customers xsd into an Order record`() {
        val ordersXml = File("../examples/shop/expected/xsd/shop/orders.xsd").readText()
        val customersXml = File("../examples/shop/expected/xsd/shop/customers.xsd").readText()

        val result =
            XsdImporter.import(
                listOf(
                    ImportInput("shop/orders.xsd", ordersXml),
                    ImportInput("shop/customers.xsd", customersXml),
                )
            )
        assertEquals(emptyList(), result.diagnostics)

        val orders = result.files.single { it.path == "shop/orders.schemata" }.content
        assertTrue(orders.contains("union Payment = Card | BankTransfer | Cash"), orders)

        val order =
            orders
                .substringAfter("record Order {")
                .substringBefore("\n}")
                .replace(Regex("[ \t]+"), " ")
        val fields =
            listOf(
                "id: uuid",
                "customer: shop.customers.Customer",
                "status: Status = pending",
                "lines: list<OrderLine>(min = 1)",
                "total: decimal(19, 4)",
                "payment: Payment",
                "shipping: OrderAddress",
                "placed_at: instant",
                "note: string(max = 500)?",
                "created: instant?",
            )
        var pos = 0
        fields.forEach { field ->
            val idx = order.indexOf(field, pos)
            assertTrue(idx >= pos, "expected '$field' at or after $pos in:\n$order")
            pos = idx + field.length
        }
    }

    private val includerXsd =
        """
        <?xml version="1.0"?>
        <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" targetNamespace="urn:schemata:a">
          <xs:include schemaLocation="b.xsd"/>
          <xs:complexType name="AType">
            <xs:sequence><xs:element name="x" type="xs:int"/></xs:sequence>
          </xs:complexType>
        </xs:schema>
        """
            .trimIndent()

    private val includedXsd =
        """
        <?xml version="1.0"?>
        <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" targetNamespace="urn:schemata:a">
          <xs:complexType name="BType">
            <xs:sequence><xs:element name="y" type="xs:int"/></xs:sequence>
          </xs:complexType>
        </xs:schema>
        """
            .trimIndent()

    @Test
    fun `an input another input includes is merged into it and not imported alone`() {
        listOf(
                listOf(ImportInput("a.xsd", includerXsd), ImportInput("b.xsd", includedXsd)),
                listOf(ImportInput("b.xsd", includedXsd), ImportInput("a.xsd", includerXsd)),
            )
            .forEach { inputs ->
                val result = XsdImporter.import(inputs)
                assertEquals(emptyList(), result.diagnostics)
                val file = result.files.single()
                assertEquals("a.schemata", file.path)
                assertTrue(file.content.contains("record A"), file.content)
                assertTrue(file.content.contains("record B"), file.content)
            }
    }

    private val choiceOfMissing =
        """
        <?xml version="1.0"?>
        <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:t="urn:schemata:t" xmlns:cac="urn:schemata:cac" targetNamespace="urn:schemata:t">
          <xs:import namespace="urn:schemata:cac" schemaLocation="common/cac.xsd"/>
          <xs:complexType name="PartyType">
            <xs:choice>
              <xs:element ref="cac:Person"/>
              <xs:element ref="cac:Organization"/>
            </xs:choice>
          </xs:complexType>
          <xs:element name="Party" type="t:PartyType"/>
          <xs:element name="Holder">
            <xs:complexType>
              <xs:choice><xs:element ref="cac:Person"/></xs:choice>
            </xs:complexType>
          </xs:element>
        </xs:schema>
        """
            .trimIndent()

    @Test
    fun `a choice of unresolved elements is reported and nothing is emitted`() {
        val result = XsdImporter.import(listOf(ImportInput("t.xsd", choiceOfMissing)))
        assertEquals(
            listOf(
                "SCH2401 t.xsd: import 'urn:schemata:cac' cannot be resolved",
                "SCH2401 union 'Party': element 'Person' cannot be resolved",
                "SCH2401 union 'Party': element 'Organization' cannot be resolved",
                "SCH2401 union 'Holder': element 'Person' cannot be resolved",
            ),
            result.diagnostics.map { "${it.code.id} ${it.message}" },
        )
        assertEquals(emptyList(), result.files)
    }

    @Test
    fun `a choice with no member left lowers to an empty record of its name`() {
        val doc = XsdReader.read("t.xsd", choiceOfMissing).doc!!
        val unit = XsdImport.lower(listOf(doc), null).units.single()
        assertEquals(
            listOf(
                UnitRecord("Party", emptyList(), emptyList(), null, emptyList()),
                UnitRecord("Holder", emptyList(), emptyList(), null, emptyList()),
            ),
            unit.declarations,
        )
        assertEquals(
            "@xsd(element_form = \"unqualified\")\nnamespace t\n\nrecord Party {}\n\nrecord Holder {}\n",
            emitUnits(listOf(unit)).single().content,
        )
    }

    @Test
    fun `two unrelated inputs declaring one foreign namespace are an error`() {
        val xsd =
            """
            <?xml version="1.0"?>
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" targetNamespace="http://example.com/x">
              <xs:complexType name="ThingType"><xs:sequence/></xs:complexType>
            </xs:schema>
            """
                .trimIndent()
        val result =
            XsdImporter.import(listOf(ImportInput("a.xsd", xsd), ImportInput("b.xsd", xsd)))
        assertEquals(
            listOf(
                "SCH2402 a.xsd: namespace 'a' was derived from the file name",
                "SCH2401 b.xsd: namespace 'http://example.com/x' is also declared by a.xsd",
            ),
            result.diagnostics.map { "${it.code.id} ${it.message}" },
        )
        assertEquals(emptyList(), result.files)
    }

    private fun schemaIn(uri: String) =
        """
        <?xml version="1.0"?>
        <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" targetNamespace="$uri" elementFormDefault="qualified">
          <xs:complexType name="ThingType">
            <xs:sequence><xs:element name="x" type="xs:int"/></xs:sequence>
          </xs:complexType>
        </xs:schema>
        """
            .trimIndent()

    @Test
    fun `a urn schemata namespace that is not lower snake is derived like any other uri`() {
        val result =
            XsdImporter.import(listOf(ImportInput("foo.xsd", schemaIn("urn:schemata:Foo-Bar"))))
        assertEquals(
            listOf("SCH2402 foo.xsd: namespace 'foo' was derived from the file name"),
            result.diagnostics.map { "${it.code.id} ${it.message}" },
        )
        val file = result.files.single()
        assertEquals("foo.schemata", file.path)
        assertTrue(
            file.content.startsWith("@xsd(namespace = \"urn:schemata:Foo-Bar\")\nnamespace foo\n"),
            file.content,
        )
    }

    @Test
    fun `a lower snake urn schemata namespace is taken as it stands`() {
        val result =
            XsdImporter.import(listOf(ImportInput("x.xsd", schemaIn("urn:schemata:shop.orders"))))
        assertEquals(emptyList(), result.diagnostics)
        val file = result.files.single()
        assertEquals("shop/orders.schemata", file.path)
        assertTrue(file.content.startsWith("namespace shop.orders\n"), file.content)
    }

    @Test
    fun `urn schemata namespaces that are not identifiers still emit formatted text`() {
        listOf("urn:schemata:Foo-Bar", "urn:schemata:import", "urn:schemata:a..b", "urn:schemata:")
            .forEach { uri ->
                val result = XsdImporter.import(listOf(ImportInput("foo.xsd", schemaIn(uri))))
                val file = result.files.single()
                assertTrue(file.content.contains("namespace foo\n"), "$uri: ${file.content}")
                assertTrue(file.content.contains("record Thing"), "$uri: ${file.content}")
            }
    }

    @Test
    fun `a chameleon include takes the including namespace`() {
        val main =
            ImportInput(
                "main.xsd",
                """
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns="urn:schemata:shop" targetNamespace="urn:schemata:shop" elementFormDefault="qualified">
                  <xs:include schemaLocation="parts.xsd"/>
                  <xs:complexType name="OrderType"><xs:sequence><xs:element name="line" type="LineType"/></xs:sequence></xs:complexType>
                  <xs:element name="order" type="OrderType"/>
                </xs:schema>
                """
                    .trimIndent(),
            )
        val parts =
            ImportInput(
                "parts.xsd",
                """
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" elementFormDefault="qualified">
                  <xs:complexType name="LineType"><xs:sequence><xs:element name="sku" type="xs:string"/><xs:element name="qty" type="QtyType"/></xs:sequence></xs:complexType>
                  <xs:complexType name="QtyType"><xs:sequence><xs:element name="n" type="xs:int"/></xs:sequence></xs:complexType>
                </xs:schema>
                """
                    .trimIndent(),
            )
        val result = XsdImporter.import(listOf(main, parts))
        assertTrue("qty: Qty" in result.files.single().content, result.files.single().content)
        assertEquals(
            emptyList(),
            result.diagnostics.filter { it.severity == Severity.ERROR }.map { it.message },
        )
        val text = result.files.single().content
        assertTrue("record Line {" in text, text)
        assertTrue("line: Line" in text, text)
    }

    @Test
    fun `an include that cannot be read is an error`() {
        val main =
            ImportInput(
                "main.xsd",
                """<xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" targetNamespace="urn:schemata:shop"><xs:include schemaLocation="missing.xsd"/></xs:schema>""",
            )
        val result = XsdImporter.import(listOf(main))
        assertEquals(
            listOf("SCH2401 main.xsd: include 'missing.xsd' cannot be resolved"),
            result.diagnostics.map { "${it.code.id} ${it.message}" },
        )
    }

    @Test
    fun `an include that is not well formed is reported once by the reader`() {
        val main =
            ImportInput(
                "main.xsd",
                """<xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" targetNamespace="urn:schemata:shop"><xs:include schemaLocation="broken.xsd"/></xs:schema>""",
            )
        val result =
            XsdImporter.import(
                listOf(main),
                locate = { if (it == "broken.xsd") ImportInput(it, "<xs:schema") else null },
            )
        assertEquals(1, result.diagnostics.size, result.diagnostics.toString())
        assertEquals("broken.xsd", result.diagnostics.single().span.file)
    }

    @Test
    fun `an include that climbs out of its directory resolves among the inputs`() {
        val main =
            ImportInput(
                "maindoc/a.xsd",
                """<xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" targetNamespace="urn:schemata:shop"><xs:include schemaLocation="../common/./b.xsd"/><xs:complexType name="AType"><xs:sequence><xs:element name="b" type="xs:int"/></xs:sequence></xs:complexType></xs:schema>""",
            )
        val common =
            ImportInput(
                "common/b.xsd",
                """<xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" targetNamespace="urn:schemata:shop"><xs:complexType name="BType"><xs:sequence><xs:element name="y" type="xs:int"/></xs:sequence></xs:complexType></xs:schema>""",
            )
        val result = XsdImporter.import(listOf(main, common))
        assertEquals(emptyList(), result.diagnostics.map { "${it.code.id} ${it.message}" })
        val text = result.files.single().content
        assertTrue("record A {" in text, text)
        assertTrue("record B {" in text, text)
    }

    @Test
    fun `a located include is asked for by its normalised path`() {
        val main =
            ImportInput(
                "maindoc/a.xsd",
                """<xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" targetNamespace="urn:schemata:shop"><xs:include schemaLocation="../common/b.xsd"/></xs:schema>""",
            )
        val asked = mutableListOf<String>()
        XsdImporter.import(
            listOf(main),
            locate = {
                asked += it
                null
            },
        )
        assertEquals(listOf("common/b.xsd"), asked)
    }

    @Test
    fun `an include of another namespace is an error`() {
        val main =
            ImportInput(
                "main.xsd",
                """<xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" targetNamespace="urn:schemata:shop"><xs:include schemaLocation="other.xsd"/></xs:schema>""",
            )
        val other =
            ImportInput(
                "other.xsd",
                """<xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" targetNamespace="urn:schemata:other"/>""",
            )
        val result =
            XsdImporter.import(listOf(main), locate = { if (it == "other.xsd") other else null })
        assertEquals(
            listOf(
                "SCH2401 main.xsd: include 'other.xsd' declares namespace 'urn:schemata:other', " +
                    "not 'urn:schemata:shop'"
            ),
            result.diagnostics.map { "${it.code.id} ${it.message}" },
        )
    }

    @Test
    fun `a diagnostic in an included document names that document`() {
        val main =
            ImportInput(
                "main.xsd",
                """<xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" targetNamespace="urn:schemata:shop"><xs:include schemaLocation="parts.xsd"/></xs:schema>""",
            )
        val parts =
            ImportInput(
                "parts.xsd",
                """
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">

                  <xs:complexType name="Thing"><xs:sequence><xs:element name="when" type="xs:gYear"/></xs:sequence></xs:complexType>
                </xs:schema>
                """
                    .trimIndent(),
            )
        val result = XsdImporter.import(listOf(main, parts))
        val widened = result.diagnostics.single { it.code == ImportCodes.WIDENED }
        assertEquals("parts.xsd", widened.span.file)
        assertEquals(3, widened.span.startLine)
    }
}
