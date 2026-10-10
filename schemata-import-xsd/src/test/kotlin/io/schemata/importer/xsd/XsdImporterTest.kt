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
            schema shop.orders

            import shop.customers

            model Order { customer shop.customers.Customer  note string? }

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
            file.content.startsWith("schema gpx @xsd(namespace: \"http://x/gpx\")\n"),
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
        assertTrue(file.content.contains("schema tracks"), file.content)
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
        assertTrue(a.content.contains("thing c.C"), a.content)
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
        assertTrue(file.content.contains("model Deep"), file.content)
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
        assertEquals(1, Regex("model A \\{").findAll(file.content).count())
        assertEquals(1, Regex("model B \\{").findAll(file.content).count())
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
                .substringAfter("model Order {")
                .substringBefore("\n}")
                .replace(Regex("[ \t]+"), " ")
        val fields =
            listOf(
                "id uuid",
                // the order holds the customer's key, as the XSD target writes a reference
                "customer_id uuid",
                "status Status = pending",
                "lines OrderLine[] { minItems 1 }",
                "total decimal(19, 4)",
                "payment Payment",
                "shipping OrderAddress",
                "placed_at instant",
                "note string? { max 500 }",
                "created instant?",
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
                assertTrue(file.content.contains("model A"), file.content)
                assertTrue(file.content.contains("model B"), file.content)
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
                "SCH2405 t.xsd: import 'urn:schemata:cac' not found; dropped",
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
            "schema t @xsd(element_form: \"unqualified\")\n" +
                "\n" +
                "model Party {}\n" +
                "\n" +
                "model Holder {}\n",
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
                "SCH2402 a.xsd: schema name 'a' was derived from the file name",
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
            listOf("SCH2402 foo.xsd: schema name 'foo' was derived from the file name"),
            result.diagnostics.map { "${it.code.id} ${it.message}" },
        )
        val file = result.files.single()
        assertEquals("foo.schemata", file.path)
        assertTrue(
            file.content.startsWith("schema foo @xsd(namespace: \"urn:schemata:Foo-Bar\")\n"),
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
        assertTrue(file.content.startsWith("schema shop.orders\n"), file.content)
    }

    @Test
    fun `urn schemata namespaces that are not identifiers still emit formatted text`() {
        listOf("urn:schemata:Foo-Bar", "urn:schemata:import", "urn:schemata:a..b", "urn:schemata:")
            .forEach { uri ->
                val result = XsdImporter.import(listOf(ImportInput("foo.xsd", schemaIn(uri))))
                val file = result.files.single()
                assertTrue(file.content.startsWith("schema foo "), "$uri: ${file.content}")
                assertTrue(file.content.contains("model Thing"), "$uri: ${file.content}")
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
        assertTrue("qty Qty" in result.files.single().content, result.files.single().content)
        assertEquals(
            emptyList(),
            result.diagnostics.filter { it.severity == Severity.ERROR }.map { it.message },
        )
        val text = result.files.single().content
        assertTrue("model Line {" in text, text)
        assertTrue("line Line" in text, text)
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
        assertTrue("model A {" in text, text)
        assertTrue("model B {" in text, text)
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
    fun `a located include from a windows path is asked for with forward slashes`() {
        val main =
            ImportInput(
                "C:\\xsd\\a\\main.xsd",
                """<xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" targetNamespace="urn:schemata:shop"><xs:include schemaLocation="../b/types.xsd"/></xs:schema>""",
            )
        val asked = mutableListOf<String>()
        XsdImporter.import(
            listOf(main),
            locate = {
                asked += it
                null
            },
        )
        assertEquals(listOf("C:/xsd/b/types.xsd"), asked)
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

    private fun xsdSchema(ns: String, body: String, header: String = ""): String =
        """<xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:n="$ns" targetNamespace="$ns">$header$body</xs:schema>"""

    private fun thing(name: String, type: String) =
        """<xs:complexType name="$name"><xs:sequence><xs:element name="x" type="$type"/></xs:sequence></xs:complexType>"""

    private fun served(vararg files: Pair<String, String>): (String) -> ImportInput? = { path ->
        files.toMap()[path]?.let { ImportInput(path, it) }
    }

    @Test
    fun `an import of a located document is followed`() {
        val a =
            xsdSchema(
                    "urn:schemata:a",
                    thing("AType", "b:BType"),
                    """<xs:import namespace="urn:schemata:b" schemaLocation="b.xsd"/>""",
                )
                .replace("xmlns:n=", "xmlns:b=\"urn:schemata:b\" xmlns:n=")
        val b =
            xsdSchema(
                    "urn:schemata:b",
                    thing("BType", "c:CType"),
                    """<xs:import namespace="urn:schemata:c" schemaLocation="c.xsd"/>""",
                )
                .replace("xmlns:n=", "xmlns:c=\"urn:schemata:c\" xmlns:n=")
        val c = xsdSchema("urn:schemata:c", thing("CType", "xs:string"))
        val result =
            XsdImporter.import(
                listOf(ImportInput("a.xsd", a)),
                locate = served("b.xsd" to b, "c.xsd" to c),
            )
        assertEquals(
            emptyList(),
            result.diagnostics.filter { it.code != ImportCodes.RENAMED }.map { it.message },
        )
        assertTrue(result.files.single { it.path == "a.schemata" }.content.contains("import b"))
        val bFile = result.files.single { it.path == "b.schemata" }.content
        assertTrue(bFile.contains("import c"), bFile)
        assertTrue(bFile.contains("x c.C"), bFile)
    }

    @Test
    fun `an include of a located document is merged into it`() {
        val a =
            xsdSchema(
                    "urn:schemata:a",
                    thing("AType", "b:B2Type"),
                    """<xs:import namespace="urn:schemata:b" schemaLocation="b.xsd"/>""",
                )
                .replace("xmlns:n=", "xmlns:b=\"urn:schemata:b\" xmlns:n=")
        val b =
            xsdSchema(
                "urn:schemata:b",
                thing("BType", "xs:string"),
                """<xs:include schemaLocation="b2.xsd"/>""",
            )
        val b2 = xsdSchema("urn:schemata:b", thing("B2Type", "xs:string"))
        val result =
            XsdImporter.import(
                listOf(ImportInput("a.xsd", a)),
                locate = served("b.xsd" to b, "b2.xsd" to b2),
            )
        assertEquals(
            emptyList(),
            result.diagnostics.filter { it.code != ImportCodes.RENAMED }.map { it.message },
        )
        assertTrue(result.files.single { it.path == "b.schemata" }.content.contains("B2"))
    }

    @Test
    fun `a located document is read once however many documents import it`() {
        fun importer(ns: String) =
            xsdSchema(
                ns,
                thing("TType", "xs:string"),
                """<xs:import namespace="urn:schemata:c" schemaLocation="c.xsd"/>""",
            )
        val c = xsdSchema("urn:schemata:c", thing("CType", "xs:string"))
        val result =
            XsdImporter.import(
                listOf(
                    ImportInput("a.xsd", importer("urn:schemata:a")),
                    ImportInput("b.xsd", importer("urn:schemata:b")),
                ),
                locate = served("c.xsd" to c),
            )
        assertEquals(1, result.files.count { it.path == "c.schemata" })
        assertEquals(
            emptyList(),
            result.diagnostics.filter { it.code != ImportCodes.RENAMED }.map { it.message },
        )
    }

    @Test
    fun `an import without a location resolves by namespace among the documents read`() {
        val a =
            xsdSchema(
                    "urn:schemata:a",
                    thing("AType", "c:CType"),
                    """<xs:import namespace="urn:schemata:c"/>""",
                )
                .replace("xmlns:n=", "xmlns:c=\"urn:schemata:c\" xmlns:n=")
        val c = xsdSchema("urn:schemata:c", thing("CType", "xs:string"))
        val result = XsdImporter.import(listOf(ImportInput("a.xsd", a), ImportInput("c.xsd", c)))
        assertEquals(
            emptyList(),
            result.diagnostics.filter { it.code != ImportCodes.RENAMED }.map { it.message },
        )
        assertTrue(result.files.single { it.path == "a.schemata" }.content.contains("import c"))
    }

    @Test
    fun `an import found nowhere is a warning and the document still imports`() {
        val s =
            xsdSchema(
                "urn:schemata:s",
                thing("SType", "xs:string"),
                """<xs:import namespace="urn:x:missing" schemaLocation="missing.xsd"/>""",
            )
        val result = XsdImporter.import(listOf(ImportInput("s.xsd", s)))
        val dropped = result.diagnostics.single { it.code == ImportCodes.DROPPED }
        assertEquals("s.xsd: import 'urn:x:missing' not found; dropped", dropped.message)
        assertEquals("add the schema that declares it to the inputs", dropped.help)
        assertTrue(result.diagnostics.none { it.severity == Severity.ERROR })
        assertTrue(result.files.any { it.path == "s.schemata" })
    }

    @Test
    fun `an include found nowhere stays an error`() {
        val s =
            xsdSchema(
                "urn:schemata:s",
                thing("SType", "xs:string"),
                """<xs:include schemaLocation="x.xsd"/>""",
            )
        val result = XsdImporter.import(listOf(ImportInput("s.xsd", s)))
        assertEquals(
            listOf("SCH2401 s.xsd: include 'x.xsd' cannot be resolved"),
            result.diagnostics.map { "${it.code.id} ${it.message}" },
        )
        assertTrue(result.diagnostics.all { it.severity == Severity.ERROR })
    }
}
