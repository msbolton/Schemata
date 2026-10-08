package io.schemata.target.xsd

import io.schemata.core.AnalysisOptions
import io.schemata.core.Analyzer
import io.schemata.core.annotations.AnnotationRegistry
import io.schemata.core.annotations.CoreAnnotations
import io.schemata.core.ir.AnnotationValue
import io.schemata.core.ir.Annotations
import io.schemata.core.ir.BoolValue
import io.schemata.core.ir.Builtin
import io.schemata.core.ir.EnumRef
import io.schemata.core.ir.EnumType
import io.schemata.core.ir.EnumValue
import io.schemata.core.ir.Field
import io.schemata.core.ir.ListOf
import io.schemata.core.ir.MapOf
import io.schemata.core.ir.Namespace
import io.schemata.core.ir.QualifiedName
import io.schemata.core.ir.RecordType
import io.schemata.core.ir.Ref
import io.schemata.core.ir.Refinements
import io.schemata.core.ir.Reserved
import io.schemata.core.ir.Scalar
import io.schemata.core.ir.Schema
import io.schemata.core.ir.Type
import io.schemata.core.ir.TypeDecl
import io.schemata.core.ir.UnionMember
import io.schemata.core.ir.UnionType
import io.schemata.core.ir.Value
import io.schemata.lang.Parser
import io.schemata.lang.Span
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val RELATIONS =
    """
schema shop

model Customer { #1 id uuid { id }  #2 name string }

model Tag { #1 code string { id, max 16 } }

model Pair { #1 a int32 { id }  #2 b int32 { id } }

model Order {
  #1 id       uuid     { id }
  #2 customer Customer
  #3 billing  Customer { embed }
  #4 tags     Tag[]
  #5 pair     Pair
  #6 pairs    Pair[]
  #7 backup   Customer?
}
"""

/** Analyses [sources], one file each, with every annotation the XSD lowering reads. */
private fun compile(vararg sources: String): Schema {
    val files = sources.mapIndexed { i, text -> Parser.parse(text, "f$i.schemata").file!! }
    val analysis =
        Analyzer.analyze(
            files,
            AnalysisOptions(
                annotations = AnnotationRegistry(CoreAnnotations.specs + XsdAnnotations.specs)
            ),
        )
    assertEquals(emptyList(), analysis.diagnostics.map { "${it.code.id} ${it.message}" })
    return analysis.schema!!
}

private const val MEMBERS =
    """
schema shop

model Customer { #1 id uuid { id } }

model Pair { #1 a int32 { id }  #2 b int32 { id } }

union Party = Customer | Pair

model Book {
  #1 id    uuid { id }
  #2 by    map<string, Customer>
  #3 pairs map<string, Pair>
  #4 party Party
}
"""

class XsdLoweringTest {
    /** The sequences these tests read hold only elements; each read goes through the element. */
    private val XsdParticle.element: XsdElement
        get() = this as XsdElement

    private val XsdParticle.name: String
        get() = element.name

    private val XsdParticle.type: XsdTypeRef
        get() = element.type

    private val XsdParticle.minOccurs: Int
        get() = element.minOccurs

    private val XsdParticle.default: String?
        get() = element.default

    private val XsdParticle.unique: String?
        get() = element.unique

    private fun at(line: Int) = Span("orders.schemata", line, 3, line, 20)

    private fun xsd(vararg pairs: Pair<String, AnnotationValue>) =
        Annotations(mapOf("xsd" to pairs.toMap()))

    private fun namespace(
        name: String,
        annotations: Annotations = Annotations.NONE,
        line: Int = 1,
        declarations: List<TypeDecl> = emptyList(),
    ) = Namespace(name, declarations, at(line), annotations)

    private fun qn(ns: String, vararg path: String) = QualifiedName(ns, path.toList())

    private fun field(
        ordinal: Int,
        name: String,
        type: Type,
        nullable: Boolean = false,
        default: Value? = null,
        line: Int = 10 + ordinal,
        doc: String? = null,
        annotations: Annotations = Annotations.NONE,
    ) = Field(ordinal, name, type, nullable, default, null, doc, at(line), at(line), annotations)

    private fun record(
        ns: String,
        name: String,
        vararg fields: Field,
        path: List<String> = listOf(name),
        nested: List<TypeDecl> = emptyList(),
        line: Int = 3,
        doc: String? = null,
        annotations: Annotations = Annotations.NONE,
    ) =
        RecordType(
            qn(ns, *path.toTypedArray()),
            name,
            fields.toList(),
            Reserved(emptyList(), emptySet()),
            false,
            nested,
            doc,
            at(line),
            at(line),
            annotations,
        )

    private fun enum(
        ns: String,
        name: String,
        vararg values: String,
        line: Int = 30,
        annotations: Annotations = Annotations.NONE,
        valueAnnotations: Map<String, Annotations> = emptyMap(),
    ) =
        EnumType(
            qn(ns, name),
            name,
            values.mapIndexed { i, v ->
                EnumValue(
                    i + 1,
                    v,
                    null,
                    at(line + 1 + i),
                    at(line + 1 + i),
                    valueAnnotations[v] ?: Annotations.NONE,
                )
            },
            Reserved(emptyList(), emptySet()),
            emptyList(),
            null,
            at(line),
            at(line),
            annotations,
        )

    private fun union(
        ns: String,
        name: String,
        vararg members: Type,
        line: Int = 40,
        annotations: Annotations = Annotations.NONE,
    ) =
        UnionType(
            qn(ns, name),
            name,
            members.mapIndexed { i, t -> UnionMember(i + 1, t, null, at(line + 1 + i)) },
            emptyList(),
            null,
            at(line),
            at(line),
            annotations,
        )

