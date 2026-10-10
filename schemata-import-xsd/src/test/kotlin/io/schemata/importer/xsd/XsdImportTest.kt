package io.schemata.importer.xsd

import io.schemata.importer.ImportInput
import io.schemata.importer.Imported
import io.schemata.importer.UnitAnnotation
import io.schemata.importer.UnitEnum
import io.schemata.importer.UnitEnumValue
import io.schemata.importer.UnitField
import io.schemata.importer.UnitRecord
import io.schemata.importer.UnitType
import io.schemata.importer.UnitUnion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class XsdImportTest {
    private val xs = "xmlns:xs=\"http://www.w3.org/2001/XMLSchema\""

    private fun xsd(key: String, value: String? = null) = UnitAnnotation("xsd", key, value)

    private fun doc(xml: String, path: String = "s.xsd"): XsdDoc {
        val result = XsdReader.read(path, xml.trimIndent())
        assertEquals(emptyList(), result.diagnostics)
        return result.doc!!
    }

    private fun lower(xml: String, path: String = "s.xsd"): Imported =
        XsdImport.lower(listOf(doc(xml, path)), null)

    private fun record(imported: Imported, name: String): UnitRecord =
        imported.units.single().declarations.filterIsInstance<UnitRecord>().single {
            it.name == name
        }

    private fun union(imported: Imported, name: String): UnitUnion =
        imported.units.single().declarations.filterIsInstance<UnitUnion>().single {
            it.name == name
        }

    private fun enum(imported: Imported, name: String): UnitEnum =
        imported.units.single().declarations.filterIsInstance<UnitEnum>().single { it.name == name }

    private fun messages(imported: Imported): List<String> =
        imported.diagnostics.map { "${it.code.id} ${it.message}" }

    @Test
    fun `xml attributes resolve without a file`() {
        val imported =
            lower(
                """
                <?xml version="1.0"?>
                <xs:schema $xs xmlns:xml="http://www.w3.org/XML/1998/namespace" xmlns:tns="urn:schemata:s" targetNamespace="urn:schemata:s">
                  <xs:complexType name="ThingType">
                    <xs:sequence>
                      <xs:element name="name" type="xs:string"/>
                    </xs:sequence>
                    <xs:attribute ref="xml:lang"/>
                    <xs:attribute ref="xml:base"/>
                  </xs:complexType>
                </xs:schema>
                """
            )
        assertEquals(emptyList(), imported.diagnostics)
        assertEquals(
            listOf(
                UnitField(
                    "lang",
                    UnitType.Scalar("string", emptyList()),
                    true,
                    null,
                    null,
                    listOf(xsd("attribute")),
                ),
                UnitField(
                    "base",
                    UnitType.Scalar("string", emptyList()),
                    true,
                    null,
                    null,
                    listOf(xsd("attribute")),
                ),
            ),
            record(imported, "Thing").fields.drop(1),
        )
    }

    @Test
    fun `attributes become annotated fields after the elements`() {
        val imported =
            lower(
                """
                <?xml version="1.0"?>
                <xs:schema $xs xmlns:tns="urn:schemata:s" targetNamespace="urn:schemata:s">
                  <xs:complexType name="ThingType">
                    <xs:sequence>
                      <xs:element name="name" type="xs:string"/>
                    </xs:sequence>
                    <xs:attribute name="id" type="xs:long" use="required"/>
                    <xs:attribute name="note" type="xs:string"/>
                    <xs:attribute name="status" type="xs:string" default="active"/>
                  </xs:complexType>
                </xs:schema>
                """
            )
        assertEquals(emptyList(), imported.diagnostics)
        assertEquals(
            listOf(
                UnitField(
                    "name",
                    UnitType.Scalar("string", emptyList()),
                    false,
                    null,
                    null,
                    emptyList(),
                ),
                UnitField(
                    "id",
                    UnitType.Scalar("int64", emptyList()),
                    false,
                    null,
                    null,
                    listOf(xsd("attribute")),
                ),
                UnitField(
                    "note",
                    UnitType.Scalar("string", emptyList()),
                    true,
                    null,
                    null,
                    listOf(xsd("attribute")),
                ),
                UnitField(
                    "status",
                    UnitType.Scalar("string", emptyList()),
                    false,
                    "\"active\"",
                    null,
                    listOf(xsd("attribute")),
                ),
            ),
            record(imported, "Thing").fields,
        )
    }

    @Test
    fun `a bare xs decimal element defaults to precision and scale`() {
        val imported =
            lower(
                """
                <?xml version="1.0"?>
                <xs:schema $xs xmlns:tns="urn:schemata:s" targetNamespace="urn:schemata:s">
                  <xs:complexType name="ThingType">
                    <xs:sequence>
                      <xs:element name="amount" type="xs:decimal"/>
                    </xs:sequence>
                  </xs:complexType>
                </xs:schema>
                """
            )
        assertEquals(
            UnitType.Scalar("decimal", listOf("p" to "38", "s" to "9")),
            record(imported, "Thing").fields.single().type,
        )
        assertEquals(
            listOf(
                "SCH2403 element 'amount': decimal without totalDigits and fractionDigits imported " +
                    "as decimal(38, 9)"
            ),
            messages(imported),
        )
    }

    @Test
    fun `an element with maxOccurs above one is a list with bounds`() {
        val imported =
            lower(
                """
                <?xml version="1.0"?>
                <xs:schema $xs xmlns:tns="urn:schemata:s" targetNamespace="urn:schemata:s">
                  <xs:complexType name="BoxType">
                    <xs:sequence>
                      <xs:element name="tags" type="xs:string" maxOccurs="unbounded"/>
                      <xs:element name="codes" type="xs:string" minOccurs="0" maxOccurs="2"/>
                      <xs:element name="holes" type="xs:string" minOccurs="0" maxOccurs="unbounded" nillable="true"/>
                      <xs:element name="labels" type="xs:string" maxOccurs="unbounded" default="x"/>
                    </xs:sequence>
                  </xs:complexType>
                </xs:schema>
                """
            )
        val string = UnitType.Scalar("string", emptyList())
        assertEquals(
            listOf(
                UnitField(
                    "tags",
                    UnitType.ListOf(string, false, listOf("min" to "1")),
                    false,
                    null,
                    null,
                    emptyList(),
                ),
                UnitField(
                    "codes",
                    UnitType.ListOf(string, false, listOf("max" to "2")),
                    false,
                    null,
                    null,
                    emptyList(),
                ),
                UnitField(
                    "holes",
                    UnitType.ListOf(string, true, emptyList()),
                    false,
                    null,
                    null,
                    emptyList(),
                ),
                UnitField(
                    "labels",
                    UnitType.ListOf(string, false, listOf("min" to "1")),
                    false,
                    null,
                    null,
                    emptyList(),
                ),
            ),
            record(imported, "Box").fields,
        )
        assertEquals(
            listOf("SCH2403 element 'labels': default on repeated element 'labels' dropped"),
            messages(imported),
        )
    }

    @Test
    fun `a single optional element is nullable unless it has a default`() {
        val imported =
            lower(
                """
                <?xml version="1.0"?>
                <xs:schema $xs xmlns:tns="urn:schemata:s" targetNamespace="urn:schemata:s">
                  <xs:simpleType name="KindType">
                    <xs:restriction base="xs:string">
                      <xs:enumeration value="personal"/>
                      <xs:enumeration value="work"/>
                    </xs:restriction>
                  </xs:simpleType>
                  <xs:complexType name="ThingType">
                    <xs:sequence>
                      <xs:element name="a" type="xs:string" minOccurs="0"/>
                      <xs:element name="b" type="xs:string" default="pending"/>
                      <xs:element name="c" type="xs:string" nillable="true"/>
                      <xs:element name="d" type="xs:string" fixed="x"/>
                      <xs:element name="kind" type="tns:KindType" default="personal"/>
                    </xs:sequence>
                  </xs:complexType>
                </xs:schema>
                """
            )
        val string = UnitType.Scalar("string", emptyList())
        assertEquals(
            listOf(
                UnitField("a", string, true, null, null, emptyList()),
                UnitField("b", string, false, "\"pending\"", null, emptyList()),
                UnitField("c", string, true, null, null, emptyList()),
                UnitField("d", string, false, "\"x\"", null, emptyList()),
                UnitField("kind", UnitType.Ref("Kind"), false, "personal", null, emptyList()),
            ),
            record(imported, "Thing").fields,
        )
        assertEquals(
            listOf("SCH2405 element 'd': fixed value imported as a default"),
            messages(imported),
        )
    }

    @Test
    fun `the map wrapper form imports as a map`() {
        val imported =
            lower(
                """
                <?xml version="1.0"?>
                <xs:schema $xs xmlns:tns="urn:schemata:s" targetNamespace="urn:schemata:s">
                  <xs:complexType name="ItemType">
                    <xs:sequence><xs:element name="name" type="xs:string"/></xs:sequence>
                  </xs:complexType>
                  <xs:complexType name="BoxType">
                    <xs:sequence>
                      <xs:element name="counts">
                        <xs:complexType>
                          <xs:sequence>
                            <xs:element name="entry" minOccurs="0" maxOccurs="unbounded">
                              <xs:complexType>
                                <xs:simpleContent>
                                  <xs:extension base="xs:int">
                                    <xs:attribute name="key" type="xs:string" use="required"/>
                                  </xs:extension>
                                </xs:simpleContent>
                              </xs:complexType>
                            </xs:element>
                          </xs:sequence>
                        </xs:complexType>
                        <xs:unique name="BoxType_counts_key">
                          <xs:selector xpath="tns:entry"/>
                          <xs:field xpath="@key"/>
                        </xs:unique>
                      </xs:element>
                      <xs:element name="by_id">
                        <xs:complexType>
                          <xs:sequence>
                            <xs:element name="entry" minOccurs="0" maxOccurs="unbounded">
                              <xs:complexType>
                                <xs:complexContent>
                                  <xs:extension base="tns:ItemType">
                                    <xs:attribute name="key" type="xs:long" use="required"/>
                                  </xs:extension>
                                </xs:complexContent>
                              </xs:complexType>
                            </xs:element>
                          </xs:sequence>
                        </xs:complexType>
                        <xs:unique name="BoxType_by_id_key">
                          <xs:selector xpath="tns:entry"/>
                          <xs:field xpath="@key"/>
                        </xs:unique>
                      </xs:element>
                      <xs:element name="sparse">
                        <xs:complexType>
                          <xs:sequence>
                            <xs:element name="entry" minOccurs="0" maxOccurs="unbounded" nillable="true">
                              <xs:complexType>
                                <xs:simpleContent>
                                  <xs:extension base="xs:int">
                                    <xs:attribute name="key" type="xs:string" use="required"/>
                                  </xs:extension>
                                </xs:simpleContent>
                              </xs:complexType>
                            </xs:element>
                          </xs:sequence>
                        </xs:complexType>
                        <xs:unique name="BoxType_sparse_key">
                          <xs:selector xpath="tns:entry"/>
                          <xs:field xpath="@key"/>
                        </xs:unique>
                      </xs:element>
                      <xs:element name="refined">
                        <xs:complexType>
                          <xs:sequence>
                            <xs:element name="entry" minOccurs="0" maxOccurs="unbounded">
                              <xs:complexType>
                                <xs:sequence>
                                  <xs:element name="value">
                                    <xs:simpleType>
                                      <xs:restriction base="xs:string">
                                        <xs:maxLength value="3"/>
                                      </xs:restriction>
                                    </xs:simpleType>
                                  </xs:element>
                                </xs:sequence>
                                <xs:attribute name="key" type="xs:string" use="required"/>
                              </xs:complexType>
                            </xs:element>
                          </xs:sequence>
                        </xs:complexType>
                        <xs:unique name="BoxType_refined_key">
                          <xs:selector xpath="tns:entry"/>
                          <xs:field xpath="@key"/>
                        </xs:unique>
                      </xs:element>
                    </xs:sequence>
                  </xs:complexType>
                  <xs:complexType name="NoUniqueType">
                    <xs:sequence>
                      <xs:element name="counts">
                        <xs:complexType>
                          <xs:sequence>
                            <xs:element name="entry" minOccurs="0" maxOccurs="unbounded">
                              <xs:complexType>
                                <xs:simpleContent>
                                  <xs:extension base="xs:int">
                                    <xs:attribute name="key" type="xs:string" use="required"/>
                                  </xs:extension>
                                </xs:simpleContent>
                              </xs:complexType>
                            </xs:element>
                          </xs:sequence>
                        </xs:complexType>
                      </xs:element>
                    </xs:sequence>
                  </xs:complexType>
                </xs:schema>
                """
            )
        val box = record(imported, "Box").fields
        assertEquals(
            UnitType.MapOf(
                UnitType.Scalar("string", emptyList()),
                UnitType.Scalar("int32", emptyList()),
                false,
                emptyList(),
            ),
            box.single { it.name == "counts" }.type,
        )
        assertEquals(
            UnitType.MapOf(
                UnitType.Scalar("int64", emptyList()),
                UnitType.Ref("Item"),
                false,
                emptyList(),
            ),
            box.single { it.name == "by_id" }.type,
        )
        assertEquals(
            UnitType.MapOf(
                UnitType.Scalar("string", emptyList()),
                UnitType.Scalar("int32", emptyList()),
                true,
                emptyList(),
            ),
            box.single { it.name == "sparse" }.type,
        )
        assertEquals(
            UnitType.MapOf(
                UnitType.Scalar("string", emptyList()),
                UnitType.Scalar("string", listOf("max" to "3")),
                false,
                emptyList(),
            ),
            box.single { it.name == "refined" }.type,
        )

        val noUnique = record(imported, "NoUnique").fields.single()
        assertEquals(UnitType.Ref("Counts"), noUnique.type)
        val counts =
            record(imported, "NoUnique").nested.filterIsInstance<UnitRecord>().single {
                it.name == "Counts"
            }
        assertEquals(
            listOf(
                UnitField(
                    "entry",
                    UnitType.ListOf(UnitType.Ref("Entry"), false, emptyList()),
                    false,
                    null,
                    null,
                    emptyList(),
                )
            ),
            counts.fields,
        )
        val entry = counts.nested.filterIsInstance<UnitRecord>().single { it.name == "Entry" }
        assertEquals(
            listOf(
                UnitField(
                    "value",
                    UnitType.Scalar("int32", emptyList()),
                    false,
                    null,
                    null,
                    emptyList(),
                ),
                UnitField(
                    "key",
                    UnitType.Scalar("string", emptyList()),
                    false,
                    null,
                    null,
                    listOf(xsd("attribute")),
                ),
            ),
            entry.fields,
        )
        assertEquals(
            listOf(
                "SCH2403 element 'counts': map wrapper without xs:unique imported as a nested model"
            ),
            messages(imported),
        )
    }

    @Test
    fun `nested collections recognise the item wrapper shape`() {
        // The exact shapes the XSD target writes for `list<list<int32>>`, `list<map<string,
        // int32>>`, and `map<string, list<Item>>` (schemata-cli --target xsd on a .schemata file
        // declaring those three fields, pasted in verbatim).
        val imported =
            lower(
                """
                <?xml version="1.0"?>
                <xs:schema $xs xmlns:tns="urn:schemata:s" targetNamespace="urn:schemata:s">
                  <xs:complexType name="ItemType">
                    <xs:sequence>
                      <xs:element name="name" type="xs:string"/>
                    </xs:sequence>
                  </xs:complexType>
                  <xs:complexType name="ThingType">
                    <xs:sequence>
                      <xs:element name="nested_list" minOccurs="0" maxOccurs="unbounded">
                        <xs:complexType>
                          <xs:sequence>
                            <xs:element name="item" type="xs:int" minOccurs="0" maxOccurs="unbounded"/>
                          </xs:sequence>
                        </xs:complexType>
                      </xs:element>
                      <xs:element name="list_of_maps" minOccurs="0" maxOccurs="unbounded">
                        <xs:complexType>
                          <xs:sequence>
                            <xs:element name="item">
                              <xs:complexType>
                                <xs:sequence>
                                  <xs:element name="entry" minOccurs="0" maxOccurs="unbounded">
                                    <xs:complexType>
                                      <xs:simpleContent>
                                        <xs:extension base="xs:int">
                                          <xs:attribute name="key" type="xs:string" use="required"/>
                                        </xs:extension>
                                      </xs:simpleContent>
                                    </xs:complexType>
                                  </xs:element>
                                </xs:sequence>
                              </xs:complexType>
                              <xs:unique name="ThingType_list_of_maps_item_key">
                                <xs:selector xpath="tns:entry"/>
                                <xs:field xpath="@key"/>
                              </xs:unique>
                            </xs:element>
                          </xs:sequence>
                        </xs:complexType>
                      </xs:element>
                      <xs:element name="map_of_lists">
                        <xs:complexType>
                          <xs:sequence>
                            <xs:element name="entry" minOccurs="0" maxOccurs="unbounded">
                              <xs:complexType>
                                <xs:sequence>
                                  <xs:element name="item" type="tns:ItemType" minOccurs="0" maxOccurs="unbounded"/>
                                </xs:sequence>
                                <xs:attribute name="key" type="xs:string" use="required"/>
                              </xs:complexType>
                            </xs:element>
                          </xs:sequence>
                        </xs:complexType>
                        <xs:unique name="ThingType_map_of_lists_key">
                          <xs:selector xpath="tns:entry"/>
                          <xs:field xpath="@key"/>
                        </xs:unique>
                      </xs:element>
                    </xs:sequence>
                  </xs:complexType>
                </xs:schema>
                """
            )
        assertEquals(emptyList(), imported.diagnostics)
        val thing = record(imported, "Thing").fields
        val int32 = UnitType.Scalar("int32", emptyList())
        val string = UnitType.Scalar("string", emptyList())
        assertEquals(
            UnitType.ListOf(UnitType.ListOf(int32, false, emptyList()), false, emptyList()),
            thing.single { it.name == "nested_list" }.type,
        )
        assertEquals(
            UnitType.ListOf(UnitType.MapOf(string, int32, false, emptyList()), false, emptyList()),
            thing.single { it.name == "list_of_maps" }.type,
        )
        assertEquals(
            UnitType.MapOf(
                string,
                UnitType.ListOf(UnitType.Ref("Item"), false, emptyList()),
                false,
                emptyList(),
            ),
            thing.single { it.name == "map_of_lists" }.type,
        )
    }

    @Test
    fun `global elements mark roots and names`() {
        val imported =
            lower(
                """
                <?xml version="1.0"?>
                <xs:schema $xs xmlns:tns="urn:schemata:s" targetNamespace="urn:schemata:s">
                  <xs:complexType name="AType">
                    <xs:sequence><xs:element name="x" type="xs:int"/></xs:sequence>
                  </xs:complexType>
                  <xs:element name="a" type="tns:AType"/>
                  <xs:complexType name="BType">
                    <xs:sequence><xs:element name="x" type="xs:int"/></xs:sequence>
                  </xs:complexType>
                  <xs:complexType name="CType">
                    <xs:sequence><xs:element name="x" type="xs:int"/></xs:sequence>
                  </xs:complexType>
                  <xs:element name="theC" type="tns:CType"/>
                  <xs:element name="my_thing">
                    <xs:complexType>
                      <xs:sequence><xs:element name="x" type="xs:int"/></xs:sequence>
                    </xs:complexType>
                  </xs:element>
                </xs:schema>
                """
            )
        assertEquals(emptyList(), record(imported, "A").annotations)
        assertEquals(listOf(xsd("root", "false")), record(imported, "B").annotations)
        // "theC" differs from the regenerated default ("c"); @xsd(name) is reserved for the type's
        // own name, so this is reported rather than fixed by a second, conflicting annotation.
        assertEquals(emptyList(), record(imported, "C").annotations)
        assertEquals(
            listOf(
                "SCH2403 element 'theC': element 'theC' has no Schemata equivalent; the regenerated root element will be named 'c'"
            ),
            messages(imported),
        )
        val myThing = record(imported, "MyThing")
        assertEquals(emptyList(), myThing.annotations)
        assertEquals("x", myThing.fields.single().name)
    }

    @Test
    fun `a type override is synthesised when it alone would reproduce the root element name`() {
        val imported =
            lower(
                """
                <?xml version="1.0"?>
                <xs:schema $xs xmlns:tns="urn:schemata:s" targetNamespace="urn:schemata:s">
                  <xs:complexType name="BetaType">
                    <xs:sequence><xs:element name="x" type="xs:int"/></xs:sequence>
                  </xs:complexType>
                  <xs:element name="Beta" type="tns:BetaType"/>
                </xs:schema>
                """
            )
        // "BetaType" needs no override to regenerate exactly (its default name "Beta" already gives
        // "BetaType" back), but the default root element name would be snake_case("Beta") = "beta",
        // not "Beta". The override text is always original.removeSuffix("Type") = "Beta" here,
        // which
        // happens to match the actual root element exactly, so it's added anyway, at no cost to the
        // type name, purely to make the element round trip too.
        assertEquals(listOf(xsd("name", "\"Beta\"")), record(imported, "Beta").annotations)
        assertEquals(emptyList(), imported.diagnostics)
    }

    @Test
    fun `nested enums interleave with records in document order`() {
        val imported =
            lower(
                """
                <?xml version="1.0"?>
                <xs:schema $xs xmlns:tns="urn:schemata:s" targetNamespace="urn:schemata:s">
                  <xs:complexType name="AType">
                    <xs:sequence><xs:element name="x" type="xs:int"/></xs:sequence>
                  </xs:complexType>
                  <xs:simpleType name="KindType">
                    <xs:restriction base="xs:string">
                      <xs:enumeration value="one"/>
                      <xs:enumeration value="two"/>
                    </xs:restriction>
                  </xs:simpleType>
                  <xs:complexType name="BType">
                    <xs:sequence><xs:element name="y" type="xs:int"/></xs:sequence>
                  </xs:complexType>
                </xs:schema>
                """
            )
        // Declarations are ordered by source line across complex types and enumerated simple types
        // together, not complex types first and enums last: the xsd target interleaves a nested
        // record's and a nested enum's flattened types as it writes them, so matching that order is
        // what lets a reimported xsd come back out byte for byte the same.
        assertEquals(listOf("A", "Kind", "B"), imported.units.single().declarations.map { it.name })
        assertEquals(emptyList(), imported.diagnostics)
    }

    @Test
    fun `type names strip Type and keep originals`() {
        val orderType =
            lower(
                """<?xml version="1.0"?>
            <xs:schema $xs xmlns:tns="urn:schemata:s" targetNamespace="urn:schemata:s">
              <xs:complexType name="OrderType"><xs:sequence/></xs:complexType>
            </xs:schema>
            """
            )
        assertNull(record(orderType, "Order").annotations.find { it.key == "name" })
        assertEquals(emptyList(), orderType.diagnostics)

        val gpx =
            lower(
                """<?xml version="1.0"?>
            <xs:schema $xs xmlns:tns="urn:schemata:s" targetNamespace="urn:schemata:s">
              <xs:complexType name="gpxType"><xs:sequence/></xs:complexType>
            </xs:schema>
            """
            )
        // gpxType has no global element at all here, so Gpx also gets @xsd(root: false); the
        // interesting annotation is the name override, and its value is the bare remainder "gpx"
        // (not "Gpx"), since that's what regenerates "gpxType" exactly (and "gpx" unmodified, not
        // snake-cased, for the element too).
        assertEquals(
            listOf(xsd("root", "false"), xsd("name", "\"gpx\"")).sortedBy { it.key },
            record(gpx, "Gpx").annotations.sortedBy { it.key },
        )
        assertEquals(emptyList(), gpx.diagnostics)

        val address =
            lower(
                """<?xml version="1.0"?>
            <xs:schema $xs xmlns:tns="urn:schemata:s" targetNamespace="urn:schemata:s">
              <xs:complexType name="Address"><xs:sequence/></xs:complexType>
            </xs:schema>
            """
            )
        // "Address" has no "Type" suffix to give back, so no override can ever regenerate it
        // exactly ("<name>Type" always ends in "Type"); reported, not annotated.
        assertEquals(
            emptyList(),
            record(address, "Address").annotations.filter { it.key == "name" },
        )
        assertEquals(
            listOf(
                "SCH2403 complex type 'Address': complex type 'Address' has no Schemata equivalent; the regenerated type will be named 'AddressType'"
            ),
            messages(address),
        )

        val collision =
            lower(
                """<?xml version="1.0"?>
            <xs:schema $xs xmlns:tns="urn:schemata:s" targetNamespace="urn:schemata:s">
              <xs:complexType name="OrderType"><xs:sequence/></xs:complexType>
              <xs:complexType name="Order"><xs:sequence/></xs:complexType>
            </xs:schema>
            """
            )
        // "Order" (no "Type" suffix) claims the clean name "Order" outright and is itself
        // unfixable (SCH2403, same as "Address"); "OrderType" would need an override ("Order") to
        // round-trip, but that override would regenerate "OrderType" — the same type name "Order"
        // itself already regenerates to by default — so no override is possible (SCH2401) and
        // "OrderType" keeps its own full name, unannotated.
        assertEquals(listOf(xsd("root", "false")), record(collision, "Order").annotations)
        assertEquals(listOf(xsd("root", "false")), record(collision, "OrderType").annotations)
        assertEquals(
            listOf(
                "SCH2401 complex type 'OrderType' and 'Order' both lower to type 'OrderType'",
                "SCH2403 complex type 'Order': complex type 'Order' has no Schemata equivalent; the regenerated type will be named 'OrderType'",
            ),
            messages(collision),
        )
    }

    @Test
    fun `only complex types and enumerated simple types claim names`() {
        // A plain-restriction simple type is inlined at every use and never becomes a declaration
        // (only an enumerated one, an enum, does); it must not compete for a name against
        // AddressType, which would otherwise wrongly collide with the name it strips to.
        val imported =
            lower(
                """
                <?xml version="1.0"?>
                <xs:schema $xs xmlns:tns="urn:schemata:s" targetNamespace="urn:schemata:s">
                  <xs:simpleType name="Address">
                    <xs:restriction base="xs:string"><xs:maxLength value="10"/></xs:restriction>
                  </xs:simpleType>
                  <xs:complexType name="AddressType">
                    <xs:sequence><xs:element name="x" type="xs:int"/></xs:sequence>
                  </xs:complexType>
                </xs:schema>
                """
            )
        assertEquals(emptyList(), imported.diagnostics)
        assertEquals("x", record(imported, "Address").fields.single().name)
    }

    @Test
    fun `field names become identifiers with the original kept`() {
        val renamed =
            lower(
                """
                <?xml version="1.0"?>
                <xs:schema $xs xmlns:tns="urn:schemata:s" targetNamespace="urn:schemata:s">
                  <xs:complexType name="AType">
                    <xs:sequence>
                      <xs:element name="full-name" type="xs:string"/>
                    </xs:sequence>
                  </xs:complexType>
                </xs:schema>
                """
            )
        assertEquals(
            listOf(
                UnitField(
                    "full_name",
                    UnitType.Scalar("string", emptyList()),
                    false,
                    null,
                    null,
                    listOf(xsd("name", "\"full-name\"")),
                )
            ),
            record(renamed, "A").fields,
        )
        assertEquals(emptyList(), renamed.diagnostics)

        val collision =
            lower(
                """
                <?xml version="1.0"?>
                <xs:schema $xs xmlns:tns="urn:schemata:s" targetNamespace="urn:schemata:s">
                  <xs:complexType name="BType">
                    <xs:sequence>
                      <xs:element name="full_name" type="xs:string"/>
                      <xs:element name="fullName" type="xs:string"/>
                    </xs:sequence>
                  </xs:complexType>
                </xs:schema>
                """
            )
        assertEquals(
            listOf(
                UnitField(
                    "full_name",
                    UnitType.Scalar("string", emptyList()),
                    false,
                    null,
                    null,
                    emptyList(),
                )
            ),
            record(collision, "B").fields,
        )
        assertEquals(
            listOf(
                "SCH2401 complex type 'BType': element 'full_name' and element 'fullName' both lower to field 'full_name'"
            ),
            messages(collision),
        )
    }

    @Test
    fun `a name that is not a valid XML name gets no override`() {
        val imported =
            lower(
                """
                <?xml version="1.0"?>
                <xs:schema $xs xmlns:tns="urn:schemata:s" targetNamespace="urn:schemata:s">
                  <xs:simpleType name="FixType">
                    <xs:restriction base="xs:string">
                      <xs:enumeration value="2d"/>
                      <xs:enumeration value="3d"/>
                    </xs:restriction>
                  </xs:simpleType>
                </xs:schema>
                """
            )
        assertEquals(
            listOf(
                UnitEnumValue("v2d", null, emptyList()),
                UnitEnumValue("v3d", null, emptyList()),
            ),
            enum(imported, "Fix").values,
        )
        assertEquals(
            listOf(
                "SCH2403 enum value 'Fix.2d': enum value 'Fix.2d' has no Schemata equivalent; " +
                    "imported as 'v2d'",
                "SCH2403 enum value 'Fix.3d': enum value 'Fix.3d' has no Schemata equivalent; " +
                    "imported as 'v3d'",
            ),
            messages(imported),
        )
    }

    @Test
    fun `anonymous complex types nest under their record`() {
        val imported =
            lower(
                """
                <?xml version="1.0"?>
                <xs:schema $xs xmlns:tns="urn:schemata:s" targetNamespace="urn:schemata:s">
                  <xs:complexType name="OrderType">
                    <xs:sequence>
                      <xs:element name="line">
                        <xs:complexType>
                          <xs:sequence>
                            <xs:element name="sku" type="xs:string"/>
                          </xs:sequence>
                        </xs:complexType>
                      </xs:element>
                    </xs:sequence>
                  </xs:complexType>
                </xs:schema>
                """
            )
        assertEquals(emptyList(), imported.diagnostics)
        val order = record(imported, "Order")
        assertEquals(UnitType.Ref("Line"), order.fields.single().type)
        assertEquals(
            listOf(
                UnitRecord(
                    "Line",
                    listOf(
                        UnitField(
                            "sku",
                            UnitType.Scalar("string", emptyList()),
                            false,
                            null,
                            null,
                            emptyList(),
                        )
                    ),
                    emptyList(),
                    null,
                    emptyList(),
                )
            ),
            order.nested,
        )
    }

    @Test
    fun `documentation lands on every construct`() {
        val imported =
            lower(
                """
                <?xml version="1.0"?>
                <xs:schema $xs xmlns:tns="urn:schemata:s" targetNamespace="urn:schemata:s">
                  <xs:complexType name="AType">
                    <xs:annotation>
                      <xs:documentation>Type doc.</xs:documentation>
                      <xs:documentation>Second.</xs:documentation>
                    </xs:annotation>
                    <xs:sequence>
                      <xs:element name="x" type="xs:string">
                        <xs:annotation><xs:documentation>Element doc.</xs:documentation></xs:annotation>
                      </xs:element>
                    </xs:sequence>
                    <xs:attribute name="y" type="xs:string">
                      <xs:annotation><xs:documentation>Attribute doc.</xs:documentation></xs:annotation>
                    </xs:attribute>
                  </xs:complexType>
                </xs:schema>
                """
            )
        val a = record(imported, "A")
        assertEquals("Type doc.\n\nSecond.", a.doc)
        assertEquals("Element doc.", a.fields.single { it.name == "x" }.doc)
        assertEquals("Attribute doc.", a.fields.single { it.name == "y" }.doc)
    }

    @Test
    fun `cross namespace references are qualified`() {
        val customers =
            doc(
                """
                <?xml version="1.0"?>
                <xs:schema $xs xmlns:tns="urn:schemata:shop.customers" targetNamespace="urn:schemata:shop.customers">
                  <xs:complexType name="CustomerType">
                    <xs:sequence><xs:element name="name" type="xs:string"/></xs:sequence>
                  </xs:complexType>
                </xs:schema>
                """,
                "shop/customers.xsd",
            )
        val orders =
            doc(
                """
                <?xml version="1.0"?>
                <xs:schema $xs xmlns:tns="urn:schemata:shop.orders" xmlns:c="urn:schemata:shop.customers" targetNamespace="urn:schemata:shop.orders">
                  <xs:import namespace="urn:schemata:shop.customers" schemaLocation="customers.xsd"/>
                  <xs:complexType name="OrderType">
                    <xs:sequence><xs:element name="customer" type="c:CustomerType"/></xs:sequence>
                  </xs:complexType>
                </xs:schema>
                """,
                "shop/orders.xsd",
            )
        val imported = XsdImport.lower(listOf(orders, customers), null)
        assertEquals(emptyList(), imported.diagnostics)
        val ordersUnit = imported.units.single { it.namespace == "shop.orders" }
        assertEquals(listOf("shop.customers"), ordersUnit.imports)
        val order =
            ordersUnit.declarations.filterIsInstance<UnitRecord>().single { it.name == "Order" }
        assertEquals(UnitType.Ref("shop.customers.Customer"), order.fields.single().type)
    }

    @Test
    fun `unresolved references are errors`() {
        val imported =
            lower(
                """
                <?xml version="1.0"?>
                <xs:schema $xs xmlns:tns="urn:schemata:s" targetNamespace="urn:schemata:s">
                  <xs:complexType name="AType">
                    <xs:sequence>
                      <xs:element name="x" type="tns:Missing"/>
                    </xs:sequence>
                  </xs:complexType>
                </xs:schema>
                """
            )
        assertEquals(emptyList(), record(imported, "A").fields)
        assertEquals(
            listOf("SCH2401 element 'x': type 'Missing' cannot be resolved"),
            messages(imported),
        )
    }

    @Test
    fun `a named complex type holding only a choice is a union and shared types are wrapped`() {
        val imported =
            lower(
                """
                <?xml version="1.0"?>
                <xs:schema $xs xmlns:tns="urn:schemata:s" targetNamespace="urn:schemata:s">
                  <xs:complexType name="CardType">
                    <xs:sequence><xs:element name="number" type="xs:string"/></xs:sequence>
                  </xs:complexType>
                  <xs:complexType name="BankTransferType">
                    <xs:sequence><xs:element name="iban" type="xs:string"/></xs:sequence>
                  </xs:complexType>
                  <xs:complexType name="CashType">
                    <xs:sequence/>
                  </xs:complexType>
                  <xs:complexType name="PaymentType">
                    <xs:choice>
                      <xs:element name="creditCard" type="tns:CardType"/>
                      <xs:element name="bank_transfer" type="tns:BankTransferType"/>
                      <xs:element name="cash" type="tns:CashType"/>
                      <xs:element name="int64" type="xs:long"/>
                      <xs:element name="voucher">
                        <xs:complexType>
                          <xs:sequence><xs:element name="code" type="xs:string"/></xs:sequence>
                        </xs:complexType>
                      </xs:element>
                      <xs:element name="coins" type="tns:CashType"/>
                    </xs:choice>
                  </xs:complexType>
                </xs:schema>
                """
            )
        assertEquals(
            listOf(
                UnitType.Ref("Card"),
                UnitType.Ref("BankTransfer"),
                UnitType.Ref("Cash2"),
                UnitType.Scalar("int64", emptyList()),
                UnitType.Ref("Voucher"),
                UnitType.Ref("Coins"),
            ),
            union(imported, "Payment").members.map { it.type },
        )
        listOf("Cash2", "Coins").forEach { wrapper ->
            val r = record(imported, wrapper)
            assertEquals(listOf(xsd("root", "false")), r.annotations)
            assertEquals(
                listOf("value" to UnitType.Ref("Cash")),
                r.fields.map { it.name to it.type },
            )
        }
        val voucher = record(imported, "Voucher")
        assertEquals(listOf(xsd("root", "false")), voucher.annotations)
        assertEquals("code", voucher.fields.single().name)
        assertEquals(
            listOf(
                "SCH2403 union 'Payment': member element name 'creditCard' has no Schemata equivalent and is dropped; " +
                    "the regenerated element will be named 'card'",
                "SCH2403 union 'Payment': members 'cash' and 'coins' share type 'Cash'; each " +
                    "imported as a model holding it",
                "SCH2403 union 'Payment': member element name 'cash' has no Schemata equivalent and is dropped; the " +
                    "regenerated element will be named 'cash2'",
                "SCH2403 union 'Payment': members 'cash' and 'coins' share type 'Cash'; each " +
                    "imported as a model holding it",
            ),
            messages(imported),
        )
    }

    @Test
    fun `a repeated element of an anonymous complex type is a list of its nested record`() {
        val imported =
            lower(
                """
                <?xml version="1.0"?>
                <xs:schema $xs targetNamespace="urn:schemata:s">
                  <xs:complexType name="GridType">
                    <xs:sequence>
                      <xs:element name="row" maxOccurs="unbounded">
                        <xs:complexType>
                          <xs:sequence><xs:element name="cell" type="xs:string"/></xs:sequence>
                        </xs:complexType>
                      </xs:element>
                      <xs:element name="pick" minOccurs="0" maxOccurs="2">
                        <xs:complexType>
                          <xs:choice>
                            <xs:element name="a" type="tns:GridType" xmlns:tns="urn:schemata:s"/>
                          </xs:choice>
                        </xs:complexType>
                      </xs:element>
                    </xs:sequence>
                  </xs:complexType>
                </xs:schema>
                """
            )
        val grid = record(imported, "Grid")
        assertEquals(
            listOf(
                UnitType.ListOf(UnitType.Ref("Row"), false, listOf("min" to "1")),
                UnitType.ListOf(UnitType.Ref("Pick"), false, listOf("max" to "2")),
            ),
            grid.fields.map { it.type },
        )
        assertEquals(listOf("Row", "Pick"), grid.nested.map { it.name })
        assertEquals("cell", (grid.nested[0] as UnitRecord).fields.single().name)
        assertEquals(
            listOf(UnitType.Ref("Grid")),
            (grid.nested[1] as UnitUnion).members.map { it.type },
        )
        assertEquals(
            listOf(
                "SCH2403 union 'Pick': member element name 'a' has no Schemata equivalent and is dropped; the " +
                    "regenerated element will be named 'grid'"
            ),
            messages(imported),
        )
    }

    @Test
    fun `an untyped union member is a string and a typeless substitute takes its head type`() {
        val imported =
            lower(
                """
                <?xml version="1.0"?>
                <xs:schema $xs xmlns:tns="urn:schemata:s" targetNamespace="urn:schemata:s">
                  <xs:complexType name="IdType"><xs:sequence/></xs:complexType>
                  <xs:element name="head" type="tns:IdType"/>
                  <xs:element name="alias" substitutionGroup="tns:head"/>
                  <xs:complexType name="PolicyType">
                    <xs:choice>
                      <xs:element name="implied"/>
                      <xs:element ref="tns:alias"/>
                    </xs:choice>
                  </xs:complexType>
                </xs:schema>
                """
            )
        assertEquals(
            listOf(UnitType.Scalar("string", emptyList()), UnitType.Ref("Id")),
            union(imported, "Policy").members.map { it.type },
        )
        assertEquals(
            listOf(
                "SCH2403 element 'head': element 'head' has no Schemata equivalent; the " +
                    "regenerated root element will be named 'id'",
                "SCH2404 union 'Policy': no declared type; treated as xs:anyType, imported as " +
                    "string",
                "SCH2403 union 'Policy': member element name 'implied' has no Schemata equivalent and is dropped; the " +
                    "regenerated element will be named 'string'",
                "SCH2403 union 'Policy': member element name 'alias' has no Schemata equivalent and is dropped; the " +
                    "regenerated element will be named 'id'",
                "SCH2403 element 'head': substitution group 'head' imported as its one member " +
                    "type 'Id'",
            ),
            messages(imported),
        )
    }

    @Test
    fun `a simple content value or a restriction of an enumeration names the enum`() {
        val imported =
            lower(
                """
                <?xml version="1.0"?>
                <xs:schema $xs xmlns:tns="urn:schemata:s" targetNamespace="urn:schemata:s">
                  <xs:simpleType name="RuleType">
                    <xs:restriction base="xs:string">
                      <xs:enumeration value="linear"/>
                      <xs:enumeration value="spiral"/>
                    </xs:restriction>
                  </xs:simpleType>
                  <xs:simpleType name="ShortRuleType">
                    <xs:restriction base="tns:RuleType"><xs:maxLength value="6"/></xs:restriction>
                  </xs:simpleType>
                  <xs:complexType name="SequenceRuleType">
                    <xs:simpleContent>
                      <xs:extension base="tns:RuleType">
                        <xs:attribute name="order" type="xs:string"/>
                      </xs:extension>
                    </xs:simpleContent>
                  </xs:complexType>
                  <xs:complexType name="UseType">
                    <xs:sequence><xs:element name="rule" type="tns:ShortRuleType"/></xs:sequence>
                  </xs:complexType>
                </xs:schema>
                """
            )
        assertEquals(
            UnitType.Ref("Rule"),
            record(imported, "SequenceRule").fields.first { it.name == "value" }.type,
        )
        assertEquals(UnitType.Ref("Rule"), record(imported, "Use").fields.single().type)
        assertEquals(
            listOf(
                "SCH2403 complex type 'SequenceRuleType': simpleContent extension of 'RuleType' " +
                    "has no Schemata equivalent; imported as a model with a 'value' field",
                "SCH2404 element 'rule': facet maxLength dropped",
            ),
            messages(imported),
        )
    }

    @Test
    fun `simple content derived from a complex type follows the chain to its simple root`() {
        val ccts =
            doc(
                """
                <xs:schema $xs targetNamespace="urn:schemata:ccts">
                  <xs:complexType name="AmountType">
                    <xs:simpleContent>
                      <xs:extension base="xs:decimal">
                        <xs:attribute name="currencyID" type="xs:normalizedString"/>
                        <xs:attribute name="currencyCodeListVersionID" type="xs:normalizedString"/>
                      </xs:extension>
                    </xs:simpleContent>
                  </xs:complexType>
                </xs:schema>
                """,
                "ccts.xsd",
            )
        val udt =
            doc(
                """
                <xs:schema $xs xmlns:c="urn:schemata:ccts" targetNamespace="urn:schemata:udt">
                  <xs:import namespace="urn:schemata:ccts"/>
                  <xs:complexType name="AmountType">
                    <xs:simpleContent>
                      <xs:restriction base="c:AmountType">
                        <xs:attribute name="currencyID" type="xs:normalizedString" use="required"/>
                      </xs:restriction>
                    </xs:simpleContent>
                  </xs:complexType>
                </xs:schema>
                """,
                "udt.xsd",
            )
        val cbc =
            doc(
                """
                <xs:schema $xs xmlns:u="urn:schemata:udt" targetNamespace="urn:schemata:cbc">
                  <xs:import namespace="urn:schemata:udt"/>
                  <xs:complexType name="AmountType">
                    <xs:simpleContent><xs:extension base="u:AmountType"/></xs:simpleContent>
                  </xs:complexType>
                </xs:schema>
                """,
                "cbc.xsd",
            )
        val imported = XsdImport.lower(listOf(ccts, udt, cbc), null)
        val amount =
            imported.units
                .single { it.namespace == "cbc" }
                .declarations
                .filterIsInstance<UnitRecord>()
                .single()
        assertEquals(
            listOf(
                Triple("value", UnitType.Scalar("decimal", listOf("p" to "38", "s" to "9")), false),
                Triple("currency_id", UnitType.Scalar("string", emptyList()), false),
                Triple(
                    "currency_code_list_version_id",
                    UnitType.Scalar("string", emptyList()),
                    true,
                ),
            ),
            amount.fields.map { Triple(it.name, it.type, it.nullable) },
        )
        assertEquals(
            listOf(
                "SCH2403 complex type 'AmountType': simpleContent extension of 'AmountType' has " +
                    "no Schemata equivalent; imported as a model with a 'value' field",
                "SCH2403 complex type 'AmountType': decimal without totalDigits and " +
                    "fractionDigits imported as decimal(38, 9)",
            ),
            imported.diagnostics
                .filter { it.span.file == "cbc.xsd" }
                .map { "${it.code.id} ${it.message}" },
        )
    }

    @Test
    fun `an attribute named like an element takes an attribute suffix`() {
        val imported =
            lower(
                """
                <?xml version="1.0"?>
                <xs:schema $xs xmlns:tns="urn:schemata:s" targetNamespace="urn:schemata:s">
                  <xs:complexType name="ArcType">
                    <xs:sequence><xs:element name="title" type="xs:string"/></xs:sequence>
                    <xs:attribute name="title" type="xs:string"/>
                  </xs:complexType>
                  <xs:complexType name="BaseType">
                    <xs:sequence/>
                    <xs:attribute name="axisLabels" type="xs:string"/>
                  </xs:complexType>
                  <xs:complexType name="GridType">
                    <xs:complexContent>
                      <xs:extension base="tns:BaseType">
                        <xs:sequence><xs:element name="axisLabels" type="xs:string"/></xs:sequence>
                      </xs:extension>
                    </xs:complexContent>
                  </xs:complexType>
                </xs:schema>
                """
            )
        val arc = record(imported, "Arc")
        assertEquals(listOf("title", "title_attribute"), arc.fields.map { it.name })
        assertEquals(listOf(xsd("attribute")), arc.fields[1].annotations)
        val grid = record(imported, "Grid")
        assertEquals(listOf("axis_labels_attribute", "axis_labels"), grid.fields.map { it.name })
        assertEquals(listOf(xsd("attribute")), grid.fields[0].annotations)
        assertEquals(listOf(xsd("name", "\"axisLabels\"")), grid.fields[1].annotations)
        assertEquals(
            listOf(
                "SCH2403 complex type 'ArcType': attribute 'title' and element 'title' both " +
                    "lower to field 'title'; the attribute is imported as 'title_attribute' and " +
                    "the regenerated attribute will be named so",
                "SCH2403 complex type 'GridType': extension of 'BaseType' has no Schemata " +
                    "equivalent; base fields flattened into the model",
                "SCH2403 complex type 'GridType': attribute 'axisLabels' and element " +
                    "'axisLabels' both lower to field 'axis_labels'; the attribute is imported " +
                    "as 'axis_labels_attribute' and the regenerated attribute will be named so",
            ),
            messages(imported),
        )
    }

    @Test
    fun `enumeration signs are spelled and a value still colliding is numbered`() {
        val imported =
            lower(
                """
                <?xml version="1.0"?>
                <xs:schema $xs targetNamespace="urn:schemata:s">
                  <xs:simpleType name="SignType">
                    <xs:restriction base="xs:string">
                      <xs:enumeration value="+"/>
                      <xs:enumeration value="-"/>
                      <xs:enumeration value="+x+y"/>
                      <xs:enumeration value="+x-y"/>
                      <xs:enumeration value="-x-y"/>
                      <xs:enumeration value="paid-out"/>
                      <xs:enumeration value="paid_out"/>
                      <xs:enumeration value="x y"/>
                      <xs:enumeration value="x.y"/>
                      <xs:enumeration value="x/y"/>
                    </xs:restriction>
                  </xs:simpleType>
                </xs:schema>
                """
            )
        val sign = enum(imported, "Sign")
        assertEquals(
            listOf(
                "plus",
                "minus",
                "plus_x_plus_y",
                "plus_x_y",
                "minus_x_y",
                "paid_out",
                "paid_out_2",
                "x_y",
                "x_y_2",
                "x_y_3",
            ),
            sign.values.map { it.name },
        )
        assertEquals(listOf(xsd("name", "\"paid-out\"")), sign.values[5].annotations)
        assertEquals(listOf(xsd("name", "\"paid_out\"")), sign.values[6].annotations)
        assertEquals(emptyList(), sign.values[7].annotations)
        assertEquals(listOf(xsd("name", "\"x.y\"")), sign.values[8].annotations)
        assertEquals(
            listOf(
                "SCH2403 enum value 'Sign.+': enum value 'Sign.+' has no Schemata equivalent; " +
                    "imported as 'plus'",
                "SCH2403 enum value 'Sign.-': enum value 'Sign.-' has no Schemata equivalent; " +
                    "imported as 'minus'",
                "SCH2403 enum value 'Sign.+x+y': enum value 'Sign.+x+y' has no Schemata " +
                    "equivalent; imported as 'plus_x_plus_y'",
                "SCH2403 enum value 'Sign.+x-y': enum value 'Sign.+x-y' has no Schemata " +
                    "equivalent; imported as 'plus_x_y'",
                "SCH2403 enum value 'Sign.-x-y': enum value 'Sign.-x-y' has no Schemata " +
                    "equivalent; imported as 'minus_x_y'",
                "SCH2403 enum value 'Sign.x y': enum value 'Sign.x y' has no Schemata " +
                    "equivalent; imported as 'x_y'",
                "SCH2403 enum value 'Sign.x/y': enum value 'Sign.x/y' has no Schemata " +
                    "equivalent; imported as 'x_y_3'",
            ),
            messages(imported),
        )
    }

    @Test
    fun `a second global element whose record name is taken is numbered`() {
        val imported =
            lower(
                """
                <?xml version="1.0"?>
                <xs:schema $xs targetNamespace="urn:schemata:s">
                  <xs:element name="secondParameter">
                    <xs:complexType><xs:sequence/></xs:complexType>
                  </xs:element>
                  <xs:element name="SecondParameter">
                    <xs:complexType>
                      <xs:sequence><xs:element name="x" type="xs:int"/></xs:sequence>
                    </xs:complexType>
                  </xs:element>
                </xs:schema>
                """
            )
        assertEquals(emptyList(), record(imported, "SecondParameter").fields)
        assertEquals("x", record(imported, "SecondParameter2").fields.single().name)
        assertEquals(
            listOf(
                "SCH2403 element 'secondParameter': the regenerated root element will be named " +
                    "'second_parameter'",
                "SCH2403 element 'SecondParameter': element 'secondParameter' already lowers to " +
                    "model 'SecondParameter'; imported as 'SecondParameter2'",
                "SCH2403 element 'SecondParameter': the regenerated root element will be named " +
                    "'second_parameter2'",
            ),
            messages(imported),
        )
    }

    @Test
    fun `a choice of nothing but wildcards is a record holding them`() {
        val imported =
            lower(
                """
                <?xml version="1.0"?>
                <xs:schema $xs targetNamespace="urn:schemata:s">
                  <xs:complexType name="PropertyType">
                    <xs:choice maxOccurs="unbounded">
                      <xs:any namespace="##other"/>
                    </xs:choice>
                    <xs:attribute name="target" type="xs:anyURI" use="required"/>
                  </xs:complexType>
                </xs:schema>
                """
            )
        val property = record(imported, "Property")
        assertEquals(
            listOf(
                "any" to
                    UnitType.ListOf(
                        UnitType.Scalar("string", emptyList()),
                        false,
                        listOf("min" to "1"),
                    ),
                "target" to UnitType.Scalar("string", emptyList()),
            ),
            property.fields.map { it.name to it.type },
        )
        assertEquals(
            listOf(xsd("any"), xsd("process", "\"strict\""), xsd("wildcard", "\"##other\"")),
            property.fields[0].annotations,
        )
        assertEquals(emptyList(), messages(imported))
    }

    @Test
    fun `a decimal union member takes the default precision and scale`() {
        val imported =
            lower(
                """
                <?xml version="1.0"?>
                <xs:schema $xs targetNamespace="urn:schemata:s">
                  <xs:complexType name="NumberType">
                    <xs:choice>
                      <xs:element name="decimal" type="xs:decimal"/>
                      <xs:element name="int32" type="xs:int"/>
                    </xs:choice>
                  </xs:complexType>
                </xs:schema>
                """
            )
        assertEquals(
            listOf(
                UnitType.Scalar("decimal", listOf("p" to "38", "s" to "9")),
                UnitType.Scalar("int32", emptyList()),
            ),
            union(imported, "Number").members.map { it.type },
        )
        assertEquals(
            listOf(
                "SCH2403 union 'Number': decimal without totalDigits and fractionDigits " +
                    "imported as decimal(38, 9)"
            ),
            messages(imported),
        )
    }

    @Test
    fun `a repeated choice type with an attribute is a record holding the choice`() {
        val imported =
            lower(
                """
                <?xml version="1.0"?>
                <xs:schema $xs xmlns:tns="urn:schemata:s" targetNamespace="urn:schemata:s">
                  <xs:complexType name="XPathType"><xs:sequence/></xs:complexType>
                  <xs:complexType name="StepType"><xs:sequence/></xs:complexType>
                  <xs:complexType name="TransformType">
                    <xs:choice maxOccurs="unbounded">
                      <xs:element name="XPath" type="tns:XPathType"/>
                      <xs:element name="step" type="tns:StepType"/>
                    </xs:choice>
                    <xs:attribute name="algorithm" type="xs:anyURI"/>
                  </xs:complexType>
                  <xs:complexType name="TransformsType">
                    <xs:sequence>
                      <xs:element name="transform" type="tns:TransformType" maxOccurs="unbounded"/>
                    </xs:sequence>
                  </xs:complexType>
                </xs:schema>
                """
            )
        val transform = record(imported, "Transform")
        assertEquals(
            listOf(
                Triple(
                    "choice",
                    UnitType.ListOf(UnitType.Ref("TransformChoice"), false, listOf("min" to "1")),
                    emptyList(),
                ),
                Triple(
                    "algorithm",
                    UnitType.Scalar("string", emptyList()),
                    listOf(xsd("attribute")),
                ),
            ),
            transform.fields.map { Triple(it.name, it.type, it.annotations) },
        )
        assertEquals(
            listOf(UnitType.Ref("XPath"), UnitType.Ref("Step")),
            union(imported, "TransformChoice").members.map { it.type },
        )
        assertEquals(
            UnitType.ListOf(UnitType.Ref("Transform"), false, listOf("min" to "1")),
            record(imported, "Transforms").fields.single().type,
        )
        assertEquals(
            listOf(
                "SCH2403 complex type 'TransformType': inline choice has no Schemata equivalent; " +
                    "imported as union 'TransformChoice' in field 'choice'"
            ),
            messages(imported),
        )
    }

    @Test
    fun `a named simple type with enumerations is an enum`() {
        val imported =
            lower(
                """
                <?xml version="1.0"?>
                <xs:schema $xs xmlns:tns="urn:schemata:s" targetNamespace="urn:schemata:s">
                  <xs:simpleType name="StatusType">
                    <xs:annotation><xs:documentation>Order status.</xs:documentation></xs:annotation>
                    <xs:restriction base="xs:string">
                      <xs:enumeration value="pending">
                        <xs:annotation><xs:documentation>Not yet paid.</xs:documentation></xs:annotation>
                      </xs:enumeration>
                      <xs:enumeration value="Personal"/>
                      <xs:enumeration value="shipped"/>
                    </xs:restriction>
                  </xs:simpleType>
                  <xs:simpleType name="AddressType">
                    <xs:restriction base="xs:string"><xs:maxLength value="10"/></xs:restriction>
                  </xs:simpleType>
                  <xs:complexType name="OrderType">
                    <xs:sequence>
                      <xs:element name="status" type="tns:StatusType"/>
                      <xs:element name="kind">
                        <xs:simpleType>
                          <xs:restriction base="xs:string">
                            <xs:enumeration value="work"/>
                            <xs:enumeration value="home"/>
                          </xs:restriction>
                        </xs:simpleType>
                      </xs:element>
                      <xs:element name="billing" type="tns:AddressType"/>
                    </xs:sequence>
                  </xs:complexType>
                </xs:schema>
                """
            )
        val status = enum(imported, "Status")
        assertEquals("Order status.", status.doc)
        assertEquals(
            listOf(
                UnitEnumValue("pending", "Not yet paid.", emptyList()),
                UnitEnumValue("personal", null, listOf(xsd("name", "\"Personal\""))),
                UnitEnumValue("shipped", null, emptyList()),
            ),
            status.values,
        )
        val order = record(imported, "Order")
        assertEquals(UnitType.Ref("Status"), order.fields.single { it.name == "status" }.type)
        assertEquals(UnitType.Ref("Kind"), order.fields.single { it.name == "kind" }.type)
        val kind = order.nested.filterIsInstance<UnitEnum>().single { it.name == "Kind" }
        assertEquals(listOf("work", "home"), kind.values.map { it.name })
        assertEquals(
            UnitType.Scalar("string", listOf("max" to "10")),
            order.fields.single { it.name == "billing" }.type,
        )
        assertEquals(emptyList(), imported.diagnostics)
    }

    @Test
    fun `list and union simple types import as a list and a string`() {
        val imported =
            lower(
                """
                <?xml version="1.0"?>
                <xs:schema $xs xmlns:tns="urn:schemata:s" targetNamespace="urn:schemata:s">
                  <xs:simpleType name="TagsType"><xs:list itemType="xs:string"/></xs:simpleType>
                  <xs:simpleType name="EitherType">
                    <xs:union memberTypes="xs:int xs:string"/>
                  </xs:simpleType>
                  <xs:complexType name="ThingType">
                    <xs:sequence>
                      <xs:element name="tags" type="tns:TagsType"/>
                      <xs:element name="either" type="tns:EitherType"/>
                    </xs:sequence>
                  </xs:complexType>
                </xs:schema>
                """
            )
        val string = UnitType.Scalar("string", emptyList())
        val thing = record(imported, "Thing").fields
        assertEquals(
            UnitType.ListOf(string, false, emptyList()),
            thing.single { it.name == "tags" }.type,
        )
        assertEquals(
            listOf(UnitAnnotation("xsd", "list", null)),
            thing.single { it.name == "tags" }.annotations,
        )
        assertEquals(string, thing.single { it.name == "either" }.type)
        assertEquals(
            listOf("SCH2403 element 'either': union simple type imported as string"),
            messages(imported),
        )
    }

    @Test
    fun `extension flattens the base first`() {
        val imported =
            lower(
                """
                <?xml version="1.0"?>
                <xs:schema $xs xmlns:tns="urn:schemata:s" targetNamespace="urn:schemata:s">
                  <xs:complexType name="BaseType">
                    <xs:sequence><xs:element name="id" type="xs:string"/></xs:sequence>
                    <xs:attribute name="tag" type="xs:string"/>
                  </xs:complexType>
                  <xs:complexType name="DerivedType">
                    <xs:complexContent>
                      <xs:extension base="tns:BaseType">
                        <xs:sequence><xs:element name="name" type="xs:string"/></xs:sequence>
                        <xs:attribute name="extra" type="xs:string"/>
                      </xs:extension>
                    </xs:complexContent>
                  </xs:complexType>
                  <xs:complexType name="ValueType">
                    <xs:simpleContent>
                      <xs:extension base="xs:int">
                        <xs:attribute name="unit" type="xs:string" use="required"/>
                        <xs:attribute name="code" type="xs:string"/>
                      </xs:extension>
                    </xs:simpleContent>
                  </xs:complexType>
                </xs:schema>
                """
            )
        val string = UnitType.Scalar("string", emptyList())
        assertEquals(listOf("id", "tag"), record(imported, "Base").fields.map { it.name })
        assertEquals(
            listOf("id", "tag", "name", "extra"),
            record(imported, "Derived").fields.map { it.name },
        )
        assertEquals(string, record(imported, "Derived").fields.single { it.name == "name" }.type)
        val value = record(imported, "Value").fields
        assertEquals(
            UnitType.Scalar("int32", emptyList()),
            value.single { it.name == "value" }.type,
        )
        assertEquals(false, value.single { it.name == "unit" }.nullable)
        assertEquals(true, value.single { it.name == "code" }.nullable)
        assertEquals(
            listOf(
                "SCH2403 complex type 'DerivedType': extension of 'BaseType' has no Schemata " +
                    "equivalent; base fields flattened into the model",
                "SCH2403 complex type 'ValueType': simpleContent extension of 'int' has no " +
                    "Schemata equivalent; imported as a model with a 'value' field",
            ),
            messages(imported),
        )
    }

    @Test
    fun `restriction of a complex type imports its own content`() {
        val imported =
            lower(
                """
                <?xml version="1.0"?>
                <xs:schema $xs xmlns:tns="urn:schemata:s" targetNamespace="urn:schemata:s">
                  <xs:complexType name="BaseType">
                    <xs:sequence>
                      <xs:element name="id" type="xs:string"/>
                      <xs:element name="extra" type="xs:string"/>
                    </xs:sequence>
                  </xs:complexType>
                  <xs:complexType name="NarrowType">
                    <xs:complexContent>
                      <xs:restriction base="tns:BaseType">
                        <xs:sequence><xs:element name="id" type="xs:string"/></xs:sequence>
                      </xs:restriction>
                    </xs:complexContent>
                  </xs:complexType>
                </xs:schema>
                """
            )
        assertEquals(listOf("id"), record(imported, "Narrow").fields.map { it.name })
        assertEquals(
            listOf(
                "SCH2403 complex type 'NarrowType': restriction of 'BaseType' has no Schemata " +
                    "equivalent; its own content is used"
            ),
            messages(imported),
        )
    }

    @Test
    fun `groups and attribute groups expand in place`() {
        val imported =
            lower(
                """
                <?xml version="1.0"?>
                <xs:schema $xs xmlns:tns="urn:schemata:s" targetNamespace="urn:schemata:s">
                  <xs:group name="AddressGroup">
                    <xs:sequence>
                      <xs:element name="street" type="xs:string"/>
                      <xs:element name="city" type="xs:string"/>
                    </xs:sequence>
                  </xs:group>
                  <xs:attributeGroup name="MetaGroup">
                    <xs:attribute name="id" type="xs:string"/>
                  </xs:attributeGroup>
                  <xs:complexType name="OrderType">
                    <xs:sequence>
                      <xs:element name="customer" type="xs:string"/>
                      <xs:group ref="tns:AddressGroup"/>
                    </xs:sequence>
                    <xs:attributeGroup ref="tns:MetaGroup"/>
                  </xs:complexType>
                </xs:schema>
                """
            )
        assertEquals(emptyList(), imported.diagnostics)
        assertEquals(
            listOf("customer", "street", "city", "id"),
            record(imported, "Order").fields.map { it.name },
        )
    }

    @Test
    fun `an inline choice of complex types becomes a synthesised union`() {
        val imported =
            lower(
                """
                <?xml version="1.0"?>
                <xs:schema $xs xmlns:tns="urn:schemata:s" targetNamespace="urn:schemata:s">
                  <xs:complexType name="CardType"><xs:sequence/></xs:complexType>
                  <xs:complexType name="CashType"><xs:sequence/></xs:complexType>
                  <xs:complexType name="OrderType">
                    <xs:sequence>
                      <xs:element name="id" type="xs:string"/>
                      <xs:choice minOccurs="0">
                        <xs:element name="card" type="tns:CardType"/>
                        <xs:element name="cash" type="tns:CashType"/>
                      </xs:choice>
                    </xs:sequence>
                  </xs:complexType>
                  <xs:complexType name="ThingType">
                    <xs:sequence>
                      <xs:element name="n" type="xs:string"/>
                      <xs:choice minOccurs="0">
                        <xs:element name="a" type="xs:int"/>
                        <xs:element name="b" type="xs:string"/>
                      </xs:choice>
                    </xs:sequence>
                  </xs:complexType>
                </xs:schema>
                """
            )
        assertEquals(
            listOf(UnitType.Ref("Card"), UnitType.Ref("Cash")),
            union(imported, "OrderChoice").members.map { it.type },
        )
        val order = record(imported, "Order").fields
        assertEquals(
            UnitField("id", UnitType.Scalar("string", emptyList()), false, null, null, emptyList()),
            order[0],
        )
        assertEquals(
            UnitField("choice", UnitType.Ref("OrderChoice"), true, null, null, emptyList()),
            order[1],
        )
        val thing = record(imported, "Thing").fields
        assertEquals(listOf("n", "a", "b"), thing.map { it.name })
        assertEquals(true, thing.single { it.name == "a" }.nullable)
        assertEquals(true, thing.single { it.name == "b" }.nullable)
        assertEquals(
            listOf(
                "SCH2403 complex type 'OrderType': inline choice has no Schemata equivalent; " +
                    "imported as union 'OrderChoice' in field 'choice'",
                "SCH2403 complex type 'ThingType': inline choice has no Schemata equivalent; " +
                    "members imported as optional fields",
            ),
            messages(imported),
        )
    }

    @Test
    fun `all takes the all annotation and a single nested sequence flattens`() {
        val imported =
            lower(
                """
                <?xml version="1.0"?>
                <xs:schema $xs xmlns:tns="urn:schemata:s" targetNamespace="urn:schemata:s">
                  <xs:complexType name="ThingType">
                    <xs:all>
                      <xs:element name="a" type="xs:string"/>
                      <xs:element name="b" type="xs:string"/>
                    </xs:all>
                  </xs:complexType>
                  <xs:complexType name="OrderType">
                    <xs:sequence>
                      <xs:element name="id" type="xs:string"/>
                      <xs:sequence>
                        <xs:element name="x" type="xs:string"/>
                        <xs:element name="y" type="xs:string"/>
                      </xs:sequence>
                    </xs:sequence>
                  </xs:complexType>
                </xs:schema>
                """
            )
        assertEquals(listOf("a", "b"), record(imported, "Thing").fields.map { it.name })
        assertEquals(
            listOf(UnitAnnotation("xsd", "root", "false"), UnitAnnotation("xsd", "all", null)),
            record(imported, "Thing").annotations,
        )
        assertEquals(listOf("id", "x", "y"), record(imported, "Order").fields.map { it.name })
        assertEquals(emptyList(), messages(imported))
    }

    @Test
    fun `dropped constructs are reported`() {
        val imported =
            lower(
                """
                <?xml version="1.0"?>
                <xs:schema $xs xmlns:tns="urn:schemata:s" targetNamespace="urn:schemata:s">
                  <xs:redefine schemaLocation="other.xsd"/>
                  <xs:override schemaLocation="other2.xsd"/>
                  <xs:notation name="n" public="x"/>
                  <xs:element name="extensions">
                    <xs:complexType><xs:sequence><xs:any/></xs:sequence></xs:complexType>
                  </xs:element>
                  <xs:complexType name="CatalogType">
                    <xs:sequence>
                      <xs:element name="special_offer" type="xs:string" substitutionGroup="tns:head"/>
                      <xs:element name="counts">
                        <xs:complexType>
                          <xs:sequence>
                            <xs:element name="entry" type="xs:int" maxOccurs="unbounded"/>
                          </xs:sequence>
                        </xs:complexType>
                        <xs:unique name="k">
                          <xs:selector xpath="tns:bogus"/>
                          <xs:field xpath="x"/>
                        </xs:unique>
                        <xs:key name="k2">
                          <xs:selector xpath="tns:entry"/>
                          <xs:field xpath="@id"/>
                        </xs:key>
                      </xs:element>
                    </xs:sequence>
                  </xs:complexType>
                  <xs:complexType name="ThingType" mixed="true" abstract="true">
                    <xs:sequence>
                      <xs:element name="a" type="xs:string"/>
                    </xs:sequence>
                    <xs:anyAttribute/>
                  </xs:complexType>
                  <xs:element name="head" type="xs:string"/>
                </xs:schema>
                """
            )
        assertEquals(listOf("any"), record(imported, "Extensions").fields.map { it.name })
        assertEquals(
            listOf(
                "SCH2405 schema: xs:redefine dropped",
                "SCH2405 schema: xs:override dropped",
                "SCH2405 schema: xs:notation dropped",
                "SCH2405 element 'counts': identity constraint 'k' dropped",
                "SCH2405 element 'counts': identity constraint 'k2' dropped",
                "SCH2405 complex type 'ThingType': abstract dropped",
                "SCH2405 element 'head': root element of simple type dropped",
            ),
            messages(imported),
        )
    }

    @Test
    fun `element references resolve to the global element`() {
        val imported =
            lower(
                """
                <?xml version="1.0"?>
                <xs:schema $xs xmlns:tns="urn:schemata:s" targetNamespace="urn:schemata:s">
                  <xs:element name="note" type="xs:string">
                    <xs:annotation><xs:documentation>A free-form note.</xs:documentation></xs:annotation>
                  </xs:element>
                  <xs:complexType name="OrderType">
                    <xs:sequence>
                      <xs:element ref="tns:note" minOccurs="0"/>
                    </xs:sequence>
                  </xs:complexType>
                </xs:schema>
                """
            )
        // the global element itself is only a reference target here: no record carries it as a
        // root, so it is reported as dropped while the reference still resolves to it
        assertEquals(
            listOf("SCH2405 element 'note': root element of simple type dropped"),
            messages(imported),
        )
        assertEquals(
            listOf(
                UnitField(
                    "note",
                    UnitType.Scalar("string", emptyList()),
                    true,
                    null,
                    "A free-form note.",
                    emptyList(),
                )
            ),
            record(imported, "Order").fields,
        )
    }

    @Test
    fun `cyclic complex type extension terminates and is reported`() {
        val selfCycle =
            lower(
                """
                <?xml version="1.0"?>
                <xs:schema $xs xmlns:tns="urn:schemata:s" targetNamespace="urn:schemata:s">
                  <xs:complexType name="DerivedType">
                    <xs:complexContent>
                      <xs:extension base="tns:DerivedType">
                        <xs:sequence><xs:element name="x" type="xs:string"/></xs:sequence>
                      </xs:extension>
                    </xs:complexContent>
                  </xs:complexType>
                </xs:schema>
                """
            )
        assertEquals(listOf("x"), record(selfCycle, "Derived").fields.map { it.name })
        assertEquals(
            listOf(
                "SCH2401 complex type 'DerivedType': extension of 'DerivedType' cannot be " +
                    "resolved; the base chain is cyclic"
            ),
            messages(selfCycle),
        )

        val twoTypeCycle =
            lower(
                """
                <?xml version="1.0"?>
                <xs:schema $xs xmlns:tns="urn:schemata:s" targetNamespace="urn:schemata:s">
                  <xs:complexType name="AType">
                    <xs:complexContent>
                      <xs:extension base="tns:BType">
                        <xs:sequence><xs:element name="a" type="xs:string"/></xs:sequence>
                      </xs:extension>
                    </xs:complexContent>
                  </xs:complexType>
                  <xs:complexType name="BType">
                    <xs:complexContent>
                      <xs:extension base="tns:AType">
                        <xs:sequence><xs:element name="b" type="xs:string"/></xs:sequence>
                      </xs:extension>
                    </xs:complexContent>
                  </xs:complexType>
                </xs:schema>
                """
            )
        // Each of the two types independently starts its own chain (A's and B's own top-level
        // import), and each chain terminates with exactly one SCH2401, at the point where it would
        // revisit a type already on its own chain.
        assertEquals(listOf("b", "a"), record(twoTypeCycle, "A").fields.map { it.name })
        assertEquals(listOf("a", "b"), record(twoTypeCycle, "B").fields.map { it.name })
        assertEquals(
            1,
            messages(twoTypeCycle).count {
                it ==
                    "SCH2401 complex type 'AType': extension of 'AType' cannot be resolved; " +
                        "the base chain is cyclic"
            },
        )
        assertEquals(
            1,
            messages(twoTypeCycle).count {
                it ==
                    "SCH2401 complex type 'BType': extension of 'BType' cannot be resolved; " +
                        "the base chain is cyclic"
            },
        )
    }

    @Test
    fun `a repeated group reference becomes a record of its own`() {
        val imported =
            lower(
                """
                <?xml version="1.0"?>
                <xs:schema $xs xmlns:tns="urn:schemata:s" targetNamespace="urn:schemata:s">
                  <xs:group name="g">
                    <xs:sequence>
                      <xs:element name="x" type="xs:string"/>
                      <xs:element name="y" type="xs:string"/>
                    </xs:sequence>
                  </xs:group>
                  <xs:complexType name="ThingType">
                    <xs:sequence>
                      <xs:element name="id" type="xs:string"/>
                      <xs:group ref="tns:g" maxOccurs="unbounded"/>
                    </xs:sequence>
                  </xs:complexType>
                </xs:schema>
                """
            )
        assertEquals(listOf("id", "g"), record(imported, "Thing").fields.map { it.name })
        assertEquals(
            listOf(
                "SCH2403 complex type 'ThingType': repeated group 'g' imported as model 'G' in " +
                    "field 'g'"
            ),
            messages(imported),
        )
    }

    @Test
    fun `an unknown enum default is dropped and reported`() {
        val imported =
            lower(
                """
                <?xml version="1.0"?>
                <xs:schema $xs xmlns:tns="urn:schemata:s" targetNamespace="urn:schemata:s">
                  <xs:simpleType name="StatusType">
                    <xs:restriction base="xs:string">
                      <xs:enumeration value="pending"/>
                      <xs:enumeration value="paid"/>
                    </xs:restriction>
                  </xs:simpleType>
                  <xs:complexType name="OrderType">
                    <xs:sequence>
                      <xs:element name="status" type="tns:StatusType" default="gone" minOccurs="0"/>
                    </xs:sequence>
                    <xs:attribute name="state" type="tns:StatusType" default="missing"/>
                  </xs:complexType>
                </xs:schema>
                """
            )
        val order = record(imported, "Order").fields
        val status = order.single { it.name == "status" }
        assertEquals(null, status.default)
        assertEquals(true, status.nullable)
        val state = order.single { it.name == "state" }
        assertEquals(null, state.default)
        assertEquals(true, state.nullable)
        assertEquals(
            listOf(
                "SCH2403 element 'status': default 'gone' is not a value of enum 'Status'; dropped",
                "SCH2403 attribute 'state': default 'missing' is not a value of enum 'Status'; dropped",
            ),
            messages(imported),
        )
    }

    @Test
    fun `two inline choices in one record are named and numbered deterministically`() {
        val imported =
            lower(
                """
                <?xml version="1.0"?>
                <xs:schema $xs xmlns:tns="urn:schemata:s" targetNamespace="urn:schemata:s">
                  <xs:complexType name="CardType"><xs:sequence/></xs:complexType>
                  <xs:complexType name="CashType"><xs:sequence/></xs:complexType>
                  <xs:complexType name="CheckType"><xs:sequence/></xs:complexType>
                  <xs:complexType name="WireType"><xs:sequence/></xs:complexType>
                  <xs:complexType name="RType">
                    <xs:sequence>
                      <xs:element name="id" type="xs:string"/>
                      <xs:choice>
                        <xs:element name="card" type="tns:CardType"/>
                        <xs:element name="cash" type="tns:CashType"/>
                      </xs:choice>
                      <xs:choice>
                        <xs:element name="check" type="tns:CheckType"/>
                        <xs:element name="wire" type="tns:WireType"/>
                      </xs:choice>
                    </xs:sequence>
                  </xs:complexType>
                </xs:schema>
                """
            )
        assertEquals(
            listOf(UnitType.Ref("Card"), UnitType.Ref("Cash")),
            union(imported, "RChoice").members.map { it.type },
        )
        assertEquals(
            listOf(UnitType.Ref("Check"), UnitType.Ref("Wire")),
            union(imported, "RChoice2").members.map { it.type },
        )
        val r = record(imported, "R").fields
        assertEquals(listOf("id", "choice", "choice_2"), r.map { it.name })
        assertEquals(UnitType.Ref("RChoice"), r.single { it.name == "choice" }.type)
        assertEquals(UnitType.Ref("RChoice2"), r.single { it.name == "choice_2" }.type)
        assertEquals(
            listOf(
                "SCH2403 complex type 'RType': inline choice has no Schemata equivalent; imported " +
                    "as union 'RChoice' in field 'choice'",
                "SCH2403 complex type 'RType': inline choice has no Schemata equivalent; imported " +
                    "as union 'RChoice2' in field 'choice_2'",
            ),
            messages(imported),
        )
    }

    @Test
    fun `keyword names take a value suffix and keep the original`() {
        val imported =
            lower(
                """
                <?xml version="1.0"?>
                <xs:schema $xs xmlns:tns="urn:schemata:s" targetNamespace="urn:schemata:s">
                  <xs:simpleType name="FlagType">
                    <xs:restriction base="xs:string">
                      <xs:enumeration value="true"/>
                      <xs:enumeration value="false"/>
                    </xs:restriction>
                  </xs:simpleType>
                  <xs:complexType name="FeedType">
                    <xs:sequence>
                      <xs:element name="stream" type="xs:string"/>
                    </xs:sequence>
                    <xs:attribute name="import" type="xs:string" use="required"/>
                  </xs:complexType>
                </xs:schema>
                """
            )
        assertEquals(emptyList(), imported.diagnostics)
        assertEquals(
            listOf(
                UnitEnumValue("true_value", null, listOf(xsd("name", "\"true\""))),
                UnitEnumValue("false_value", null, listOf(xsd("name", "\"false\""))),
            ),
            enum(imported, "Flag").values,
        )
        val feed = record(imported, "Feed").fields
        assertEquals(listOf("stream_value", "import_value"), feed.map { it.name })
        assertEquals(listOf(xsd("name", "\"stream\"")), feed[0].annotations)
        assertEquals(listOf(xsd("name", "\"import\""), xsd("attribute")), feed[1].annotations)
    }

    @Test
    fun `null is named like a keyword and a null default names the renamed value`() {
        val imported =
            lower(
                """
                <?xml version="1.0"?>
                <xs:schema $xs xmlns:tns="urn:schemata:s" targetNamespace="urn:schemata:s">
                  <xs:simpleType name="FlagType">
                    <xs:restriction base="xs:string">
                      <xs:enumeration value="on"/>
                      <xs:enumeration value="null"/>
                    </xs:restriction>
                  </xs:simpleType>
                  <xs:complexType name="FeedType">
                    <xs:sequence>
                      <xs:element name="flag" type="tns:FlagType" default="null"/>
                      <xs:element name="null" type="xs:string"/>
                    </xs:sequence>
                  </xs:complexType>
                </xs:schema>
                """
            )
        assertEquals(emptyList(), imported.diagnostics)
        assertEquals(
            listOf(
                UnitEnumValue("on", null, emptyList()),
                UnitEnumValue("null_value", null, listOf(xsd("name", "\"null\""))),
            ),
            enum(imported, "Flag").values,
        )
        val feed = record(imported, "Feed").fields
        assertEquals(listOf("flag", "null_value"), feed.map { it.name })
        assertEquals("null_value", feed[0].default)
        assertEquals(listOf(xsd("name", "\"null\"")), feed[1].annotations)
    }

    @Test
    fun `an element name starting with a digit names its nested record with a letter`() {
        val imported =
            lower(
                """
                <?xml version="1.0"?>
                <xs:schema $xs xmlns:tns="urn:schemata:s" targetNamespace="urn:schemata:s">
                  <xs:complexType name="ShapeType">
                    <xs:sequence>
                      <xs:element name="3d">
                        <xs:complexType>
                          <xs:sequence><xs:element name="z" type="xs:int"/></xs:sequence>
                        </xs:complexType>
                      </xs:element>
                    </xs:sequence>
                  </xs:complexType>
                </xs:schema>
                """
            )
        val shape = record(imported, "Shape")
        val field = shape.fields.single()
        assertEquals("v3d", field.name)
        assertEquals(UnitType.Ref("V3d"), field.type)
        // "3d" is not a valid XML name, so no override could regenerate it
        assertEquals(emptyList(), field.annotations)
        assertEquals(listOf("V3d"), shape.nested.map { it.name })
        assertEquals(
            listOf(
                "SCH2403 element '3d': element '3d' has no Schemata equivalent; imported as 'v3d'"
            ),
            messages(imported),
        )
    }

    @Test
    fun `a default with no Schemata literal is dropped and reported`() {
        val imported =
            lower(
                """
                <?xml version="1.0"?>
                <xs:schema $xs xmlns:tns="urn:schemata:s" targetNamespace="urn:schemata:s">
                  <xs:complexType name="ChildType"><xs:sequence/></xs:complexType>
                  <xs:complexType name="PayType">
                    <xs:choice maxOccurs="unbounded">
                      <xs:element name="child" type="tns:ChildType"/>
                    </xs:choice>
                  </xs:complexType>
                  <xs:complexType name="PointType">
                    <xs:sequence>
                      <xs:element name="x" type="xs:double" default="INF"/>
                      <xs:element name="y" type="xs:double" default=".5"/>
                      <xs:element name="ok" type="xs:boolean" default="1"/>
                      <xs:element name="at" type="xs:dateTime" default="2024-01-01T00:00:00Z" minOccurs="0"/>
                      <xs:element name="child" type="tns:ChildType" default="x"/>
                      <xs:element name="pays" type="tns:PayType" default="q"/>
                      <xs:element name="mode" default="slow">
                        <xs:simpleType>
                          <xs:restriction base="xs:string"><xs:enumeration value="fast"/></xs:restriction>
                        </xs:simpleType>
                      </xs:element>
                      <xs:element name="speed" default="fast">
                        <xs:simpleType>
                          <xs:restriction base="xs:string">
                            <xs:enumeration value="fast"/>
                            <xs:enumeration value="true"/>
                          </xs:restriction>
                        </xs:simpleType>
                      </xs:element>
                      <xs:element name="flag" default="true">
                        <xs:simpleType>
                          <xs:restriction base="xs:string"><xs:enumeration value="true"/></xs:restriction>
                        </xs:simpleType>
                      </xs:element>
                      <xs:element name="anon" default="z">
                        <xs:complexType><xs:sequence/></xs:complexType>
                      </xs:element>
                    </xs:sequence>
                    <xs:attribute name="w" type="xs:float" default="NaN"/>
                  </xs:complexType>
                </xs:schema>
                """
            )
        val fields = record(imported, "Point").fields.associateBy { it.name }
        assertEquals(
            mapOf(
                "x" to null,
                "y" to "0.5",
                "ok" to "true",
                "at" to null,
                "child" to null,
                "pays" to null,
                "mode" to null,
                "speed" to "fast",
                "flag" to "true_value",
                "anon" to null,
                "w" to null,
            ),
            fields.mapValues { it.value.default },
        )
        assertEquals(false, fields.getValue("x").nullable)
        assertEquals(true, fields.getValue("w").nullable)
        assertEquals(
            listOf(
                "SCH2403 complex type 'PayType': inline choice has no Schemata equivalent; " +
                    "imported as union 'PayChoice' in field 'choice'",
                "SCH2403 element 'x': default 'INF' has no Schemata literal; dropped",
                "SCH2403 element 'at': default '2024-01-01T00:00:00Z' has no Schemata literal; dropped",
                "SCH2403 element 'child': default 'x' has no Schemata literal; dropped",
                "SCH2403 element 'pays': default 'q' has no Schemata literal; dropped",
                "SCH2403 element 'mode': default 'slow' has no Schemata literal; dropped",
                "SCH2403 element 'anon': default 'z' has no Schemata literal; dropped",
                "SCH2403 attribute 'w': default 'NaN' has no Schemata literal; dropped",
            ),
            messages(imported),
        )
    }

    @Test
    fun `a choice only type keeps its type name override`() {
        val imported =
            lower(
                """
                <?xml version="1.0"?>
                <xs:schema $xs xmlns:tns="urn:schemata:s" targetNamespace="urn:schemata:s">
                  <xs:complexType name="CardType"><xs:sequence/></xs:complexType>
                  <xs:complexType name="paymentType">
                    <xs:choice><xs:element name="card" type="tns:CardType"/></xs:choice>
                  </xs:complexType>
                </xs:schema>
                """
            )
        assertEquals(emptyList(), imported.diagnostics)
        assertEquals(listOf(xsd("name", "\"payment\"")), union(imported, "Payment").annotations)
    }

    @Test
    fun `a repeated choice of simple branches repeats each branch`() {
        val imported =
            lower(
                """
                <?xml version="1.0"?>
                <xs:schema $xs targetNamespace="urn:schemata:s">
                  <xs:complexType name="TransformType" mixed="true">
                    <xs:choice minOccurs="0" maxOccurs="unbounded">
                      <xs:any namespace="##other" processContents="lax"/>
                      <xs:element name="XPath" type="xs:string"/>
                      <xs:element name="step" type="xs:int" maxOccurs="2"/>
                    </xs:choice>
                    <xs:attribute name="Algorithm" type="xs:anyURI" use="required"/>
                  </xs:complexType>
                  <xs:complexType name="PairType">
                    <xs:choice maxOccurs="3">
                      <xs:element name="a" type="xs:string" maxOccurs="2"/>
                      <xs:element name="b" type="xs:int"/>
                    </xs:choice>
                  </xs:complexType>
                </xs:schema>
                """
            )
        val string = UnitType.Scalar("string", emptyList())
        val int32 = UnitType.Scalar("int32", emptyList())
        assertEquals(
            listOf(
                "any" to UnitType.ListOf(string, false, emptyList()),
                "x_path" to UnitType.ListOf(string, false, emptyList()),
                "step" to UnitType.ListOf(int32, false, emptyList()),
                "text" to string,
                "algorithm" to string,
            ),
            record(imported, "Transform").fields.map { it.name to it.type },
        )
        assertEquals(
            listOf(
                "a" to UnitType.ListOf(string, false, listOf("max" to "6")),
                "b" to UnitType.ListOf(int32, false, listOf("max" to "3")),
            ),
            record(imported, "Pair").fields.map { it.name to it.type },
        )
    }

    @Test
    fun `a plain choice type is a union and an attributed or mixed one a record`() {
        val imported =
            lower(
                """
                <?xml version="1.0"?>
                <xs:schema $xs xmlns:tns="urn:schemata:s" targetNamespace="urn:schemata:s">
                  <xs:complexType name="CardType"><xs:sequence/></xs:complexType>
                  <xs:complexType name="CashType"><xs:sequence/></xs:complexType>
                  <xs:complexType name="TenderType" abstract="true">
                    <xs:choice minOccurs="0">
                      <xs:element name="card" type="tns:CardType"/>
                      <xs:element name="cash" type="tns:CashType"/>
                    </xs:choice>
                  </xs:complexType>
                  <xs:complexType name="PaymentType" mixed="true">
                    <xs:choice minOccurs="0"><xs:element name="card" type="tns:CardType"/></xs:choice>
                    <xs:attribute name="id" type="xs:string"/>
                    <xs:anyAttribute/>
                  </xs:complexType>
                </xs:schema>
                """
            )
        assertEquals(
            listOf(UnitType.Ref("Card"), UnitType.Ref("Cash")),
            union(imported, "Tender").members.map { it.type },
        )
        assertEquals(
            listOf(
                "choice" to UnitType.Ref("PaymentChoice"),
                "text" to UnitType.Scalar("string", emptyList()),
                "id" to UnitType.Scalar("string", emptyList()),
                "attributes" to
                    UnitType.MapOf(
                        UnitType.Scalar("string", emptyList()),
                        UnitType.Scalar("string", emptyList()),
                        false,
                        emptyList(),
                    ),
            ),
            record(imported, "Payment").fields.map { it.name to it.type },
        )
        assertEquals(true, record(imported, "Payment").fields.first().nullable)
        assertEquals(
            listOf(
                "SCH2405 union 'Tender': abstract dropped",
                "SCH2403 complex type 'PaymentType': inline choice has no Schemata equivalent; " +
                    "imported as union 'PaymentChoice' in field 'choice'",
            ),
            messages(imported),
        )
    }

    @Test
    fun `an anyType base is silent and a restriction keeps the attributes its bases declare`() {
        val imported =
            lower(
                """
                <?xml version="1.0"?>
                <xs:schema $xs xmlns:tns="urn:schemata:s" targetNamespace="urn:schemata:s">
                  <xs:complexType name="OpenType">
                    <xs:complexContent>
                      <xs:restriction base="xs:anyType">
                        <xs:sequence><xs:element name="x" type="xs:int"/></xs:sequence>
                      </xs:restriction>
                    </xs:complexContent>
                  </xs:complexType>
                  <xs:complexType name="RootType">
                    <xs:sequence/>
                    <xs:attribute name="id" type="xs:string" use="required"/>
                  </xs:complexType>
                  <xs:complexType name="MidType">
                    <xs:complexContent>
                      <xs:extension base="tns:RootType">
                        <xs:sequence><xs:element name="a" type="xs:int"/></xs:sequence>
                        <xs:attribute name="lang" type="xs:string"/>
                      </xs:extension>
                    </xs:complexContent>
                  </xs:complexType>
                  <xs:complexType name="LeafType">
                    <xs:complexContent>
                      <xs:restriction base="tns:MidType">
                        <xs:sequence><xs:element name="a" type="xs:int"/></xs:sequence>
                        <xs:attribute name="lang" use="prohibited"/>
                      </xs:restriction>
                    </xs:complexContent>
                  </xs:complexType>
                </xs:schema>
                """
            )
        assertEquals(listOf("x"), record(imported, "Open").fields.map { it.name })
        val leaf = record(imported, "Leaf")
        assertEquals(listOf("a", "id"), leaf.fields.map { it.name })
        assertEquals(false, leaf.fields[1].nullable)
        assertEquals(
            listOf(
                "SCH2403 complex type 'MidType': extension of 'RootType' has no Schemata " +
                    "equivalent; base fields flattened into the model",
                "SCH2403 complex type 'LeafType': restriction of 'MidType' has no Schemata " +
                    "equivalent; its own content is used",
            ),
            messages(imported),
        )
    }

    @Test
    fun `a second global element of one type is dropped`() {
        val imported =
            lower(
                """
                <?xml version="1.0"?>
                <xs:schema $xs xmlns:tns="urn:schemata:s" targetNamespace="urn:schemata:s">
                  <xs:complexType name="OrderType"><xs:sequence/></xs:complexType>
                  <xs:element name="order" type="tns:OrderType"/>
                  <xs:element name="other" type="tns:OrderType"/>
                </xs:schema>
                """
            )
        assertEquals(emptyList(), record(imported, "Order").annotations)
        assertEquals(
            listOf("SCH2405 element 'other': second root element for 'OrderType' dropped"),
            messages(imported),
        )
    }

    @Test
    fun `a global element of a simple or foreign type is dropped`() {
        val imported =
            lower(
                """
                <?xml version="1.0"?>
                <xs:schema $xs xmlns:tns="urn:schemata:s" xmlns:o="urn:other" targetNamespace="urn:schemata:s">
                  <xs:simpleType name="CodeType">
                    <xs:restriction base="xs:string"><xs:maxLength value="4"/></xs:restriction>
                  </xs:simpleType>
                  <xs:element name="x" type="xs:string"/>
                  <xs:element name="code" type="tns:CodeType"/>
                  <xs:element name="ext" type="o:ThingType"/>
                </xs:schema>
                """
            )
        assertEquals(emptyList(), imported.units.single().declarations)
        assertEquals(
            listOf(
                "SCH2405 element 'x': root element of simple type dropped",
                "SCH2405 element 'code': root element of simple type dropped",
                "SCH2405 element 'ext': root element of a type in another namespace dropped",
            ),
            messages(imported),
        )
    }

    @Test
    fun `a global element whose record name does not regenerate it is reported`() {
        val imported =
            lower(
                """
                <?xml version="1.0"?>
                <xs:schema $xs xmlns:tns="urn:schemata:s" targetNamespace="urn:schemata:s">
                  <xs:element name="myThing">
                    <xs:complexType>
                      <xs:sequence><xs:element name="x" type="xs:int"/></xs:sequence>
                    </xs:complexType>
                  </xs:element>
                </xs:schema>
                """
            )
        assertEquals("x", record(imported, "MyThing").fields.single().name)
        assertEquals(
            listOf(
                "SCH2403 element 'myThing': the regenerated root element will be named 'my_thing'"
            ),
            messages(imported),
        )
    }

    @Test
    fun `a global or hoisted element lowering to a named type's name is dropped`() {
        val imported =
            lower(
                """
                <?xml version="1.0"?>
                <xs:schema $xs xmlns:tns="urn:schemata:s" targetNamespace="urn:schemata:s">
                  <xs:complexType name="gpxType">
                    <xs:sequence><xs:element name="x" type="xs:int"/></xs:sequence>
                  </xs:complexType>
                  <xs:complexType name="CardType"><xs:sequence/></xs:complexType>
                  <xs:complexType name="PaymentType">
                    <xs:choice>
                      <xs:element name="string" type="xs:string"/>
                      <xs:element name="card">
                        <xs:complexType><xs:sequence/></xs:complexType>
                      </xs:element>
                    </xs:choice>
                  </xs:complexType>
                  <xs:element name="gpx">
                    <xs:complexType><xs:sequence/></xs:complexType>
                  </xs:element>
                </xs:schema>
                """
            )
        val declarations = imported.units.single().declarations
        assertEquals(listOf("Gpx", "Card", "Payment"), declarations.map { it.name })
        assertEquals("x", record(imported, "Gpx").fields.single().name)
        assertEquals(
            listOf(UnitType.Scalar("string", emptyList())),
            union(imported, "Payment").members.map { it.type },
        )
        assertEquals(
            listOf(
                "SCH2401 element 'card' and complex type 'CardType' both lower to type 'Card'",
                "SCH2401 element 'gpx' and complex type 'gpxType' both lower to type 'Gpx'",
            ),
            messages(imported),
        )
    }

    @Test
    fun `self referencing simple types groups and attribute groups are reported as cyclic`() {
        val imported =
            lower(
                """
                <?xml version="1.0"?>
                <xs:schema $xs xmlns:tns="urn:schemata:s" targetNamespace="urn:schemata:s">
                  <xs:simpleType name="LoopType">
                    <xs:restriction base="tns:LoopType"><xs:maxLength value="3"/></xs:restriction>
                  </xs:simpleType>
                  <xs:group name="g">
                    <xs:sequence>
                      <xs:element name="a" type="xs:int"/>
                      <xs:group ref="tns:g"/>
                    </xs:sequence>
                  </xs:group>
                  <xs:attributeGroup name="ag">
                    <xs:attribute name="b" type="xs:int"/>
                    <xs:attributeGroup ref="tns:ag"/>
                  </xs:attributeGroup>
                  <xs:complexType name="TType">
                    <xs:sequence>
                      <xs:element name="s" type="tns:LoopType"/>
                      <xs:group ref="tns:g"/>
                    </xs:sequence>
                    <xs:attributeGroup ref="tns:ag"/>
                  </xs:complexType>
                </xs:schema>
                """
            )
        val t = record(imported, "T").fields.single()
        assertEquals("s", t.name)
        assertEquals(UnitType.Scalar("string", listOf("max" to "3")), t.type)
        assertEquals(
            listOf(
                "SCH2401 s.xsd: group 'g' cannot be resolved; the reference chain is cyclic",
                "SCH2401 element 's': simple type 'LoopType' cannot be resolved; the reference " +
                    "chain is cyclic",
                "SCH2401 s.xsd: attribute group 'ag' cannot be resolved; the reference chain is " +
                    "cyclic",
            ),
            messages(imported),
        )
    }

    @Test
    fun `the decimal note points at the simple type that declares it`() {
        val imported =
            lower(
                """
                <?xml version="1.0"?>
                <xs:schema $xs xmlns:tns="urn:schemata:s" targetNamespace="urn:schemata:s">
                  <xs:simpleType name="PriceType">
                    <xs:restriction base="xs:decimal"><xs:minInclusive value="0"/></xs:restriction>
                  </xs:simpleType>
                  <xs:complexType name="ItemType">
                    <xs:sequence><xs:element name="price" type="tns:PriceType"/></xs:sequence>
                  </xs:complexType>
                </xs:schema>
                """
            )
        val d = imported.diagnostics.single()
        assertEquals(
            "element 'price': decimal without totalDigits and fractionDigits imported as " +
                "decimal(38, 9)",
            d.message,
        )
        assertEquals("s.xsd", d.span.file)
        assertEquals(3, d.span.startLine)
    }

    @Test
    fun `an extension base in another document reports at its own path`() {
        val base =
            doc(
                """
                <?xml version="1.0"?>
                <xs:schema $xs targetNamespace="urn:schemata:a">
                  <xs:complexType name="BaseType">
                    <xs:sequence><xs:sequence minOccurs="0"><xs:element name="x" type="xs:int"/></xs:sequence></xs:sequence>
                  </xs:complexType>
                </xs:schema>
                """,
                "a.xsd",
            )
        val derived =
            doc(
                """
                <?xml version="1.0"?>
                <xs:schema $xs xmlns:a="urn:schemata:a" targetNamespace="urn:schemata:b">
                  <xs:import namespace="urn:schemata:a" schemaLocation="a.xsd"/>
                  <xs:complexType name="DerivedType">
                    <xs:complexContent>
                      <xs:extension base="a:BaseType">
                        <xs:sequence><xs:element name="y" type="xs:int"/></xs:sequence>
                      </xs:extension>
                    </xs:complexContent>
                  </xs:complexType>
                </xs:schema>
                """,
                "b.xsd",
            )
        val imported = XsdImport.lower(listOf(derived, base), null)
        val spans =
            imported.diagnostics
                .filter { it.message.startsWith("complex type 'DerivedType'") }
                .associate { it.message to (it.span.file to it.span.startLine) }
        assertEquals(
            mapOf(
                "complex type 'DerivedType': nested sequence imported as model 'XGroup' in " +
                    "field 'x_group'" to ("a.xsd" to 4),
                "complex type 'DerivedType': extension of 'BaseType' has no Schemata equivalent; " +
                    "base fields flattened into the model" to ("b.xsd" to 6),
            ),
            spans,
        )
    }

    @Test
    fun `an element with maxOccurs zero is dropped`() {
        val imported =
            lower(
                """
                <?xml version="1.0"?>
                <xs:schema $xs xmlns:tns="urn:schemata:s" targetNamespace="urn:schemata:s">
                  <xs:complexType name="TType">
                    <xs:sequence>
                      <xs:element name="kept" type="xs:int"/>
                      <xs:element name="gone" type="xs:int" minOccurs="0" maxOccurs="0"/>
                    </xs:sequence>
                  </xs:complexType>
                </xs:schema>
                """
            )
        assertEquals(listOf("kept"), record(imported, "T").fields.map { it.name })
        assertEquals(listOf("SCH2405 element 'gone': maxOccurs 0 dropped"), messages(imported))
    }

    @Test
    fun `simple content of a complex base keeps a string value and its attributes`() {
        val imported =
            lower(
                """
                <?xml version="1.0"?>
                <xs:schema $xs xmlns:tns="urn:schemata:s" targetNamespace="urn:schemata:s">
                  <xs:complexType name="BaseType"><xs:sequence/></xs:complexType>
                  <xs:complexType name="ExtType">
                    <xs:simpleContent>
                      <xs:extension base="tns:BaseType">
                        <xs:attribute name="unit" type="xs:string"/>
                      </xs:extension>
                    </xs:simpleContent>
                  </xs:complexType>
                  <xs:complexType name="ResType">
                    <xs:simpleContent>
                      <xs:restriction base="tns:BaseType">
                        <xs:attribute name="unit" type="xs:string"/>
                      </xs:restriction>
                    </xs:simpleContent>
                  </xs:complexType>
                </xs:schema>
                """
            )
        listOf("Ext", "Res").forEach { name ->
            val fields = record(imported, name).fields
            assertEquals(listOf("value", "unit"), fields.map { it.name }, name)
            assertEquals(UnitType.Scalar("string", emptyList()), fields[0].type, name)
        }
        assertEquals(
            listOf(
                "SCH2403 complex type 'ExtType': simpleContent extension of 'BaseType' has no " +
                    "Schemata equivalent; imported as a model with a 'value' field",
                "SCH2403 complex type 'ExtType': simpleContent extension of complex type " +
                    "'BaseType' imported as string",
                "SCH2403 complex type 'ResType': simpleContent restriction of 'BaseType' has no " +
                    "Schemata equivalent; imported as a model with a 'value' field",
                "SCH2403 complex type 'ResType': simpleContent restriction of complex type " +
                    "'BaseType' imported as string",
            ),
            messages(imported),
        )
    }

    @Test
    fun `an import with no namespace is dropped by name when not found`() {
        val result =
            importAll(
                "s.xsd" to
                    """
                    <?xml version="1.0"?>
                    <xs:schema $xs targetNamespace="urn:schemata:s">
                      <xs:import schemaLocation="x.xsd"/>
                    </xs:schema>
                    """
            )
        assertEquals(
            listOf("SCH2405 s.xsd: import '(no namespace)' not found; dropped"),
            result.diagnostics.map { "${it.code.id} ${it.message}" },
        )
    }

    private fun importAll(vararg files: Pair<String, String>) =
        XsdImporter.import(files.map { ImportInput(it.first, it.second.trimIndent()) })

    private fun located(result: io.schemata.importer.ImportResult): List<String> =
        result.diagnostics.map {
            "${it.code.id} ${it.span.file}:${it.span.startLine} ${it.message}"
        }

    @Test
    fun `group content from an included file is reported at the group's file`() {
        val result =
            importAll(
                "a.xsd" to
                    """
                    <xs:schema $xs targetNamespace="urn:schemata:p" xmlns="urn:schemata:p">
                      <xs:include schemaLocation="b.xsd"/>
                      <xs:complexType name="TType">
                        <xs:sequence>
                          <xs:group ref="G"/>
                          <xs:group ref="Pair" maxOccurs="2"/>
                        </xs:sequence>
                        <xs:attributeGroup ref="AG"/>
                      </xs:complexType>
                    </xs:schema>
                    """,
                "b.xsd" to
                    """
                    <xs:schema $xs targetNamespace="urn:schemata:p" xmlns="urn:schemata:p">

                      <xs:group name="G">
                        <xs:sequence>
                          <xs:element name="x" type="Missing"/>
                          <xs:group ref="Nope"/>
                        </xs:sequence>
                      </xs:group>
                      <xs:group name="Pair">
                        <xs:sequence>
                          <xs:element name="y" type="MissingToo"/>
                          <xs:group ref="NopeToo"/>
                        </xs:sequence>
                      </xs:group>
                      <xs:attributeGroup name="AG">
                        <xs:attribute name="q" type="Missing2"/>
                        <xs:attributeGroup ref="NopeAg"/>
                      </xs:attributeGroup>
                    </xs:schema>
                    """,
            )
        assertEquals(
            listOf(
                "SCH2401 b.xsd:6 b.xsd: group 'Nope' cannot be resolved",
                "SCH2401 b.xsd:5 element 'x': type 'Missing' cannot be resolved",
                "SCH2401 b.xsd:12 b.xsd: group 'NopeToo' cannot be resolved",
                "SCH2401 b.xsd:11 element 'y': type 'MissingToo' cannot be resolved",
                "SCH2401 b.xsd:17 b.xsd: attribute group 'NopeAg' cannot be resolved",
                "SCH2401 b.xsd:16 attribute 'q': type 'Missing2' cannot be resolved",
            ),
            located(result).filter { "SCH2401" in it },
        )
    }

    @Test
    fun `a concrete head of a simple type is reported when it leaves its union`() {
        val imported =
            lower(
                """
                <?xml version="1.0"?>
                <xs:schema $xs targetNamespace="urn:schemata:p" xmlns="urn:schemata:p">
                  <xs:complexType name="AType"><xs:sequence><xs:element name="a" type="xs:string"/></xs:sequence></xs:complexType>
                  <xs:complexType name="BType"><xs:sequence><xs:element name="b" type="xs:string"/></xs:sequence></xs:complexType>
                  <xs:element name="head" type="xs:string"/>
                  <xs:element name="ma" type="AType" substitutionGroup="head"/>
                  <xs:element name="mb" type="BType" substitutionGroup="head"/>
                  <xs:element name="mc" type="xs:anyType" substitutionGroup="head"/>
                  <xs:element name="md" type="DType" substitutionGroup="head"/>
                  <xs:element name="me" substitutionGroup="head"><xs:complexType/></xs:element>
                </xs:schema>
                """
            )
        val dropped = messages(imported).filter { "dropped from union" in it }
        assertEquals(
            listOf(
                "SCH2405 element 'head': substitution member of simple type dropped from union 'Head'",
                "SCH2405 element 'mc': substitution member of xs:anyType dropped from union 'Head'",
                "SCH2405 element 'md': substitution member of unresolved type dropped from union 'Head'",
                "SCH2405 element 'me': substitution member with an inline type dropped from union 'Head'",
            ),
            dropped,
        )
    }

    @Test
    fun `a head union that lost its name to a record leaves no reference to it`() {
        val imported =
            lower(
                """
                <?xml version="1.0"?>
                <xs:schema $xs targetNamespace="urn:schemata:p" xmlns="urn:schemata:p">
                  <xs:complexType name="ShapeType"><xs:sequence><xs:element name="a" type="xs:string"/></xs:sequence></xs:complexType>
                  <xs:complexType name="CircleType"><xs:complexContent><xs:extension base="ShapeType"><xs:sequence><xs:element name="r" type="xs:int"/></xs:sequence></xs:extension></xs:complexContent></xs:complexType>
                  <xs:element name="shape" type="ShapeType"/>
                  <xs:element name="circle" type="CircleType" substitutionGroup="shape"/>
                  <xs:element name="shapeChoice"><xs:complexType><xs:sequence><xs:element name="z" type="xs:string"/></xs:sequence></xs:complexType></xs:element>
                  <xs:complexType name="UsesType"><xs:sequence><xs:element ref="shape"/></xs:sequence></xs:complexType>
                </xs:schema>
                """
            )
        assertEquals(
            listOf(
                "SCH2401 element 'shape' and element 'shapeChoice' both lower to type 'ShapeChoice'"
            ),
            messages(imported).filter { it.startsWith("SCH2401") },
        )
        assertEquals(UnitType.Ref("Shape"), record(imported, "Uses").fields.single().type)
    }

    @Test
    fun `a choice of a group of wildcards is a record not a union`() {
        val imported =
            lower(
                """
                <?xml version="1.0"?>
                <xs:schema $xs targetNamespace="urn:schemata:p" xmlns="urn:schemata:p">
                  <xs:group name="G"><xs:sequence><xs:any/></xs:sequence></xs:group>
                  <xs:complexType name="CType"><xs:choice><xs:group ref="G"/></xs:choice></xs:complexType>
                </xs:schema>
                """
            )
        assertEquals(listOf("any"), record(imported, "C").fields.map { it.name })
        assertEquals(emptyList(), messages(imported))
    }

    @Test
    fun `the first of two declarations of a name is the one heads see`() {
        val d =
            doc(
                """
                <?xml version="1.0"?>
                <xs:schema $xs targetNamespace="urn:schemata:p" xmlns="urn:schemata:p">
                  <xs:complexType name="BaseType"><xs:sequence><xs:element name="a" type="xs:string"/></xs:sequence></xs:complexType>
                  <xs:complexType name="BaseType" abstract="true"/>
                  <xs:complexType name="SubType"><xs:complexContent><xs:extension base="BaseType"><xs:sequence><xs:element name="b" type="xs:string"/></xs:sequence></xs:extension></xs:complexContent></xs:complexType>
                  <xs:complexType name="AType"/>
                  <xs:complexType name="BType"/>
                  <xs:complexType name="CType"/>
                  <xs:element name="h" type="AType"/>
                  <xs:element name="h" type="BType" abstract="true"/>
                  <xs:element name="m" type="CType" substitutionGroup="h"/>
                </xs:schema>
                """
            )
        val heads = Heads(listOf(d))
        assertEquals(emptySet(), heads.types.keys)
        assertEquals(
            listOf("AType", "CType"),
            heads.elements.getValue(QName("urn:schemata:p", "h")).members.map { it.local },
        )
    }

    @Test
    fun `two documents with no namespace each declare their own head union`() {
        val a =
            doc(
                """
                <xs:schema $xs>
                  <xs:complexType name="AType"><xs:sequence><xs:element name="a" type="xs:string"/></xs:sequence></xs:complexType>
                  <xs:complexType name="A2Type"><xs:sequence><xs:element name="a2" type="xs:string"/></xs:sequence></xs:complexType>
                  <xs:element name="ha" abstract="true" type="AType"/>
                  <xs:element name="ma" type="AType" substitutionGroup="ha"/>
                  <xs:element name="ma2" type="A2Type" substitutionGroup="ha"/>
                </xs:schema>
                """,
                "a.xsd",
            )
        val b =
            doc(
                """
                <xs:schema $xs>
                  <xs:complexType name="BType"><xs:sequence><xs:element name="b" type="xs:string"/></xs:sequence></xs:complexType>
                  <xs:complexType name="B2Type"><xs:sequence><xs:element name="b2" type="xs:string"/></xs:sequence></xs:complexType>
                  <xs:element name="hb" abstract="true" type="BType"/>
                  <xs:element name="mb" type="BType" substitutionGroup="hb"/>
                  <xs:element name="mb2" type="B2Type" substitutionGroup="hb"/>
                </xs:schema>
                """,
                "b.xsd",
            )
        val imported = XsdImport.lower(listOf(a, b), null)
        fun unions(namespace: String) =
            imported.units
                .single { it.namespace == namespace }
                .declarations
                .filterIsInstance<UnitUnion>()
        assertEquals(listOf("Ha"), unions("a").map { it.name })
        assertEquals(listOf("Hb"), unions("b").map { it.name })
        assertEquals(
            listOf(UnitType.Ref("B"), UnitType.Ref("B2")),
            unions("b").single().members.map { it.type },
        )
        assertEquals(emptyList(), imported.units.single { it.namespace == "b" }.imports)
    }

    @Test
    fun `mixed text follows an extension chain whichever type declares it`() {
        val imported =
            lower(
                """
                <?xml version="1.0"?>
                <xs:schema $xs targetNamespace="urn:schemata:p" xmlns="urn:schemata:p">
                  <xs:complexType name="BaseType" mixed="true"><xs:sequence><xs:element name="e" type="xs:string" minOccurs="0"/></xs:sequence></xs:complexType>
                  <xs:complexType name="DType"><xs:complexContent><xs:extension base="BaseType"><xs:sequence><xs:element name="f" type="xs:string"/></xs:sequence></xs:extension></xs:complexContent></xs:complexType>
                  <xs:complexType name="E1Type"><xs:sequence><xs:element name="e" type="xs:string" minOccurs="0"/></xs:sequence></xs:complexType>
                  <xs:complexType name="E2Type"><xs:complexContent mixed="true"><xs:extension base="E1Type"><xs:sequence><xs:element name="f" type="xs:string"/></xs:sequence></xs:extension></xs:complexContent></xs:complexType>
                </xs:schema>
                """
            )
        assertEquals(listOf("e", "text", "f"), record(imported, "D").fields.map { it.name })
        assertEquals(listOf("e", "f", "text"), record(imported, "E2").fields.map { it.name })
        assertEquals(listOf("e"), record(imported, "E1").fields.map { it.name })
    }

    @Test
    fun `an attribute clashing with an element is the only field renamed`() {
        val imported =
            lower(
                """
                <?xml version="1.0"?>
                <xs:schema $xs targetNamespace="urn:schemata:p" xmlns="urn:schemata:p">
                  <xs:complexType name="ThingType">
                    <xs:sequence>
                      <xs:element name="x" type="xs:string"/>
                      <xs:element name="y" type="xs:string"/>
                    </xs:sequence>
                    <xs:attribute name="id" type="xs:string"/>
                    <xs:attribute name="x" type="xs:string"/>
                    <xs:attribute name="z-z" type="xs:string"/>
                  </xs:complexType>
                  <xs:complexType name="OtherType">
                    <xs:attribute name="x-y" type="xs:string"/>
                    <xs:attribute name="w" type="xs:string"/>
                    <xs:sequence>
                      <xs:element name="x_y" type="xs:string"/>
                    </xs:sequence>
                  </xs:complexType>
                </xs:schema>
                """
            )
        val thing = record(imported, "Thing").fields
        assertEquals(listOf("x", "y", "id", "x_attribute", "z_z"), thing.map { it.name })
        assertEquals(
            listOf(xsd("attribute")),
            thing.single { it.name == "x_attribute" }.annotations,
        )
        assertEquals(
            listOf(xsd("name", "\"z-z\""), xsd("attribute")),
            thing.single { it.name == "z_z" }.annotations,
        )
    }

    @Test
    fun `a record is not a collection because its one field is called item`() {
        val imported =
            lower(
                """
                <?xml version="1.0"?>
                <xs:schema $xs targetNamespace="urn:schemata:p" xmlns="urn:schemata:p">
                  <xs:complexType name="RType"><xs:sequence>
                    <xs:element name="order" maxOccurs="unbounded"><xs:complexType><xs:sequence><xs:element name="item" type="xs:string"/></xs:sequence></xs:complexType></xs:element>
                    <xs:element name="grid" maxOccurs="unbounded"><xs:complexType><xs:sequence><xs:element name="item" type="xs:int" maxOccurs="unbounded"/></xs:sequence></xs:complexType></xs:element>
                  </xs:sequence></xs:complexType>
                </xs:schema>
                """
            )
        val r = record(imported, "R")
        assertEquals(
            UnitType.ListOf(UnitType.Ref("Order"), false, listOf("min" to "1")),
            r.fields.single { it.name == "order" }.type,
        )
        assertEquals(
            listOf("item"),
            r.nested
                .filterIsInstance<UnitRecord>()
                .single { it.name == "Order" }
                .fields
                .map { it.name },
        )
        assertEquals(
            UnitType.ListOf(
                UnitType.ListOf(UnitType.Scalar("int32", emptyList()), false, listOf("min" to "1")),
                false,
                listOf("min" to "1"),
            ),
            r.fields.single { it.name == "grid" }.type,
        )
    }

    @Test
    fun `a union member whose element name differs is noted for a substitution group too`() {
        val imported =
            lower(
                """
                <?xml version="1.0"?>
                <xs:schema $xs targetNamespace="urn:schemata:p" xmlns="urn:schemata:p">
                  <xs:complexType name="AType"><xs:sequence><xs:element name="a" type="xs:string"/></xs:sequence></xs:complexType>
                  <xs:complexType name="BType"><xs:sequence><xs:element name="b" type="xs:string"/></xs:sequence></xs:complexType>
                  <xs:element name="head" abstract="true" type="AType"/>
                  <xs:element name="alpha" type="AType" substitutionGroup="head"/>
                  <xs:element name="bType" type="BType" substitutionGroup="head"/>
                </xs:schema>
                """
            )
        assertTrue(
            "SCH2403 element 'head': member element name 'alpha' has no Schemata equivalent and is dropped; the regenerated element will be named 'a'" in
                messages(imported)
        )
        assertTrue(
            "SCH2403 element 'head': member element name 'bType' has no Schemata equivalent and is dropped; the regenerated element will be named 'b'" in
                messages(imported)
        )
    }

    @Test
    fun `a substitution group is described by its union or its one member type`() {
        val imported =
            lower(
                """
                <?xml version="1.0"?>
                <xs:schema $xs targetNamespace="urn:schemata:p" xmlns="urn:schemata:p">
                  <xs:complexType name="AType"><xs:sequence><xs:element name="a" type="xs:string"/></xs:sequence></xs:complexType>
                  <xs:complexType name="BType"><xs:sequence><xs:element name="b" type="xs:string"/></xs:sequence></xs:complexType>
                  <xs:element name="one" abstract="true" type="AType"/>
                  <xs:element name="oneMember" type="AType" substitutionGroup="one"/>
                  <xs:element name="two" abstract="true" type="AType"/>
                  <xs:element name="twoA" type="AType" substitutionGroup="two"/>
                  <xs:element name="twoB" type="BType" substitutionGroup="two"/>
                </xs:schema>
                """
            )
        val wording = messages(imported).filter { "substitution group" in it }
        assertEquals(
            listOf(
                    "SCH2403 element 'two': substitution group 'two' imported as union 'Two' of 2 member types; the regenerated XSD uses a choice",
                    "SCH2403 element 'one': substitution group 'one' imported as its one member type 'A'",
                )
                .sorted(),
            wording.sorted(),
        )
    }

    @Test
    fun `an element form that differs from the schema default is noted`() {
        val imported =
            lower(
                """
                <?xml version="1.0"?>
                <xs:schema $xs targetNamespace="urn:schemata:p" xmlns="urn:schemata:p">
                  <xs:complexType name="ThingType">
                    <xs:sequence>
                      <xs:element name="a" type="xs:string" form="qualified"/>
                      <xs:element name="b" type="xs:string" form="unqualified"/>
                    </xs:sequence>
                  </xs:complexType>
                </xs:schema>
                """
            )
        assertEquals(
            listOf(
                "SCH2403 element 'a': form 'qualified' differs from the schema default; dropped"
            ),
            messages(imported).filter { "form" in it },
        )
    }

    @Test
    fun `a group referenced twice is numbered the second time`() {
        val imported =
            lower(
                """
                <?xml version="1.0"?>
                <xs:schema $xs targetNamespace="urn:schemata:p" xmlns="urn:schemata:p">
                  <xs:group name="pair"><xs:sequence>
                    <xs:element name="l" type="xs:string"/><xs:element name="r" type="xs:string"/>
                  </xs:sequence></xs:group>
                  <xs:complexType name="BagType"><xs:sequence>
                    <xs:group ref="pair" maxOccurs="unbounded"/>
                    <xs:group ref="pair" minOccurs="0"/>
                  </xs:sequence></xs:complexType>
                </xs:schema>
                """
            )
        val bag = record(imported, "Bag")
        assertEquals(listOf("pair", "pair_2"), bag.fields.map { it.name })
        assertEquals(listOf("Pair", "Pair2"), bag.nested.map { it.name })
        assertTrue(
            "SCH2403 complex type 'BagType': repeated group 'pair' imported as model 'Pair2' in field 'pair_2'" in
                messages(imported)
        )
    }

    @Test
    fun `a redefined document is merged and its redefinition reported`() {
        val result =
            importAll(
                "a.xsd" to
                    """
                    <xs:schema $xs targetNamespace="urn:schemata:p" xmlns="urn:schemata:p">
                      <xs:redefine schemaLocation="b.xsd">
                        <xs:complexType name="BType">
                          <xs:complexContent><xs:extension base="BType"><xs:sequence><xs:element name="more" type="xs:string"/></xs:sequence></xs:extension></xs:complexContent>
                        </xs:complexType>
                      </xs:redefine>
                    </xs:schema>
                    """,
                "b.xsd" to
                    """
                    <xs:schema $xs targetNamespace="urn:schemata:p" xmlns="urn:schemata:p">
                      <xs:complexType name="BType"><xs:sequence><xs:element name="b" type="xs:string"/></xs:sequence></xs:complexType>
                    </xs:schema>
                    """,
            )
        assertEquals(listOf("p.schemata"), result.files.map { it.path })
        assertTrue("model B {" in result.files.single().content)
        assertEquals(
            listOf("SCH2405 a.xsd:2 schema: xs:redefine dropped"),
            located(result).filter { "redefine" in it },
        )
    }

    @Test
    fun `a chameleon document is rebased so its unqualified references name the including namespace`() {
        val chameleon =
            doc(
                """
                <xs:schema $xs>
                  <xs:complexType name="InnerType"><xs:sequence><xs:element name="v" type="xs:string"/></xs:sequence></xs:complexType>
                  <xs:complexType name="OuterType"><xs:sequence><xs:element name="inner" type="InnerType"/></xs:sequence></xs:complexType>
                  <xs:element name="outer" type="OuterType"/>
                </xs:schema>
                """,
                "c.xsd",
            )
        val imported = XsdImport.lower(listOf(chameleon.rebased("urn:schemata:p")), null)
        assertEquals(emptyList(), messages(imported))
        assertEquals(UnitType.Ref("Inner"), record(imported, "Outer").fields.single().type)
    }

    @Test
    fun `a map whose value is a list simple type keeps the list`() {
        val imported =
            lower(
                """
                <?xml version="1.0"?>
                <xs:schema $xs xmlns:tns="urn:schemata:p" targetNamespace="urn:schemata:p">
                  <xs:simpleType name="IntList"><xs:list itemType="xs:int"/></xs:simpleType>
                  <xs:complexType name="ThingType">
                    <xs:sequence>
                      <xs:element name="m">
                        <xs:complexType>
                          <xs:sequence>
                            <xs:element name="entry" minOccurs="0" maxOccurs="unbounded">
                              <xs:complexType>
                                <xs:simpleContent>
                                  <xs:extension base="tns:IntList">
                                    <xs:attribute name="key" type="xs:string" use="required"/>
                                  </xs:extension>
                                </xs:simpleContent>
                              </xs:complexType>
                            </xs:element>
                          </xs:sequence>
                        </xs:complexType>
                        <xs:unique name="k"><xs:selector xpath="tns:entry"/><xs:field xpath="@key"/></xs:unique>
                      </xs:element>
                    </xs:sequence>
                  </xs:complexType>
                </xs:schema>
                """
            )
        assertEquals(emptyList(), messages(imported))
        assertEquals(
            UnitType.MapOf(
                UnitType.Scalar("string", emptyList()),
                UnitType.ListOf(UnitType.Scalar("int32", emptyList()), false, emptyList()),
                false,
                emptyList(),
            ),
            record(imported, "Thing").fields.single().type,
        )
    }

    @Test
    fun `an unresolved type names the unresolved imports in its help`() {
        val xml =
            """
            <?xml version="1.0"?>
            <xs:schema $xs xmlns:tns="urn:schemata:s" xmlns:m="urn:x:m" targetNamespace="urn:schemata:s">
              <xs:complexType name="AType">
                <xs:sequence>
                  <xs:element name="x" type="m:Thing"/>
                </xs:sequence>
              </xs:complexType>
            </xs:schema>
            """
        fun help(vararg missing: String) =
            XsdImport.lower(listOf(doc(xml).copy(unresolvedImports = missing.toList())), null)
                .diagnostics
                .single()
                .help
        assertEquals(
            "import 'urn:x:m' was not found; add the schema that declares it",
            help("urn:x:m"),
        )
        assertEquals(
            "imports 'urn:x:m', 'urn:x:n' were not found; add the schemas that declare them",
            help("urn:x:m", "urn:x:n"),
        )
    }

    @Test
    fun `an idrefs attribute is a string list with the list key`() {
        val imported =
            lower(
                """
                <xs:schema $xs xmlns:tns="urn:schemata:s" targetNamespace="urn:schemata:s">
                  <xs:complexType name="HolderType">
                    <xs:attribute name="refs" type="xs:IDREFS"/>
                    <xs:attribute name="one" type="xs:IDREFS" use="required"/>
                  </xs:complexType>
                </xs:schema>
                """
            )
        val refs = record(imported, "Holder").fields.single { it.name == "refs" }
        assertEquals(
            UnitType.ListOf(UnitType.Scalar("string", emptyList()), false, emptyList()),
            refs.type,
        )
        assertEquals(listOf(xsd("list"), xsd("attribute")), refs.annotations)
        assertTrue(
            "SCH2404 attribute 'refs': xs:IDREFS imported as a list of string" in messages(imported)
        )
    }

    @Test
    fun `an element typed idrefs is a string list with the list key`() {
        val imported =
            lower(
                """
                <xs:schema $xs xmlns:tns="urn:schemata:s" targetNamespace="urn:schemata:s">
                  <xs:complexType name="HolderType">
                    <xs:sequence><xs:element name="refs" type="xs:IDREFS"/></xs:sequence>
                  </xs:complexType>
                </xs:schema>
                """
            )
        val refs = record(imported, "Holder").fields.single()
        assertEquals(
            UnitType.ListOf(UnitType.Scalar("string", emptyList()), false, emptyList()),
            refs.type,
        )
        assertTrue(refs.annotations.contains(xsd("list")))
    }

    @Test
    fun `a note on an inherited attribute is reported once`() {
        val imported =
            lower(
                """
                <xs:schema $xs xmlns:tns="urn:schemata:s" targetNamespace="urn:schemata:s">
                  <xs:complexType name="BaseType" abstract="true">
                    <xs:attribute name="refs" type="xs:IDREFS"/>
                  </xs:complexType>
                  <xs:complexType name="AType"><xs:complexContent><xs:extension base="tns:BaseType"><xs:sequence/></xs:extension></xs:complexContent></xs:complexType>
                  <xs:complexType name="BType"><xs:complexContent><xs:extension base="tns:BaseType"><xs:sequence/></xs:extension></xs:complexContent></xs:complexType>
                  <xs:complexType name="CType"><xs:complexContent><xs:extension base="tns:BaseType"><xs:sequence/></xs:extension></xs:complexContent></xs:complexType>
                </xs:schema>
                """
            )
        val notes = imported.diagnostics.filter { "xs:IDREFS" in it.message }
        assertEquals(1, notes.size)
        assertEquals(3, notes.single().span?.startLine)
    }

    @Test
    fun `a typeless abstract head with no member is dropped where it is used`() {
        val imported =
            lower(
                """
                <xs:schema $xs xmlns:tns="urn:schemata:s" targetNamespace="urn:schemata:s">
                  <xs:element name="Augmentation" abstract="true"/>
                  <xs:element name="Typed" type="xs:string" abstract="true"/>
                  <xs:complexType name="HolderType">
                    <xs:sequence>
                      <xs:element name="id" type="xs:string"/>
                      <xs:element ref="tns:Augmentation" minOccurs="0" maxOccurs="unbounded"/>
                      <xs:element ref="tns:Typed" minOccurs="0"/>
                    </xs:sequence>
                  </xs:complexType>
                </xs:schema>
                """
            )
        val fields = record(imported, "Holder").fields
        assertEquals(listOf("id", "typed"), fields.map { it.name })
        assertTrue(fields.none { f -> f.annotations.contains(xsd("any_type")) })
        val msgs = messages(imported)
        assertEquals(
            1,
            msgs.count {
                it ==
                    "SCH2405 element 'Augmentation': abstract element 'Augmentation' has no " +
                        "type and no substituting element; dropped"
            },
        )
        assertEquals(
            listOf("SCH2405 element 'Typed': abstract dropped"),
            msgs.filter { "abstract dropped" in it },
        )
    }
}
