package io.schemata.target.xsd

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
import io.schemata.core.ir.Value
import io.schemata.lang.Span
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals

class XsdLoweringTest {
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
    ) =
        EnumType(
            qn(ns, name),
            name,
            values.mapIndexed { i, v ->
                EnumValue(i + 1, v, null, at(line + 1 + i), at(line + 1 + i))
            },
            Reserved(emptyList(), emptySet()),
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
    fun `two namespaces sharing a uri collide on the second`() {
        val a = namespace("a", xsd("namespace" to AnnotationValue.Str("urn:x")), line = 1)
        val b = namespace("b", xsd("namespace" to AnnotationValue.Str("urn:x")), line = 5)
        val d = XsdLowering.lower(Schema(listOf(a, b))).diagnostics.single()
        assertEquals(XsdCodes.NAMESPACE_COLLISION, d.code)
        assertEquals("namespaces a and b both lower to target namespace 'urn:x'", d.message)
        assertEquals(5, d.span.startLine)
        assertEquals("set `@xsd(namespace = \"…\")` on one of them", d.help)
    }

    @Test
    fun `a relative namespace override is rejected`() {
        val ns = namespace("shop.orders", xsd("namespace" to AnnotationValue.Str("orders")))
        val d = XsdLowering.lower(Schema(listOf(ns))).diagnostics.single()
        assertEquals(XsdCodes.INVALID_OVERRIDE, d.code)
        assertEquals(
            "namespace 'shop.orders': @xsd(namespace = \"orders\") is not an absolute URI",
            d.message,
        )
        assertEquals("use an absolute URI such as `urn:example:orders`", d.help)
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
            "record 'HttpStatus' lowers to element 'http_status', already used by record 'HTTPStatus' (orders.schemata:2)",
            d.message,
        )
        assertEquals("rename one of them, or set `@xsd(name = \"…\")` on one", d.help)
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
            "record 'Line' lowers to type 'OrderLineType', already used by record 'OrderLine' (orders.schemata:2)",
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
        assertEquals("field 'R.x': @xsd(name = \"1bad\") is not a valid XML name", d.message)
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
                    ListOf(Scalar(Builtin.STRING), nullableElement = false),
                    nullable = true,
                    line = 3,
                ),
            )
        val lowered = XsdLowering.lower(Schema(listOf(namespace("s", declarations = listOf(r)))))
        val d = lowered.diagnostics.single()
        assertEquals(XsdCodes.LOSSY, d.code)
        assertEquals(
            "field 'R.tags': a nullable list has no XSD representation; lowered to an optional repeated element",
            d.message,
        )
        assertEquals("declare the list as `list<T>`; an absent list already means empty", d.help)
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
}