    @Test
    fun `each namespace becomes one file with a urn target namespace`() {
        val lowered =
            XsdLowering.lower(Schema(listOf(namespace("shop.orders"), namespace("shop.customers"))))
        assertEquals(emptyList(), lowered.diagnostics)
        assertEquals(
            listOf("shop/orders.xsd", "shop/customers.xsd"),
            lowered.model.files.map { it.path },
        )
        assertEquals("urn:schemata:shop.orders", lowered.model.files[0].targetNamespace)
    }

    @Test
    fun `an xsd namespace override replaces the uri`() {
        val ns =
            namespace(
                "shop.orders",
                xsd("namespace" to AnnotationValue.Str("http://example.com/orders")),
            )
        val lowered = XsdLowering.lower(Schema(listOf(ns)))
        assertEquals("http://example.com/orders", lowered.model.files.single().targetNamespace)
    }

    @Test
    fun `an invalid namespace uri is reported and the default is used`() {
        val ns = namespace("s", xsd("namespace" to AnnotationValue.Str("urn:a b")))
        val lowered = XsdLowering.lower(Schema(listOf(ns)))
        assertEquals(listOf("SCH2205"), lowered.diagnostics.map { it.code.id })
        assertEquals("urn:schemata:s", lowered.model.files.single().targetNamespace)
    }

    @Test
    fun `two namespaces sharing a uri collide on the second`() {
        val a = namespace("a", xsd("namespace" to AnnotationValue.Str("urn:x")), line = 1)
        val b = namespace("b", xsd("namespace" to AnnotationValue.Str("urn:x")), line = 5)
        val d = XsdLowering.lower(Schema(listOf(a, b))).diagnostics.single()
        assertEquals(XsdCodes.NAMESPACE_COLLISION, d.code)
        assertEquals("schemas a and b both lower to target namespace 'urn:x'", d.message)
        assertEquals(5, d.span.startLine)
        assertEquals("set `@xsd(namespace: \"…\")` on one of them", d.help)
    }

    @Test
    fun `a relative namespace override is rejected`() {
        val ns = namespace("shop.orders", xsd("namespace" to AnnotationValue.Str("orders")))
        val d = XsdLowering.lower(Schema(listOf(ns))).diagnostics.single()
        assertEquals(XsdCodes.INVALID_OVERRIDE, d.code)
        assertEquals(
            "schema 'shop.orders': @xsd(namespace: \"orders\") is not an absolute URI",
            d.message,
        )
        assertEquals("use an absolute URI, such as `urn:example:orders`", d.help)
    }

    @Test
    fun `a record with scalar fields becomes a complex type with a sequence`() {
        val r =
            record(
                "s",
                "Contact",
                field(1, "name", Scalar(Builtin.STRING, Refinements(max = BigDecimal(100)))),
                field(2, "age", Scalar(Builtin.INT32), nullable = true),
                field(
                    3,
                    "active",
                    Scalar(Builtin.BOOL),
                    default = BoolValue(true),
                    doc = "Whether the contact is live.",
                ),
            )
        val file =
            XsdLowering.lower(Schema(listOf(namespace("s", declarations = listOf(r)))))
                .model
                .files
                .single()
        val type = file.types.single() as XsdComplex
        assertEquals("ContactType", type.name)
        assertEquals(
            listOf(
                XsdElement(
                    "name",
                    XsdTypeRef.Restricted("xs:string", listOf(XsdFacet("maxLength", "100"))),
                ),
                XsdElement("age", XsdTypeRef.Builtin("xs:int"), minOccurs = 0),
                XsdElement(
                    "active",
                    XsdTypeRef.Builtin("xs:boolean"),
                    minOccurs = 0,
                    default = "true",
                    doc = "Whether the contact is live.",
                ),
            ),
            type.sequence,
        )
    }

    @Test
    fun `an enum becomes an enumeration and an enum field references it`() {
        val e = enum("s", "Kind", "personal", "work")
        val r =
            record(
                "s",
                "Contact",
                field(
                    1,
                    "kind",
                    Ref(qn("s", "Kind")),
                    default = EnumRef(qn("s", "Kind"), "personal"),
                ),
            )
        val file =
            XsdLowering.lower(Schema(listOf(namespace("s", declarations = listOf(e, r)))))
                .model
                .files
                .single()
        assertEquals(
            XsdEnumeration(
                "KindType",
                null,
                listOf(XsdEnumValue("personal", null), XsdEnumValue("work", null)),
            ),
            file.types[0],
        )
        val kind = (file.types[1] as XsdComplex).sequence.single()
        assertEquals(
            XsdElement(
                "kind",
                XsdTypeRef.Named("tns", "KindType", simple = true),
                minOccurs = 0,
                default = "personal",
            ),
            kind,
        )
    }

    @Test
    fun `two enum values lowering to one enumeration value are reported`() {
        val e =
            enum(
                "s",
                "Status",
                "paid",
                "settled",
                valueAnnotations = mapOf("settled" to xsd("name" to AnnotationValue.Str("paid"))),
            )
        val ns = namespace("s", declarations = listOf(e))
        assertEquals(
            listOf(
                "SCH2202 enum value 'Status.settled' lowers to enumeration value 'paid', already used by enum value 'Status.paid' (orders.schemata:31)"
            ),
            XsdLowering.lower(Schema(listOf(ns))).diagnostics.map { "${it.code.id} ${it.message}" },
        )
    }

