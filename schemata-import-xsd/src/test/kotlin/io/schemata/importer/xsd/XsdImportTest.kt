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

    private fun union(imported: Imported, name: String): UnitUnion =
        imported.units.single().declarations.filterIsInstance<UnitUnion>().single {
            it.name == name
        }

    private fun enum(imported: Imported, name: String): UnitEnum =
        imported.units.single().declarations.filterIsInstance<UnitEnum>().single { it.name == name }

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
        // gpxType has no global element at all here, so Gpx also gets @xsd(root = false); the
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
    fun `a named complex type holding only a choice is a union`() {
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
                      <xs:element name="cash2" type="tns:CashType"/>
                    </xs:choice>
                  </xs:complexType>
                </xs:schema>
                """
            )
        assertEquals(
            listOf(
                UnitType.Ref("Card"),
                UnitType.Ref("BankTransfer"),
                UnitType.Ref("Cash"),
                UnitType.Scalar("int64", emptyList()),
                UnitType.Ref("Voucher"),
            ),
            union(imported, "Payment").members.map { it.type },
        )
        val voucher = record(imported, "Voucher")
        assertEquals(listOf(xsd("root", "false")), voucher.annotations)
        assertEquals("code", voucher.fields.single().name)
        assertEquals(
            listOf(
                "SCH2403 union 'Payment': member element 'creditCard' has no Schemata equivalent; " +
                    "the regenerated element will be named 'card'",
                "SCH2401 union 'Payment': members 'cash' and 'cash2' both lower to member 'Cash'",
            ),
            messages(imported),
        )
    }

    @Test
    fun `a choice with maxOccurs above one is a list of the union`() {
        val imported =
            lower(
                """
                <?xml version="1.0"?>
                <xs:schema $xs xmlns:tns="urn:schemata:s" targetNamespace="urn:schemata:s">
                  <xs:complexType name="CardType"><xs:sequence/></xs:complexType>
                  <xs:complexType name="CashType"><xs:sequence/></xs:complexType>
                  <xs:complexType name="PaymentsType">
                    <xs:choice maxOccurs="unbounded">
                      <xs:element name="card" type="tns:CardType"/>
                      <xs:element name="cash" type="tns:CashType"/>
                    </xs:choice>
                  </xs:complexType>
                  <xs:complexType name="OrderType">
                    <xs:sequence>
                      <xs:element name="payments" type="tns:PaymentsType"/>
                    </xs:sequence>
                  </xs:complexType>
                </xs:schema>
                """
            )
        assertEquals(
            listOf(UnitType.Ref("Card"), UnitType.Ref("Cash")),
            union(imported, "Payments").members.map { it.type },
        )
        assertEquals(
            UnitType.ListOf(UnitType.Ref("Payments"), false, listOf("min" to "1")),
            record(imported, "Order").fields.single().type,
        )
        assertEquals(
            listOf(
                "SCH2403 element 'payments': type 'PaymentsType' is a repeated choice; imported " +
                    "as list<Payments>"
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
    fun `list and union simple types import as string`() {
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
        assertEquals(string, thing.single { it.name == "tags" }.type)
        assertEquals(string, thing.single { it.name == "either" }.type)
        assertEquals(
            listOf(
                "SCH2405 element 'tags': list simple type imported as string",
                "SCH2405 element 'either': union simple type imported as string",
            ),
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
                    "equivalent; base fields flattened into the record",
                "SCH2403 complex type 'ValueType': simpleContent extension of 'int' has no " +
                    "Schemata equivalent; imported as a record with a 'value' field",
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
    fun `all and nested sequences flatten`() {
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
        assertEquals(listOf("id", "x", "y"), record(imported, "Order").fields.map { it.name })
        assertEquals(
            listOf(
                "SCH2403 complex type 'ThingType': xs:all imported as a sequence",
                "SCH2403 complex type 'OrderType': nested sequence flattened into the record",
            ),
            messages(imported),
        )
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
        assertEquals(emptyList(), record(imported, "Extensions").fields)
        assertEquals(
            listOf(
                "SCH2405 schema: xs:redefine dropped",
                "SCH2405 schema: xs:override dropped",
                "SCH2405 schema: xs:notation dropped",
                "SCH2405 element 'special_offer': substitution group 'head' dropped; imported as " +
                    "an independent element",
                "SCH2405 element 'counts': identity constraint 'k' dropped",
                "SCH2405 element 'counts': identity constraint 'k2' dropped",
                "SCH2405 complex type 'ThingType': mixed content dropped; elements kept",
                "SCH2405 complex type 'ThingType': abstract dropped",
                "SCH2405 complex type 'ThingType': xs:anyAttribute dropped",
                "SCH2405 element 'extensions': xs:any dropped",
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
        assertEquals(emptyList(), imported.diagnostics)
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
    fun `a repeated group reference expands once and is reported`() {
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
        assertEquals(listOf("id", "x", "y"), record(imported, "Thing").fields.map { it.name })
        assertEquals(
            listOf(
                "SCH2403 complex type 'ThingType': repeated group 'g' has no Schemata equivalent; " +
                    "expanded once"
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
}
