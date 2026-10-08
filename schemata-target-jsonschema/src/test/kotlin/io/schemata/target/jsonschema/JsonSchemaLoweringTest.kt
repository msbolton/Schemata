package io.schemata.target.jsonschema

import io.schemata.core.AnalysisOptions
import io.schemata.core.Analyzer
import io.schemata.core.annotations.AnnotationRegistry
import io.schemata.core.annotations.CoreAnnotations
import io.schemata.core.ir.AnnotationValue
import io.schemata.core.ir.Annotations
import io.schemata.core.ir.Builtin
import io.schemata.core.ir.EnumRef
import io.schemata.core.ir.EnumType
import io.schemata.core.ir.EnumValue
import io.schemata.core.ir.Field
import io.schemata.core.ir.IntValue
import io.schemata.core.ir.ListOf
import io.schemata.core.ir.MapOf
import io.schemata.core.ir.Namespace
import io.schemata.core.ir.QualifiedName
import io.schemata.core.ir.RealValue
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
import io.schemata.target.json.JsonNumber
import io.schemata.target.json.JsonString
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals

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

/** Analyses [sources], one file each, with every annotation the JSON Schema lowering reads. */
private fun compile(vararg sources: String): Schema {
    val files = sources.mapIndexed { i, text -> Parser.parse(text, "f$i.schemata").file!! }
    val analysis =
        Analyzer.analyze(
            files,
            AnalysisOptions(
                annotations =
                    AnnotationRegistry(CoreAnnotations.specs + JsonSchemaAnnotations.specs)
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

class JsonSchemaLoweringTest {
    private fun at(line: Int) = Span("orders.schemata", line, 3, line, 20)

    private fun js(vararg pairs: Pair<String, AnnotationValue>) =
        Annotations(mapOf("jsonschema" to pairs.toMap()))

    private fun core(vararg pairs: Pair<String, AnnotationValue>) =
        Annotations(mapOf("" to pairs.toMap()))

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
            Reserved.NONE,
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
        valueDocs: Map<String, String> = emptyMap(),
        valueAnnotations: Map<String, Annotations> = emptyMap(),
    ) =
        EnumType(
            qn(ns, name),
            name,
            values.mapIndexed { i, v ->
                EnumValue(
                    i + 1,
                    v,
                    valueDocs[v],
                    at(line + 1 + i),
                    at(line + 1 + i),
                    valueAnnotations[v] ?: Annotations.NONE,
                )
            },
            Reserved.NONE,
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
        memberDocs: List<String?> = emptyList(),
        annotations: Annotations = Annotations.NONE,
    ) =
        UnionType(
            qn(ns, name),
            name,
            members.mapIndexed { i, t ->
                UnionMember(i + 1, t, memberDocs.getOrNull(i), at(line + 1 + i))
            },
            emptyList(),
            null,
            at(line),
            at(line),
            annotations,
        )

    private fun lower(vararg namespaces: Namespace) =
        JsonSchemaLowering.lower(Schema(namespaces.toList()))

    private fun document(vararg namespaces: Namespace) = lower(*namespaces).model.documents.single()

    private fun def(key: String, vararg namespaces: Namespace) =
        document(*namespaces).defs.single { it.key == key }.schema

    private fun messages(vararg namespaces: Namespace) =
        lower(*namespaces).diagnostics.map { "${it.code.id} ${it.message}" }

    @Test
    fun `one document per namespace with path id and title`() {
        val doc = document(namespace("shop.orders"))
        assertEquals("shop/orders.schema.json", doc.path)
        assertEquals("urn:schemata:shop.orders", doc.id)
        assertEquals("shop.orders", doc.title)
        assertEquals(emptyList(), doc.defs)
    }

    @Test
    fun `documents come in namespace order`() {
        val model = lower(namespace("b"), namespace("a")).model
        assertEquals(listOf("b.schema.json", "a.schema.json"), model.documents.map { it.path })
    }

    @Test
    fun `an id override that is not an absolute uri is reported and the urn is kept`() {
        val ns = namespace("s", js("id" to AnnotationValue.Str("orders")))
        assertEquals(
            listOf("SCH2303 schema 's': @jsonschema(id: \"orders\") is not an absolute URI"),
            messages(ns),
        )
        assertEquals("urn:schemata:s", document(ns).id)
    }

    @Test
    fun `two namespaces with one id are reported`() {
        val a = namespace("a", js("id" to AnnotationValue.Str("urn:x")))
        val b = namespace("b", js("id" to AnnotationValue.Str("urn:x")), line = 9)
        assertEquals(listOf("SCH2304 schemas a and b both lower to \$id 'urn:x'"), messages(a, b))
    }

    @Test
    fun `a record is a closed object with required non-nullable undefaulted fields`() {
        val r =
            record(
                "s",
                "Order",
                field(1, "id", Scalar(Builtin.UUID)),
                field(
                    2,
                    "note",
                    Scalar(Builtin.STRING, Refinements(max = BigDecimal(500))),
                    nullable = true,
                    doc = "Free text.",
                ),
                field(3, "retries", Scalar(Builtin.INT32), default = IntValue(3)),
                doc = "A customer's order.",
            )
        val schema = def("Order", namespace("s", declarations = listOf(r))) as ObjectSchema
        assertEquals("A customer's order.", schema.common.description)
        assertEquals(true, schema.closed)
        assertEquals(listOf("id", "note", "retries"), schema.properties.map { it.name })
        assertEquals(listOf(true, false, false), schema.properties.map { it.required })
        val note = schema.properties[1].schema as ScalarSchema
        assertEquals(
            ScalarSchema(
                "string",
                maxLength = 500,
                common = Common(description = "Free text.", nullable = true),
            ),
            note,
        )
        assertEquals(JsonNumber("3"), schema.properties[2].schema.common.default)
    }

    @Test
    fun `open records omit the closing and deprecated fields and records are marked`() {
        val r =
            record(
                "s",
                "Event",
                field(
                    1,
                    "x",
                    Scalar(Builtin.BOOL),
                    annotations = core("deprecated" to AnnotationValue.Flag),
                ),
                annotations =
                    Annotations(
                        mapOf(
                            "jsonschema" to mapOf("open" to AnnotationValue.Flag),
                            "" to mapOf("deprecated" to AnnotationValue.Flag),
                        )
                    ),
            )
        val schema = def("Event", namespace("s", declarations = listOf(r))) as ObjectSchema
        assertEquals(false, schema.closed)
        assertEquals(true, schema.common.deprecated)
        assertEquals(true, schema.properties.single().schema.common.deprecated)
    }

    @Test
    fun `references stay local within a document and are absolute across documents`() {
        val customer = record("c", "Customer", field(1, "id", Scalar(Builtin.UUID)))
        val order =
            record(
                "s",
                "Order",
                field(1, "customer", Ref(qn("c", "Customer"))),
                field(2, "parent", Ref(qn("s", "Order")), nullable = true),
            )
        val model =
            lower(
                    namespace("s", declarations = listOf(order)),
                    namespace("c", declarations = listOf(customer)),
                )
                .model
        val schema = model.documents.first().defs.single().schema as ObjectSchema
        assertEquals(RefSchema("urn:schemata:c#/\$defs/Customer"), schema.properties[0].schema)
        assertEquals(
            RefSchema("#/\$defs/Order", Common(nullable = true)),
            schema.properties[1].schema,
        )
    }

    @Test
    fun `nested declarations are keyed by their scoped name`() {
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
                field(1, "line", Ref(qn("s", "Order", "Line"))),
                nested = listOf(line),
            )
        val doc = document(namespace("s", declarations = listOf(order)))
        assertEquals(listOf("Order", "Order.Line"), doc.defs.map { it.key })
        assertEquals(
            RefSchema("#/\$defs/Order.Line"),
            (doc.defs[0].schema as ObjectSchema).properties[0].schema,
        )
    }

    @Test
    fun `name overrides apply to defs keys and properties and an empty one is reported`() {
        val r =
            record(
                "s",
                "Order",
                field(
                    1,
                    "id",
                    Scalar(Builtin.UUID),
                    annotations = js("name" to AnnotationValue.Str("orderId")),
                ),
                field(
                    2,
                    "x",
                    Scalar(Builtin.BOOL),
                    annotations = js("name" to AnnotationValue.Str("")),
                ),
                annotations = js("name" to AnnotationValue.Str("Purchase")),
            )
        val ns = namespace("s", declarations = listOf(r))
        val doc = document(ns)
        assertEquals(listOf("Purchase"), doc.defs.map { it.key })
        assertEquals(
            listOf("orderId", "x"),
            (doc.defs[0].schema as ObjectSchema).properties.map { it.name },
        )
        assertEquals(
            listOf("SCH2303 field 'Order.x': @jsonschema(name: \"\") is empty"),
            messages(ns),
        )
    }

    @Test
    fun `a name override with a reserved character is reported and the field name is used`() {
        val r =
            record(
                "s",
                "Order",
                field(
                    1,
                    "a",
                    Scalar(Builtin.BOOL),
                    annotations = js("name" to AnnotationValue.Str("a/b")),
                ),
                field(
                    2,
                    "c",
                    Scalar(Builtin.BOOL),
                    annotations = js("name" to AnnotationValue.Str("c\nd")),
                ),
            )
        val ns = namespace("s", declarations = listOf(r))
        assertEquals(
            listOf("a", "c"),
            (def("Order", ns) as ObjectSchema).properties.map { it.name },
        )
        assertEquals(
            listOf(
                "SCH2303 field 'Order.a': @jsonschema(name: \"a/b\") contains '/', which a \$ref cannot carry",
                "SCH2303 field 'Order.c': @jsonschema(name: \"c\\nd\") contains '\\n', which a \$ref cannot carry",
            ),
            messages(ns),
        )
    }

    @Test
    fun `two enum values lowering to one string are reported`() {
        val e =
            enum(
                "s",
                "Status",
                "paid",
                "settled",
                valueAnnotations = mapOf("settled" to js("name" to AnnotationValue.Str("paid"))),
            )
        assertEquals(
            listOf(
                "SCH2302 enum value 'Status.settled' lowers to enum value 'paid', already used by enum value 'Status.paid' (orders.schemata:31)"
            ),
            messages(namespace("s", declarations = listOf(e))),
        )
    }

    @Test
    fun `two fields lowering to one property name are reported`() {
        val r =
            record(
                "s",
                "Order",
                field(
                    1,
                    "a",
                    Scalar(Builtin.BOOL),
                    annotations = js("name" to AnnotationValue.Str("b")),
                ),
                field(2, "b", Scalar(Builtin.BOOL)),
            )
        assertEquals(
            listOf(
                "SCH2302 field 'Order.b' lowers to property 'b', already used by field 'Order.a' (orders.schemata:11)"
            ),
            messages(namespace("s", declarations = listOf(r))),
        )
    }

    @Test
    fun `two declarations lowering to one defs key are reported`() {
        val a = record("s", "A", annotations = js("name" to AnnotationValue.Str("B")))
        val b = record("s", "B", line = 7)
        assertEquals(
            listOf(
                "SCH2302 model 'B' lowers to \$defs key 'B', already used by model 'A' (orders.schemata:3)"
            ),
            messages(namespace("s", declarations = listOf(a, b))),
        )
    }

    @Test
    fun `three namespaces with one id are reported once naming all of them`() {
        val a = namespace("a", js("id" to AnnotationValue.Str("urn:x")))
        val b = namespace("b", js("id" to AnnotationValue.Str("urn:x")), line = 9)
        val c = namespace("c", js("id" to AnnotationValue.Str("urn:x")), line = 12)
        assertEquals(
            listOf("SCH2304 schemas a and b and c both lower to \$id 'urn:x'"),
            messages(a, b, c),
        )
    }

    @Test
    fun `a control character in an override is escaped in the report`() {
        val r =
            record(
                "s",
                "Order",
                field(
                    1,
                    "a",
                    Scalar(Builtin.BOOL),
                    annotations = js("name" to AnnotationValue.Str("a\tb")),
                ),
            )
        assertEquals(
            listOf(
                "SCH2303 field 'Order.a': @jsonschema(name: \"a\\tb\") contains '\\t', which a \$ref cannot carry"
            ),
            messages(namespace("s", declarations = listOf(r))),
        )
    }

    @Test
    fun `a decimal default whose scale cannot be set exactly is emitted as written`() {
        val r =
            record(
                "s",
                "Order",
                field(
                    1,
                    "total",
                    Scalar(Builtin.DECIMAL, Refinements(precision = 19, scale = 2)),
                    default = RealValue(BigDecimal("1.23456")),
                ),
            )
        val ns = namespace("s", declarations = listOf(r))
        val schema = def("Order", ns) as ObjectSchema
        assertEquals(JsonString("1.23456"), schema.properties[0].schema.common.default)
        assertEquals(emptyList(), messages(ns))
    }

    @Test
    fun `overridden declarations that share a defs key and a field name are reported once`() {
        val a =
            record(
                "s",
                "A",
                field(1, "x", Scalar(Builtin.BOOL), line = 4),
                annotations = js("name" to AnnotationValue.Str("B")),
            )
        val b = record("s", "B", field(1, "x", Scalar(Builtin.BOOL), line = 9), line = 8)
        assertEquals(
            listOf(
                "SCH2302 model 'B' lowers to \$defs key 'B', already used by model 'A' (orders.schemata:3)"
            ),
            messages(namespace("s", declarations = listOf(a, b))),
        )
    }

    @Test
    fun `overridden enums that share a defs key and a value are reported once`() {
        val a = enum("s", "A", "x", line = 3, annotations = js("name" to AnnotationValue.Str("B")))
        val b = enum("s", "B", "x", line = 8)
        assertEquals(
            listOf(
                "SCH2302 enum 'B' lowers to \$defs key 'B', already used by enum 'A' (orders.schemata:3)"
            ),
            messages(namespace("s", declarations = listOf(a, b))),
        )
    }

    @Test
    fun `a decimal default prints with the field's scale and lossy scalars name the field`() {
        val r =
            record(
                "s",
                "Order",
                field(
                    1,
                    "total",
                    Scalar(Builtin.DECIMAL, Refinements(precision = 19, scale = 4)),
                    default = IntValue(0),
                ),
                field(2, "code", Scalar(Builtin.STRING, Refinements(pattern = "\\Aabc"))),
            )
        val ns = namespace("s", declarations = listOf(r))
        val schema = def("Order", ns) as ObjectSchema
        assertEquals(JsonString("0.0000"), schema.properties[0].schema.common.default)
        assertEquals(
            listOf(
                "SCH2301 field 'Order.code': pattern uses \\A, which JSON Schema (ECMA-262) cannot express; dropped"
            ),
            messages(ns),
        )
    }

    @Test
    fun `an enum lists its values in order with value docs and overrides`() {
        val e =
            enum(
                "s",
                "Status",
                "pending",
                "paid",
                valueDocs = mapOf("pending" to "Not paid yet."),
                valueAnnotations = mapOf("paid" to js("name" to AnnotationValue.Str("PAID"))),
            )
        assertEquals(
            EnumSchema(listOf(EnumEntry("pending", "Not paid yet."), EnumEntry("PAID", null))),
            def("Status", namespace("s", declarations = listOf(e))),
        )
    }

    @Test
    fun `an enum default uses the value's schema name`() {
        val e =
            enum(
                "s",
                "Status",
                "pending",
                valueAnnotations = mapOf("pending" to js("name" to AnnotationValue.Str("P"))),
            )
        val r =
            record(
                "s",
                "Order",
                field(
                    1,
                    "status",
                    Ref(qn("s", "Status")),
                    default = EnumRef(qn("s", "Status"), "pending"),
                ),
            )
        val schema = def("Order", namespace("s", declarations = listOf(e, r))) as ObjectSchema
        assertEquals(
            RefSchema("#/\$defs/Status", Common(default = JsonString("P"))),
            schema.properties.single().schema,
        )
    }

    @Test
    fun `a union is tagged by member type names with member docs and refined scalars kept`() {
        val card = record("s", "Card")
        val transfer = record("s", "BankTransfer", line = 5)
        val u =
            union(
                "s",
                "Payment",
                Ref(qn("s", "Card")),
                Ref(qn("s", "BankTransfer")),
                Scalar(Builtin.STRING, Refinements(max = BigDecimal(8))),
                memberDocs = listOf("By card.", null, null),
                annotations = Annotations(mapOf("" to mapOf("deprecated" to AnnotationValue.Flag))),
            )
        val schema =
            def("Payment", namespace("s", declarations = listOf(card, transfer, u)))
                as TaggedUnionSchema
        assertEquals(
            TaggedUnionSchema(
                listOf(
                    Member("card", RefSchema("#/\$defs/Card", Common(description = "By card."))),
                    Member("bank_transfer", RefSchema("#/\$defs/BankTransfer")),
                    Member("string", ScalarSchema("string", maxLength = 8)),
                ),
                Common(deprecated = true),
            ),
            schema,
        )
    }

    @Test
    fun `a member's tag follows its declaration's name override and a nested member uses its own name`() {
        val line = record("s", "Line", path = listOf("Order", "Line"))
        val order = record("s", "Order", nested = listOf(line))
        val other =
            record("s", "Other", line = 8, annotations = js("name" to AnnotationValue.Str("Alt")))
        val u = union("s", "U", Ref(qn("s", "Order", "Line")), Ref(qn("s", "Other")))
        val schema =
            def("U", namespace("s", declarations = listOf(order, other, u))) as TaggedUnionSchema
        assertEquals(listOf("line", "Alt"), schema.members.map { it.tag })
    }

    @Test
    fun `two members lowering to one tag are reported`() {
        val a = record("s", "Card")
        val b = record("s", "card", line = 5)
        val u = union("s", "U", Ref(qn("s", "Card")), Ref(qn("s", "card")))
        assertEquals(
            listOf(
                "SCH2302 union member 'card' lowers to tag 'card', already used by union member 'Card' (orders.schemata:41)"
            ),
            messages(namespace("s", declarations = listOf(a, b, u))),
        )
    }

    @Test
    fun `lists are arrays with bounds and nullable elements`() {
        val r =
            record(
                "s",
                "R",
                field(
                    1,
                    "tags",
                    ListOf(
                        Scalar(Builtin.STRING),
                        nullableElement = true,
                        Refinements(min = BigDecimal(1), max = BigDecimal(10)),
                    ),
                ),
                field(
                    2,
                    "lines",
                    ListOf(Ref(qn("s", "R")), false, Refinements.NONE),
                    nullable = true,
                ),
            )
        val schema = def("R", namespace("s", declarations = listOf(r))) as ObjectSchema
        assertEquals(
            ArraySchema(
                ScalarSchema("string", common = Common(nullable = true)),
                minItems = 1,
                maxItems = 10,
            ),
            schema.properties[0].schema,
        )
        assertEquals(
            ArraySchema(RefSchema("#/\$defs/R"), common = Common(nullable = true)),
            schema.properties[1].schema,
        )
        assertEquals(listOf(true, false), schema.properties.map { it.required })
    }

    @Test
    fun `maps are objects keyed by property names with bounds and nullable values`() {
        val r =
            record(
                "s",
                "R",
                field(
                    1,
                    "by_name",
                    MapOf(
                        Scalar(Builtin.STRING, Refinements(max = BigDecimal(8))),
                        Scalar(Builtin.INT32),
                        false,
                        Refinements(max = BigDecimal(3)),
                    ),
                ),
                field(
                    2,
                    "by_id",
                    MapOf(
                        Scalar(Builtin.INT64),
                        Ref(qn("s", "R")),
                        nullableValue = true,
                        Refinements.NONE,
                    ),
                ),
                field(
                    3,
                    "plain",
                    MapOf(Scalar(Builtin.STRING), Scalar(Builtin.BOOL), false, Refinements.NONE),
                ),
            )
        val schema = def("R", namespace("s", declarations = listOf(r))) as ObjectSchema
        assertEquals(
            MapSchema(
                ScalarSchema(
                    "integer",
                    minimum = BigDecimal("-2147483648"),
                    maximum = BigDecimal("2147483647"),
                ),
                keys = ScalarSchema("string", maxLength = 8),
                maxProperties = 3,
            ),
            schema.properties[0].schema,
        )
        assertEquals(
            MapSchema(
                RefSchema("#/\$defs/R", Common(nullable = true)),
                keys = ScalarSchema("string", pattern = "^(0|-?[1-9][0-9]*)$"),
            ),
            schema.properties[1].schema,
        )
        assertEquals(MapSchema(ScalarSchema("boolean")), schema.properties[2].schema)
    }

    @Test
    fun `collections nest directly`() {
        val r =
            record(
                "s",
                "R",
                field(
                    1,
                    "grid",
                    ListOf(
                        ListOf(Scalar(Builtin.INT32), false, Refinements.NONE),
                        false,
                        Refinements.NONE,
                    ),
                ),
            )
        val schema = def("R", namespace("s", declarations = listOf(r))) as ObjectSchema
        assertEquals(
            ArraySchema(
                ArraySchema(
                    ScalarSchema(
                        "integer",
                        minimum = BigDecimal("-2147483648"),
                        maximum = BigDecimal("2147483647"),
                    )
                )
            ),
            schema.properties[0].schema,
        )
    }

    private val relations by lazy {
        JsonSchemaLowering.lower(compile(RELATIONS)).model.documents.single()
    }

    private fun relation(key: String) =
        relations.defs.single { it.key == key }.schema as ObjectSchema

    private fun property(key: String, name: String) =
        relation(key).properties.single { it.name == name }

    @Test
    fun `a reference to a keyed model emits its key`() {
        val id = property("Customer", "id").schema
        val code = property("Tag", "code").schema
        assertEquals(
            listOf("id", "customer_id", "billing", "tags", "pair", "pairs", "backup_id"),
            relation("Order").properties.map { it.name },
        )
        assertEquals(Property("customer_id", id, required = true), property("Order", "customer_id"))
        assertEquals(ArraySchema(code), property("Order", "tags").schema)
        assertEquals(
            Property(
                "backup_id",
                (id as ScalarSchema).copy(common = Common(nullable = true)),
                false,
            ),
            property("Order", "backup_id"),
        )
    }

    @Test
    fun `embed restores the record`() {
        assertEquals(RefSchema("#/\$defs/Customer"), property("Order", "billing").schema)
    }

    @Test
    fun `a composite-key reference emits one key object`() {
        assertEquals(
            listOf("Customer", "Tag", "Pair", "PairKey", "Order"),
            relations.defs.map { it.key },
        )
        assertEquals(relation("Pair").properties, relation("PairKey").properties)
        assertEquals(RefSchema("#/\$defs/PairKey"), property("Order", "pair").schema)
        assertEquals(ArraySchema(RefSchema("#/\$defs/PairKey")), property("Order", "pairs").schema)
    }

    private val members by lazy {
        JsonSchemaLowering.lower(compile(MEMBERS)).model.documents.single()
    }

    private fun member(key: String) = members.defs.single { it.key == key }.schema

    @Test
    fun `a union member typed as a keyed model carries its key`() {
        val id = (member("Customer") as ObjectSchema).properties.single().schema
        val party = member("Party") as TaggedUnionSchema
        assertEquals(
            listOf(Member("customer", id), Member("pair", RefSchema("#/\$defs/PairKey"))),
            party.members,
        )
    }

    @Test
    fun `a map value typed as a keyed model carries its key`() {
        val id = (member("Customer") as ObjectSchema).properties.single().schema
        val book = (member("Book") as ObjectSchema).properties.associate { it.name to it.schema }
        assertEquals(id, (book.getValue("by") as MapSchema).values)
        assertEquals(RefSchema("#/\$defs/PairKey"), (book.getValue("pairs") as MapSchema).values)
    }
}