    @Test
    fun `a pattern xsd cannot express is dropped with a lossy warning`() {
        val r =
            record(
                "s",
                "R",
                field(1, "x", Scalar(Builtin.STRING, Refinements(pattern = "^(?=a).*$")), line = 4),
            )
        val lowered = XsdLowering.lower(Schema(listOf(namespace("s", declarations = listOf(r)))))
        val d = lowered.diagnostics.single()
        assertEquals(XsdCodes.LOSSY, d.code)
        assertEquals(
            "field 'R.x': pattern uses (?, which XSD 1.0 cannot express; dropped",
            d.message,
        )
        assertEquals("rewrite the pattern without (?, or enforce it in application code", d.help)
        assertEquals(
            XsdTypeRef.Builtin("xs:string"),
            (lowered.model.files.single().types.single() as XsdComplex).sequence.single().type,
        )
    }

    @Test
    fun `top-level records get a global element named in lower snake`() {
        val r = record("s", "HTTPStatus", field(1, "code", Scalar(Builtin.INT32)))
        val file =
            XsdLowering.lower(Schema(listOf(namespace("s", declarations = listOf(r)))))
                .model
                .files
                .single()
        assertEquals(
            listOf(
                XsdElement("http_status", XsdTypeRef.Named("tns", "HTTPStatusType", simple = false))
            ),
            file.elements,
        )
    }

    @Test
    fun `an xsd name override renames the element and the type of a record`() {
        val r =
            record(
                "s",
                "Order",
                field(1, "id", Scalar(Builtin.INT64)),
                annotations = xsd("name" to AnnotationValue.Str("purchase")),
            )
        val file =
            XsdLowering.lower(Schema(listOf(namespace("s", declarations = listOf(r)))))
                .model
                .files
                .single()
        assertEquals("purchaseType", file.types.single().name)
        assertEquals("purchase", file.elements.single().name)
    }

    @Test
    fun `two records whose global elements collide report the second`() {
        val a = record("s", "HTTPStatus", field(1, "x", Scalar(Builtin.BOOL)), line = 2)
        val b = record("s", "HttpStatus", field(1, "x", Scalar(Builtin.BOOL)), line = 5)
        val d =
            XsdLowering.lower(Schema(listOf(namespace("s", declarations = listOf(a, b)))))
                .diagnostics
                .single()
        assertEquals(XsdCodes.NAME_COLLISION, d.code)
        assertEquals(
            "model 'HttpStatus' lowers to element 'http_status', already used by model 'HTTPStatus' (orders.schemata:2)",
            d.message,
        )
        assertEquals("rename one of them, or set `@xsd(name: \"…\")` on one", d.help)
    }

    @Test
    fun `a nested record whose type name collides with a top-level one reports it`() {
        val line =
            record(
                "s",
                "Line",
                field(1, "x", Scalar(Builtin.BOOL)),
                path = listOf("Order", "Line"),
                line = 8,
            )
        val order =
            record(
                "s",
                "Order",
                field(1, "x", Scalar(Builtin.BOOL)),
                nested = listOf(line),
                line = 6,
            )
        val orderLine = record("s", "OrderLine", field(1, "x", Scalar(Builtin.BOOL)), line = 2)
        val d =
            XsdLowering.lower(
                    Schema(listOf(namespace("s", declarations = listOf(orderLine, order))))
                )
                .diagnostics
                .single()
        assertEquals(
            "model 'Line' lowers to type 'OrderLineType', already used by model 'OrderLine' (orders.schemata:2)",
            d.message,
        )
    }

    @Test
    fun `an xsd name that is not an ncname is rejected`() {
        val r =
            record(
                "s",
                "R",
                field(
                    1,
                    "x",
                    Scalar(Builtin.BOOL),
                    annotations = xsd("name" to AnnotationValue.Str("1bad")),
                ),
            )
        val d =
            XsdLowering.lower(Schema(listOf(namespace("s", declarations = listOf(r)))))
                .diagnostics
                .single()
        assertEquals(XsdCodes.INVALID_OVERRIDE, d.code)
        assertEquals("field 'R.x': @xsd(name: \"1bad\") is not a valid XML name", d.message)
        assertEquals(
            "use letters, digits, underscores, hyphens, and dots, starting with a letter or underscore",
            d.help,
        )
    }

    @Test
    fun `an embedded record field is an element of the record type`() {
        val addr = record("s", "Address", field(1, "city", Scalar(Builtin.STRING)))
        val r = record("s", "Order", field(1, "shipping", Ref(qn("s", "Address")), nullable = true))
        val file =
            XsdLowering.lower(Schema(listOf(namespace("s", declarations = listOf(addr, r)))))
                .model
                .files
                .single()
        val e = (file.types[1] as XsdComplex).sequence.single()
        assertEquals(
            XsdElement(
                "shipping",
                XsdTypeRef.Named("tns", "AddressType", simple = false),
                minOccurs = 0,
            ),
            e,
        )
    }

    @Test
    fun `a list is a repeated element with occurrence bounds from its refinements`() {
        val r =
            record(
                "s",
                "R",
                field(
                    1,
                    "tags",
                    ListOf(
                        Scalar(Builtin.STRING),
                        nullableElement = false,
                        Refinements(min = BigDecimal(1), max = BigDecimal(5)),
                    ),
                ),
                field(2, "notes", ListOf(Scalar(Builtin.STRING), nullableElement = true)),
            )
        val seq =
            (XsdLowering.lower(Schema(listOf(namespace("s", declarations = listOf(r)))))
                    .model
                    .files
                    .single()
                    .types
                    .single() as XsdComplex)
                .sequence
        assertEquals(
            XsdElement("tags", XsdTypeRef.Builtin("xs:string"), minOccurs = 1, maxOccurs = 5),
            seq[0],
        )
        assertEquals(
            XsdElement(
                "notes",
                XsdTypeRef.Builtin("xs:string"),
                minOccurs = 0,
                maxOccurs = null,
                nillable = true,
            ),
            seq[1],
        )
    }

