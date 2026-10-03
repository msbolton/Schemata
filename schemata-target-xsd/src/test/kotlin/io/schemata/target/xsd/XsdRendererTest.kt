package io.schemata.target.xsd

import io.schemata.testkit.Golden
import io.schemata.testkit.Xsd
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class XsdRendererTest {
    private val kitchen =
        XsdFile(
            path = "shop/orders.xsd",
            targetNamespace = "urn:schemata:shop.orders",
            imports = listOf(XsdImport("urn:schemata:shop.customers", "customers.xsd", "ns1")),
            types =
                listOf(
                    XsdEnumeration(
                        "StatusType",
                        "Order lifecycle.",
                        listOf(XsdEnumValue("pending", "Not paid yet."), XsdEnumValue("paid", null)),
                    ),
                    XsdComplex(
                        "OrderType",
                        "A customer's order.",
                        sequence =
                            listOf(
                                XsdElement(
                                    "id",
                                    XsdTypeRef.Restricted(
                                        "xs:string",
                                        listOf(XsdFacet("pattern", XsdTypes.UUID_PATTERN)),
                                    ),
                                ),
                                XsdElement(
                                    "customer",
                                    XsdTypeRef.Named("ns1", "CustomerType", simple = false),
                                ),
                                XsdElement(
                                    "status",
                                    XsdTypeRef.Named("tns", "StatusType", simple = true),
                                    minOccurs = 0,
                                    default = "pending",
                                ),
                                XsdElement(
                                    "note",
                                    XsdTypeRef.Restricted(
                                        "xs:string",
                                        listOf(XsdFacet("maxLength", "500")),
                                    ),
                                    minOccurs = 0,
                                    doc = "Free text & remarks.",
                                ),
                                XsdElement(
                                    "tags",
                                    XsdTypeRef.Builtin("xs:string"),
                                    minOccurs = 0,
                                    maxOccurs = null,
                                    nillable = true,
                                ),
                                XsdElement(
                                    "prices",
                                    XsdTypeRef.Anonymous(
                                        listOf(
                                            XsdElement(
                                                "entry",
                                                XsdTypeRef.Extension(
                                                    XsdTypeRef.Builtin("xs:decimal"),
                                                    listOf(
                                                        XsdAttribute(
                                                            "key",
                                                            XsdTypeRef.Builtin("xs:string"),
                                                            required = true,
                                                        )
                                                    ),
                                                ),
                                                minOccurs = 0,
                                                maxOccurs = null,
                                            )
                                        )
                                    ),
                                    minOccurs = 0,
                                    unique = "OrderType_prices_key",
                                ),
                                XsdElement(
                                    "payment",
                                    XsdTypeRef.Named("tns", "PaymentType", simple = false),
                                    minOccurs = 0,
                                ),
                            ),
                        attributes =
                            listOf(
                                XsdAttribute(
                                    "version",
                                    XsdTypeRef.Builtin("xs:int"),
                                    required = false,
                                    default = "1",
                                    doc = "Schema revision.",
                                )
                            ),
                    ),
                    XsdChoice(
                        "PaymentType",
                        null,
                        listOf(
                            XsdElement("card", XsdTypeRef.Named("tns", "CardType", simple = false)),
                            XsdElement("cash", XsdTypeRef.Builtin("xs:boolean")),
                        ),
                    ),
                    XsdComplex(
                        "CardType",
                        null,
                        listOf(XsdElement("last4", XsdTypeRef.Builtin("xs:string"))),
                    ),
                ),
            elements =
                listOf(XsdElement("order", XsdTypeRef.Named("tns", "OrderType", simple = false))),
        )

    private val customers =
        XsdFile(
            "shop/customers.xsd",
            "urn:schemata:shop.customers",
            emptyList(),
            listOf(
                XsdComplex(
                    "CustomerType",
                    null,
                    listOf(XsdElement("name", XsdTypeRef.Builtin("xs:string"))),
                )
            ),
            emptyList(),
        )

    @Test
    fun `renders the kitchen sink golden`() {
        Golden.assertMatches(
            "kitchen.xsd",
            XsdRenderer.render(XsdModel(listOf(kitchen))).single().content,
        )
    }

    @Test
    fun `the kitchen sink compiles under the jdk processor`() {
        val files =
            XsdRenderer.render(XsdModel(listOf(kitchen, customers))).associate {
                it.path to it.content
            }
        assertNull(Xsd.validate(files))
    }

    @Test
    fun `tab, newline, and carriage return in an attribute are character references`() {
        assertEquals(
            "a&#9;b&#10;c&#13;d &amp;&quot;",
            XsdRenderer.escapeAttribute("a\tb\nc\rd &\""),
        )
    }

    @Test
    fun `documentation keeps its line breaks`() {
        assertEquals("a\nb &lt;", XsdRenderer.escape("a\nb <"))
    }

    @Test
    fun `an extension of a restricted base is refused`() {
        val file =
            XsdFile(
                "s.xsd",
                "urn:schemata:s",
                emptyList(),
                listOf(
                    XsdComplex(
                        "RType",
                        null,
                        listOf(
                            XsdElement(
                                "x",
                                XsdTypeRef.Extension(
                                    XsdTypeRef.Restricted(
                                        "xs:string",
                                        listOf(XsdFacet("maxLength", "2")),
                                    ),
                                    emptyList(),
                                ),
                            )
                        ),
                    )
                ),
                emptyList(),
            )
        assertFailsWith<IllegalStateException> { XsdRenderer.text(file) }
    }
}
