package io.schemata.importer.xsd

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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

    private fun messages(imported: Imported): List<String> =
        imported.diagnostics.map { "${it.code.id} ${it.message}" }

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
                      <xs:enumeration value="Personal"/>
                      <xs:enumeration value="work"/>
                    </xs:restriction>
                  </xs:simpleType>
                  <xs:complexType name="ThingType">
                    <xs:sequence>
                      <xs:element name="a" type="xs:string" minOccurs="0"/>
                      <xs:element name="b" type="xs:string" default="pending"/>
                      <xs:element name="c" type="xs:string" nillable="true"/>
                      <xs:element name="d" type="xs:string" fixed="x"/>
                      <xs:element name="kind" type="tns:KindType" default="Personal"/>
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
                "SCH2403 element 'counts': map wrapper without xs:unique imported as a nested record"
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
        // gpxType has no global element at all here, so Gpx also gets @xsd(root = false); the
        // interesting annotation is the name override, and its value is the bare remainder "gpx"
        // (not "Gpx"), since that's what regenerates "gpxType" exactly (and "gpx" unmodified, not
        // snake-cased, for the element too).
        assertEquals(
            listOf(xsd("root", "false"), xsd("name", "\"gpx\"")).sortedBy { it.key },
            record(gpx, "Gpx").annotations.sortedBy { it.key },
        )
        assertEquals(
            listOf(
                "SCH2402 complex type 'gpxType': complex type 'gpxType' is not a Schemata identifier; imported as 'Gpx' with @xsd(name)"
            ),
            messages(gpx),
        )

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
        // (an enumerated one, an enum, is Task 4's); it must not compete for a name against
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
        assertEquals(
            listOf(
                "SCH2402 element 'full-name': element 'full-name' is not a Schemata identifier; imported as 'full_name' with @xsd(name)"
            ),
            messages(renamed),
        )

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
                "SCH2402 element 'fullName': element 'fullName' is not a Schemata identifier; imported as 'full_name' with @xsd(name)",
                "SCH2401 complex type 'BType': element 'full_name' and element 'fullName' both lower to field 'full_name'",
            ),
            messages(collision),
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
}