    @Test
    fun `a nullable list is lowered as optional and reported as lossy`() {
        val r =
            record(
                "s",
                "R",
                field(
                    1,
                    "tags",
                    ListOf(
                        Scalar(Builtin.STRING),
                        nullableElement = false,
                        Refinements(min = BigDecimal(2)),
                    ),
                    nullable = true,
                    line = 3,
                ),
            )
        val lowered = XsdLowering.lower(Schema(listOf(namespace("s", declarations = listOf(r)))))
        assertEquals(
            0,
            (lowered.model.files.single().types.single() as XsdComplex).sequence.single().minOccurs,
        )
        val d = lowered.diagnostics.single()
        assertEquals(XsdCodes.LOSSY, d.code)
        assertEquals(
            "field 'R.tags': a nullable list has no XSD representation; lowered to an optional repeated element",
            d.message,
        )
        assertEquals("declare the list as `T[]`; an absent list already means empty", d.help)
    }

    @Test
    fun `a map is a wrapper of entries keyed by an attribute with a uniqueness constraint`() {
        val r =
            record(
                "s",
                "Account",
                field(
                    1,
                    "balances",
                    MapOf(
                        Scalar(Builtin.STRING),
                        Scalar(Builtin.DECIMAL, Refinements(precision = 19, scale = 4)),
                        nullableValue = false,
                    ),
                ),
            )
        val e =
            (XsdLowering.lower(Schema(listOf(namespace("s", declarations = listOf(r)))))
                    .model
                    .files
                    .single()
                    .types
                    .single() as XsdComplex)
                .sequence
                .single()
        val entry =
            XsdElement(
                "entry",
                XsdTypeRef.Anonymous(
                    listOf(
                        XsdElement(
                            "value",
                            XsdTypeRef.Restricted(
                                "xs:decimal",
                                listOf(
                                    XsdFacet("totalDigits", "19"),
                                    XsdFacet("fractionDigits", "4"),
                                ),
                            ),
                        )
                    ),
                    listOf(XsdAttribute("key", XsdTypeRef.Builtin("xs:string"), required = true)),
                ),
                minOccurs = 0,
                maxOccurs = null,
            )
        assertEquals(
            XsdElement(
                "balances",
                XsdTypeRef.Anonymous(listOf(entry)),
                unique = "AccountType_balances_key",
            ),
            e,
        )
    }

    @Test
    fun `a map with a plain scalar value uses simple content`() {
        val r =
            record(
                "s",
                "R",
                field(
                    1,
                    "counts",
                    MapOf(Scalar(Builtin.STRING), Scalar(Builtin.INT32), nullableValue = false),
                ),
            )
        val e =
            (XsdLowering.lower(Schema(listOf(namespace("s", declarations = listOf(r)))))
                    .model
                    .files
                    .single()
                    .types
                    .single() as XsdComplex)
                .sequence
                .single()
        val entryType = ((e.type as XsdTypeRef.Anonymous).sequence.single().type)
        assertEquals(
            XsdTypeRef.Extension(
                XsdTypeRef.Builtin("xs:int"),
                listOf(XsdAttribute("key", XsdTypeRef.Builtin("xs:string"), required = true)),
            ),
            entryType,
        )
    }

    @Test
    fun `a nested list wraps inner items`() {
        val r =
            record("s", "R", field(1, "grid", ListOf(ListOf(Scalar(Builtin.INT32), false), false)))
        val e =
            (XsdLowering.lower(Schema(listOf(namespace("s", declarations = listOf(r)))))
                    .model
                    .files
                    .single()
                    .types
                    .single() as XsdComplex)
                .sequence
                .single()
        assertEquals(
            XsdElement(
                "grid",
                XsdTypeRef.Anonymous(
                    listOf(
                        XsdElement(
                            "item",
                            XsdTypeRef.Builtin("xs:int"),
                            minOccurs = 0,
                            maxOccurs = null,
                        )
                    )
                ),
                minOccurs = 0,
                maxOccurs = null,
            ),
            e,
        )
    }

    @Test
    fun `nested maps in different list fields get distinct uniqueness constraint names`() {
        val r =
            record(
                "s",
                "R",
                field(
                    1,
                    "a",
                    ListOf(
                        MapOf(Scalar(Builtin.STRING), Scalar(Builtin.INT32), nullableValue = false),
                        nullableElement = false,
                    ),
                ),
                field(
                    2,
                    "b",
                    ListOf(
                        MapOf(Scalar(Builtin.STRING), Scalar(Builtin.INT32), nullableValue = false),
                        nullableElement = false,
                    ),
                ),
            )
        val seq =
            (XsdLowering.lower(Schema(listOf(namespace("s", declarations = listOf(r)))))
                    .model
                    .files
                    .single()
                    .types
                    .single() as XsdComplex)
                .sequence
        val aMap = (seq[0].type as XsdTypeRef.Anonymous).sequence.single()
        val bMap = (seq[1].type as XsdTypeRef.Anonymous).sequence.single()
        assertEquals("RType_a_item_key", aMap.unique)
        assertEquals("RType_b_item_key", bMap.unique)
    }

