package io.schemata.target.jsonschema

import io.schemata.core.ir.AnnotationValue
import io.schemata.core.ir.Annotations
import io.schemata.core.ir.Builtin
import io.schemata.core.ir.EnumType
import io.schemata.core.ir.EnumValue
import io.schemata.core.ir.Field
import io.schemata.core.ir.IntValue
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
import io.schemata.lang.Span
import io.schemata.target.json.JsonNumber
import io.schemata.target.json.JsonString
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals

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
            listOf("SCH2303 namespace 's': @jsonschema(id = \"orders\") is not an absolute URI"),
            messages(ns),
        )
        assertEquals("urn:schemata:s", document(ns).id)
    }

    @Test
    fun `two namespaces with one id are reported`() {
        val a = namespace("a", js("id" to AnnotationValue.Str("urn:x")))
        val b = namespace("b", js("id" to AnnotationValue.Str("urn:x")), line = 9)
        assertEquals(
            listOf("SCH2304 namespaces a and b both lower to \$id 'urn:x'"),
            messages(a, b),
        )
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
            listOf("SCH2303 field 'Order.x': @jsonschema(name = \"\") is empty"),
            messages(ns),
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
                "SCH2302 record 'B' lowers to \$defs key 'B', already used by record 'A' (orders.schemata:3)"
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
}