    @Test
    fun `a map of maps names the outer and inner uniqueness constraints from the field`() {
        val r =
            record(
                "s",
                "R",
                field(
                    1,
                    "grid",
                    MapOf(
                        Scalar(Builtin.STRING),
                        MapOf(Scalar(Builtin.STRING), Scalar(Builtin.INT32), nullableValue = false),
                        nullableValue = false,
                    ),
                ),
            )
        val outer =
            (XsdLowering.lower(Schema(listOf(namespace("s", declarations = listOf(r)))))
                    .model
                    .files
                    .single()
                    .types
                    .single() as XsdComplex)
                .sequence
                .single()
        assertEquals("RType_grid_key", outer.unique)
        val entry = (outer.type as XsdTypeRef.Anonymous).sequence.single()
        val innerMap = (entry.type as XsdTypeRef.Anonymous).sequence.single()
        assertEquals("RType_grid_item_key", innerMap.unique)
    }

    @Test
    fun `same-named nested records under different parents do not collide`() {
        val orderLine =
            record(
                "s",
                "Line",
                field(1, "x", Scalar(Builtin.BOOL)),
                path = listOf("Order", "Line"),
                line = 4,
            )
        val order =
            record(
                "s",
                "Order",
                field(1, "x", Scalar(Builtin.BOOL)),
                nested = listOf(orderLine),
                line = 2,
            )
        val invoiceLine =
            record(
                "s",
                "Line",
                field(1, "x", Scalar(Builtin.BOOL)),
                path = listOf("Invoice", "Line"),
                line = 9,
            )
        val invoice =
            record(
                "s",
                "Invoice",
                field(1, "x", Scalar(Builtin.BOOL)),
                nested = listOf(invoiceLine),
                line = 7,
            )
        val lowered =
            XsdLowering.lower(Schema(listOf(namespace("s", declarations = listOf(order, invoice)))))
        assertEquals(emptyList(), lowered.diagnostics)
    }

    @Test
    fun `a union is a choice of elements named after its members`() {
        val card = record("s", "Card", field(1, "last4", Scalar(Builtin.STRING)))
        val kind = enum("s", "Kind", "a", "b")
        val u =
            union("s", "Payment", Ref(qn("s", "Card")), Scalar(Builtin.INT32), Ref(qn("s", "Kind")))
        val file =
            XsdLowering.lower(Schema(listOf(namespace("s", declarations = listOf(card, kind, u)))))
                .model
                .files
                .single()
        assertEquals(
            XsdChoice(
                "PaymentType",
                null,
                listOf(
                    XsdElement("card", XsdTypeRef.Named("tns", "CardType", simple = false)),
                    XsdElement("int32", XsdTypeRef.Builtin("xs:int")),
                    XsdElement("kind", XsdTypeRef.Named("tns", "KindType", simple = true)),
                ),
            ),
            file.types[2],
        )
    }

    @Test
    fun `a union member's doc comment is kept on its choice element`() {
        val card = record("s", "Card", field(1, "last4", Scalar(Builtin.STRING)))
        val u =
            UnionType(
                qn("s", "Payment"),
                "Payment",
                listOf(UnionMember(1, Ref(qn("s", "Card")), "Paid by card.", at(41))),
                emptyList(),
                null,
                at(40),
                at(40),
                Annotations.NONE,
            )
        val file =
            XsdLowering.lower(Schema(listOf(namespace("s", declarations = listOf(card, u)))))
                .model
                .files
                .single()
        assertEquals(
            XsdChoice(
                "PaymentType",
                null,
                listOf(
                    XsdElement(
                        "card",
                        XsdTypeRef.Named("tns", "CardType", simple = false),
                        doc = "Paid by card.",
                    )
                ),
            ),
            file.types[1],
        )
    }

    @Test
    fun `union members that lower to one element name collide`() {
        val a = record("s", "HTTPStatus", field(1, "x", Scalar(Builtin.BOOL)), line = 2)
        val b = record("s", "HttpStatus", field(1, "x", Scalar(Builtin.BOOL)), line = 3)
        val u = union("s", "U", Ref(qn("s", "HTTPStatus")), Ref(qn("s", "HttpStatus")), line = 6)
        val ds =
            XsdLowering.lower(Schema(listOf(namespace("s", declarations = listOf(a, b, u)))))
                .diagnostics
        val member = ds.single { "union member" in it.message }
        assertEquals(XsdCodes.NAME_COLLISION, member.code)
        assertEquals(
            "union member 'HttpStatus' lowers to element 'http_status', already used by union member 'HTTPStatus' (orders.schemata:7)",
            member.message,
        )
        assertEquals(8, member.span.startLine)
    }

    @Test
    fun `a reference into another namespace imports it with a relative location and a prefix`() {
        val customer =
            record("shop.customers", "Customer", field(1, "name", Scalar(Builtin.STRING)))
        val order =
            record(
                "shop.orders",
                "Order",
                field(1, "customer", Ref(qn("shop.customers", "Customer"))),
            )
        val model =
            XsdLowering.lower(
                    Schema(
                        listOf(
                            namespace("shop.customers", declarations = listOf(customer)),
                            namespace("shop.orders", declarations = listOf(order)),
                        )
                    )
                )
                .model
        val orders = model.files[1]
        assertEquals(
            listOf(XsdImport("urn:schemata:shop.customers", "customers.xsd", "ns1")),
            orders.imports,
        )
        assertEquals(
            XsdTypeRef.Named("ns1", "CustomerType", simple = false),
            (orders.types.single() as XsdComplex).sequence.single().type,
        )
    }

    @Test
    fun `an import from a deeper path climbs directories`() {
        val a = record("a", "A", field(1, "x", Scalar(Builtin.BOOL)))
        val b = record("x.y.b", "B", field(1, "a", Ref(qn("a", "A"))))
        val model =
            XsdLowering.lower(
                    Schema(
                        listOf(
                            namespace("a", declarations = listOf(a)),
                            namespace("x.y.b", declarations = listOf(b)),
                        )
                    )
                )
                .model
        assertEquals("../../a.xsd", model.files[1].imports.single().schemaLocation)
    }

    @Test
    fun `an attribute field becomes an xs attribute with use and default`() {
        val kind = enum("s", "Kind", "a", "b")
        val r =
            record(
                "s",
                "R",
                field(
                    1,
                    "id",
                    Scalar(Builtin.INT64),
                    annotations = xsd("attribute" to AnnotationValue.Flag),
                ),
                field(
                    2,
                    "note",
                    Scalar(Builtin.STRING, Refinements(max = BigDecimal(10))),
                    nullable = true,
                    annotations = xsd("attribute" to AnnotationValue.Flag),
                ),
                field(
                    3,
                    "kind",
                    Ref(qn("s", "Kind")),
                    default = EnumRef(qn("s", "Kind"), "a"),
                    annotations = xsd("attribute" to AnnotationValue.Flag),
                    doc = "Which kind.",
                ),
                field(4, "body", Scalar(Builtin.STRING)),
            )
        val type =
            XsdLowering.lower(Schema(listOf(namespace("s", declarations = listOf(kind, r)))))
                .model
                .files
                .single()
                .types[1]
                as XsdComplex
        assertEquals(listOf("body"), type.sequence.map { it.name })
        assertEquals(
            listOf(
                XsdAttribute("id", XsdTypeRef.Builtin("xs:long"), required = true),
                XsdAttribute(
                    "note",
                    XsdTypeRef.Restricted("xs:string", listOf(XsdFacet("maxLength", "10"))),
                    required = false,
                ),
                XsdAttribute(
                    "kind",
                    XsdTypeRef.Named("tns", "KindType", simple = true),
                    required = false,
                    default = "a",
                    doc = "Which kind.",
                ),
            ),
            type.attributes,
        )
    }

    @Test
    fun `a key record does not repeat the warnings of the model it copies`() {
        val schema =
            compile(
                "schema shop\n\nmodel Pair { #1 a int32 { id } @xsd(mixed)  #2 b int32 { id } }\n\n" +
                    "model User { #1 id uuid { id }  #2 pair Pair }\n"
            )
        val lowered = XsdLowering.lower(schema)
        assertEquals(
            listOf(
                "field 'Pair.a': @xsd(mixed) is not allowed on an int32; it takes a string or string?"
            ),
            lowered.diagnostics.map { it.message },
        )
    }

    @Test
    fun `an attribute on a list field is rejected and the field is skipped`() {
        val r =
            record(
                "s",
                "R",
                field(
                    1,
                    "tags",
                    ListOf(Scalar(Builtin.STRING), false),
                    annotations = xsd("attribute" to AnnotationValue.Flag),
                    line = 3,
                ),
            )
        val lowered = XsdLowering.lower(Schema(listOf(namespace("s", declarations = listOf(r)))))
        val d = lowered.diagnostics.single()
        assertEquals(XsdCodes.ATTRIBUTE_NOT_ALLOWED, d.code)
        assertEquals("field 'R.tags': @xsd(attribute) is not allowed on a list", d.message)
        assertEquals(
            "remove the annotation; only scalar and enum fields lower to attributes",
            d.help,
        )
    }

    @Test
    fun `root false suppresses the global element`() {
        val r =
            record(
                "s",
                "Address",
                field(1, "city", Scalar(Builtin.STRING)),
                annotations = xsd("root" to AnnotationValue.Bool(false)),
            )
        val file =
            XsdLowering.lower(Schema(listOf(namespace("s", declarations = listOf(r)))))
                .model
                .files
                .single()
        assertEquals(emptyList(), file.elements)
    }

    @Test
    fun `a field referencing an overridden record uses the overridden type name`() {
        val order =
            record(
                "s",
                "Order",
                field(1, "id", Scalar(Builtin.INT64)),
                annotations = xsd("name" to AnnotationValue.Str("Purchase")),
            )
        val r =
            record(
                "s",
                "Invoice",
                field(1, "order", Ref(qn("s", "Order"))),
                field(2, "orders", ListOf(Ref(qn("s", "Order")), nullableElement = false)),
                field(
                    3,
                    "byId",
                    MapOf(Scalar(Builtin.STRING), Ref(qn("s", "Order")), nullableValue = false),
                ),
            )
        val u = union("s", "Pay", Ref(qn("s", "Order")))
        val lowered =
            XsdLowering.lower(Schema(listOf(namespace("s", declarations = listOf(order, r, u)))))
        assertEquals(emptyList(), lowered.diagnostics)
        val file = lowered.model.files.single()
        val purchase = XsdTypeRef.Named("tns", "PurchaseType", simple = false)
        val seq = (file.types[1] as XsdComplex).sequence
        assertEquals(purchase, seq[0].type)
        assertEquals(purchase, seq[1].type)
        val entry = (seq[2].type as XsdTypeRef.Anonymous).sequence.single()
        assertEquals(purchase, (entry.type as XsdTypeRef.Extension).base)
        assertEquals(purchase, (file.types[2] as XsdChoice).members.single().type)
        assertEquals(XsdElement("Purchase", purchase), file.elements[0])
    }

    @Test
    fun `a cross-namespace reference to an overridden record imports the overridden type name`() {
        val customer =
            record(
                "shop.customers",
                "Customer",
                field(1, "name", Scalar(Builtin.STRING)),
                annotations = xsd("name" to AnnotationValue.Str("Client")),
            )
        val order =
            record(
                "shop.orders",
                "Order",
                field(1, "customer", Ref(qn("shop.customers", "Customer"))),
            )
        val model =
            XsdLowering.lower(
                    Schema(
                        listOf(
                            namespace("shop.customers", declarations = listOf(customer)),
                            namespace("shop.orders", declarations = listOf(order)),
                        )
                    )
                )
                .model
        assertEquals("ClientType", model.files[0].types.single().name)
        assertEquals(
            XsdTypeRef.Named("ns1", "ClientType", simple = false),
            (model.files[1].types.single() as XsdComplex).sequence.single().type,
        )
    }

    @Test
    fun `a nested type under an overridden parent is referenced by the overridden path`() {
        val line =
            record(
                "s",
                "Line",
                field(1, "sku", Scalar(Builtin.STRING)),
                path = listOf("Order", "Line"),
            )
        val order =
            record(
                "s",
                "Order",
                field(1, "id", Scalar(Builtin.INT64)),
                nested = listOf(line),
                annotations = xsd("name" to AnnotationValue.Str("Purchase")),
            )
        val r = record("s", "Invoice", field(1, "line", Ref(qn("s", "Order", "Line"))))
        val lowered =
            XsdLowering.lower(Schema(listOf(namespace("s", declarations = listOf(order, r)))))
        assertEquals(emptyList(), lowered.diagnostics)
        val file = lowered.model.files.single()
        assertEquals(
            listOf("PurchaseType", "PurchaseLineType", "InvoiceType"),
            file.types.map { it.name },
        )
        assertEquals(
            XsdTypeRef.Named("tns", "PurchaseLineType", simple = false),
            (file.types[2] as XsdComplex).sequence.single().type,
        )
    }

    @Test
    fun `an invalid record override referenced from two files is reported once`() {
        val order =
            record(
                "a",
                "Order",
                field(1, "id", Scalar(Builtin.INT64)),
                annotations = xsd("name" to AnnotationValue.Str("1bad")),
            )
        val b = union("b", "B", Ref(qn("a", "Order")))
        val c = union("c", "C", Ref(qn("a", "Order")))
        val lowered =
            XsdLowering.lower(
                Schema(
                    listOf(
                        namespace("a", declarations = listOf(order)),
                        namespace("b", declarations = listOf(b)),
                        namespace("c", declarations = listOf(c)),
                    )
                )
            )
        val d = lowered.diagnostics.single()
        assertEquals(XsdCodes.INVALID_OVERRIDE, d.code)
        assertEquals("model 'Order': @xsd(name: \"1bad\") is not a valid XML name", d.message)
        assertEquals(
            XsdTypeRef.Named("ns1", "OrderType", simple = false),
            (lowered.model.files[1].types.single() as XsdChoice).members.single().type,
        )
    }

    @Test
    fun `an enum default honours the overridden value name on elements and attributes`() {
        val kind =
            enum(
                "s",
                "Kind",
                "personal",
                "work",
                valueAnnotations =
                    mapOf("personal" to xsd("name" to AnnotationValue.Str("Personal"))),
            )
        val r =
            record(
                "s",
                "Contact",
                field(
                    1,
                    "kind",
                    Ref(qn("s", "Kind")),
                    default = EnumRef(qn("s", "Kind"), "personal"),
                ),
                field(
                    2,
                    "other",
                    Ref(qn("s", "Kind")),
                    default = EnumRef(qn("s", "Kind"), "personal"),
                    annotations = xsd("attribute" to AnnotationValue.Flag),
                ),
            )
        val lowered =
            XsdLowering.lower(Schema(listOf(namespace("s", declarations = listOf(kind, r)))))
        assertEquals(emptyList(), lowered.diagnostics)
        val file = lowered.model.files.single()
        assertEquals(
            listOf("Personal", "work"),
            (file.types[0] as XsdEnumeration).values.map { it.value },
        )
        val contact = file.types[1] as XsdComplex
        assertEquals("Personal", contact.sequence.single().default)
        assertEquals("Personal", contact.attributes.single().default)
    }

    @Test
    fun `two scalar members of one builtin in a union collide`() {
        val u =
            union(
                "s",
                "U",
                Scalar(Builtin.INT32),
                Scalar(Builtin.INT32, Refinements(min = BigDecimal.ZERO)),
            )
        val d =
            XsdLowering.lower(Schema(listOf(namespace("s", declarations = listOf(u)))))
                .diagnostics
                .single()
        assertEquals(XsdCodes.NAME_COLLISION, d.code)
        assertEquals(
            "union member 'int32' lowers to element 'int32', already used by union member 'int32' (orders.schemata:41)",
            d.message,
        )
        assertEquals(42, d.span.startLine)
    }

    @Test
    fun `an attribute named like an element in the same record collides as an attribute`() {
        val r =
            record(
                "s",
                "R",
                field(1, "x", Scalar(Builtin.STRING)),
                field(
                    2,
                    "y",
                    Scalar(Builtin.STRING),
                    annotations =
                        xsd("attribute" to AnnotationValue.Flag, "name" to AnnotationValue.Str("x")),
                ),
            )
        val d =
            XsdLowering.lower(Schema(listOf(namespace("s", declarations = listOf(r)))))
                .diagnostics
                .single()
        assertEquals(XsdCodes.NAME_COLLISION, d.code)
        assertEquals(
            "field 'R.y' lowers to attribute 'x', already used by field 'R.x' (orders.schemata:11)",
            d.message,
        )
        assertEquals(12, d.span.startLine)
    }

    @Test
    fun `two maps lowering to one uniqueness constraint name collide`() {
        val inner = MapOf(Scalar(Builtin.STRING), Scalar(Builtin.INT32), nullableValue = false)
        val r =
            record(
                "s",
                "R",
                field(1, "x", ListOf(inner, nullableElement = false)),
                field(2, "x_item", inner),
            )
        val d =
            XsdLowering.lower(Schema(listOf(namespace("s", declarations = listOf(r)))))
                .diagnostics
                .single()
        assertEquals(XsdCodes.NAME_COLLISION, d.code)
        assertEquals(
            "field 'R.x_item' lowers to uniqueness constraint 'RType_x_item_key', already used by field 'R.x' (orders.schemata:11)",
            d.message,
        )
        assertEquals(12, d.span.startLine)
    }

    @Test
    fun `a map whose record value has a key attribute is reported and keeps one key`() {
        val v =
            record(
                "s",
                "V",
                field(
                    1,
                    "key",
                    Scalar(Builtin.STRING),
                    annotations = xsd("attribute" to AnnotationValue.Flag),
                    line = 4,
                ),
                annotations = xsd("root" to AnnotationValue.Bool(false)),
            )
        val r =
            record(
                "s",
                "R",
                field(
                    1,
                    "m",
                    MapOf(Scalar(Builtin.STRING), Ref(qn("s", "V")), nullableValue = false),
                    line = 8,
                ),
            )
        val lowered = XsdLowering.lower(Schema(listOf(namespace("s", declarations = listOf(v, r)))))
        val d = lowered.diagnostics.single()
        assertEquals(XsdCodes.NAME_COLLISION, d.code)
        assertEquals(
            "field 'R.m': map entries lower to attribute 'key', already used by field 'V.key' (orders.schemata:4)",
            d.message,
        )
        assertEquals(8, d.span.startLine)
        val m = (lowered.model.files.single().types[1] as XsdComplex).sequence.single()
        assertEquals(
            XsdTypeRef.Named("tns", "VType", simple = false),
            (m.type as XsdTypeRef.Anonymous).sequence.single().type,
        )
    }

    private val relations by lazy { XsdLowering.lower(compile(RELATIONS)).model.files.single() }

    private fun complex(name: String) = relations.types.single { it.name == name } as XsdComplex

    private fun element(type: String, name: String) =
        complex(type).sequence.map { it as XsdElement }.single { it.name == name }

    @Test
    fun `a reference to a keyed model emits its key`() {
        val id = element("CustomerType", "id")
        val code = element("TagType", "code")
        assertEquals(
            listOf("id", "customer_id", "billing", "tags", "pairs", "pair", "backup_id").sorted(),
            complex("OrderType").sequence.map { (it as XsdElement).name }.sorted(),
        )
        assertEquals(id.copy(name = "customer_id"), element("OrderType", "customer_id"))
        assertEquals(code.type, element("OrderType", "tags").type)
        assertEquals(null, element("OrderType", "tags").maxOccurs)
        assertEquals(
            id.copy(name = "backup_id", minOccurs = 0),
            element("OrderType", "backup_id").copy(nillable = false),
        )
    }

    @Test
    fun `embed restores the record`() {
        assertEquals(
            XsdTypeRef.Named("tns", "CustomerType", simple = false),
            element("OrderType", "billing").type,
        )
    }

    @Test
    fun `a composite-key reference emits one key object`() {
        assertEquals(
            listOf("CustomerType", "TagType", "PairType", "PairKeyType", "OrderType"),
            relations.types.map { it.name },
        )
        assertEquals(complex("PairType").sequence, complex("PairKeyType").sequence)
        val key = XsdTypeRef.Named("tns", "PairKeyType", simple = false)
        assertEquals(key, element("OrderType", "pair").type)
        assertEquals(key, element("OrderType", "pairs").type)
        assertEquals(listOf("customer", "tag", "pair", "order"), relations.elements.map { it.name })
    }

    private val members by lazy { XsdLowering.lower(compile(MEMBERS)).model.files.single() }

    private fun memberType(name: String) = members.types.single { it.name == name }

    @Test
    fun `a union member typed as a keyed model carries its key`() {
        val id = (memberType("CustomerType") as XsdComplex).sequence.single() as XsdElement
        val party = memberType("PartyType") as XsdChoice
        assertEquals(
            listOf(
                id.copy(name = "customer"),
                XsdElement("pair", XsdTypeRef.Named("tns", "PairKeyType", false)),
            ),
            party.members.map { it.copy(minOccurs = 1, maxOccurs = 1) },
        )
    }

    @Test
    fun `a map value typed as a keyed model carries its key`() {
        val id = (memberType("CustomerType") as XsdComplex).sequence.single() as XsdElement
        val book = (memberType("BookType") as XsdComplex).sequence.map { it as XsdElement }
        val by = book.single { it.name == "by" }.type.toString()
        val pairs = book.single { it.name == "pairs" }.type.toString()
        assertTrue(by.contains(id.type.toString()), by)
        assertFalse(by.contains("CustomerType"), by)
        assertTrue(pairs.contains("PairKeyType"), pairs)
    }
}
