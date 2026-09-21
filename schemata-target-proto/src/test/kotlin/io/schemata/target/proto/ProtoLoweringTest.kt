package io.schemata.target.proto

import io.schemata.core.ir.AnnotationValue
import io.schemata.core.ir.Annotations
import io.schemata.core.ir.Builtin
import io.schemata.core.ir.EnumType
import io.schemata.core.ir.EnumValue
import io.schemata.core.ir.Field
import io.schemata.core.ir.ListOf
import io.schemata.core.ir.MapOf
import io.schemata.core.ir.Namespace
import io.schemata.core.ir.QualifiedName
import io.schemata.core.ir.RecordType
import io.schemata.core.ir.Ref
import io.schemata.core.ir.Reserved
import io.schemata.core.ir.Scalar
import io.schemata.core.ir.Schema
import io.schemata.core.ir.Type
import io.schemata.core.ir.TypeDecl
import io.schemata.core.ir.UnionType
import io.schemata.lang.Span
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProtoLoweringTest {
    private fun at(line: Int) = Span("orders.schemata", line, 3, line, 20)

    private fun qn(ns: String, vararg path: String) = QualifiedName(ns, path.toList())

    private fun proto(vararg pairs: Pair<String, String>) =
        Annotations(mapOf("proto" to pairs.associate { (k, v) -> k to AnnotationValue.Str(v) }))

    private fun field(
        ordinal: Int,
        name: String,
        type: Type,
        nullable: Boolean = false,
        line: Int = 10 + ordinal,
        annotations: Annotations = Annotations.NONE,
    ) = Field(ordinal, name, type, nullable, null, null, null, at(line), at(line), annotations)

    private fun record(
        ns: String,
        name: String,
        vararg fields: Field,
        path: List<String> = listOf(name),
        nested: List<TypeDecl> = emptyList(),
        reserved: Reserved = Reserved.NONE,
        line: Int = 3,
        annotations: Annotations = Annotations.NONE,
    ) =
        RecordType(
            qn(ns, *path.toTypedArray()),
            name,
            fields.toList(),
            reserved,
            false,
            nested,
            null,
            at(line),
            at(line),
            annotations,
        )

    private fun enum(
        ns: String,
        name: String,
        vararg values: String,
        reserved: Reserved = Reserved.NONE,
        line: Int = 30,
        annotations: Annotations = Annotations.NONE,
    ) =
        EnumType(
            qn(ns, name),
            name,
            values.mapIndexed { i, v ->
                EnumValue(i + 1, v, null, at(line + 1 + i), at(line + 1 + i))
            },
            reserved,
            emptyList(),
            null,
            at(line),
            at(line),
            annotations,
        )

    private fun union(ns: String, name: String, vararg members: Type, line: Int = 40) =
        UnionType(
            qn(ns, name),
            name,
            members.mapIndexed { i, t ->
                io.schemata.core.ir.UnionMember(i + 1, t, null, at(line + 1 + i))
            },
            emptyList(),
            null,
            at(line),
            at(line),
        )

    private fun schema(vararg namespaces: Namespace) = Schema(namespaces.toList())

    private fun ns(
        name: String,
        vararg decls: TypeDecl,
        annotations: Annotations = Annotations.NONE,
    ) = Namespace(name, decls.toList(), at(1), annotations)

    private fun message(file: ProtoFile, name: String) =
        file.declarations.first { it.name == name } as ProtoMessage

    private val user =
        record(
            "shop.orders",
            "User",
            field(1, "id", Scalar(Builtin.UUID), line = 4),
            field(2, "email", Scalar(Builtin.STRING), nullable = true, line = 5),
            field(3, "name", Scalar(Builtin.STRING), line = 6),
            field(4, "age", Scalar(Builtin.INT32), line = 7),
            field(5, "active", Scalar(Builtin.BOOL), line = 8),
        )

    @Test
    fun `emits one file per namespace with package and path`() {
        val files =
            ProtoLowering.lower(schema(ns("shop.customers"), ns("shop.orders", user))).model.files
        assertEquals(
            listOf(
                "shop/customers.proto" to "shop.customers",
                "shop/orders.proto" to "shop.orders",
            ),
            files.map { it.path to it.packageName },
        )
        assertEquals(emptyList(), files[0].imports)
    }

    @Test
    fun `maps plain scalars, keeps ordinals as numbers, and marks nullable scalars optional`() {
        val all =
            record(
                "a",
                "R",
                field(1, "a", Scalar(Builtin.BOOL)),
                field(2, "b", Scalar(Builtin.INT32)),
                field(3, "c", Scalar(Builtin.INT64)),
                field(4, "d", Scalar(Builtin.FLOAT32)),
                field(5, "e", Scalar(Builtin.FLOAT64)),
                field(6, "f", Scalar(Builtin.STRING)),
                field(7, "g", Scalar(Builtin.BYTES)),
                field(8, "h", Scalar(Builtin.STRING), nullable = true),
            )
        val fields =
            message(ProtoLowering.lower(schema(ns("a", all))).model.files.single(), "R").fields
        assertEquals((1..8).toList(), fields.map { it.number })
        assertEquals(
            listOf("bool", "int32", "int64", "float", "double", "string", "bytes", "string"),
            fields.map { (it.type as ProtoType.Scalar).keyword },
        )
        assertEquals(Label.OPTIONAL, fields[7].label)
        assertTrue(fields.dropLast(1).all { it.label == Label.NONE })
    }

    @Test
    fun `temporal builtins use the well-known types and import them once`() {
        val r =
            record(
                "a",
                "R",
                field(1, "at", Scalar(Builtin.INSTANT)),
                field(2, "took", Scalar(Builtin.DURATION)),
                field(3, "maybe", Scalar(Builtin.INSTANT), nullable = true),
            )
        val file = ProtoLowering.lower(schema(ns("a", r))).model.files.single()
        assertEquals(
            listOf("google/protobuf/duration.proto", "google/protobuf/timestamp.proto"),
            file.imports,
        )
        val fields = message(file, "R").fields
        assertEquals(ProtoType.Named("google.protobuf.Timestamp"), fields[0].type)
        assertEquals(ProtoType.Named("google.protobuf.Duration"), fields[1].type)
        assertEquals(Label.NONE, fields[2].label) // message-typed: presence is inherent
    }

    @Test
    fun `lists and maps lower to repeated and map with nested element types`() {
        val item = record("a", "Item", field(1, "n", Scalar(Builtin.STRING)))
        val color = enum("a", "Color", "red")
        val r =
            record(
                "a",
                "R",
                field(1, "tags", ListOf(Scalar(Builtin.STRING), false)),
                field(2, "items", ListOf(Ref(qn("a", "Item")), false)),
                field(3, "colors", ListOf(Ref(qn("a", "Color")), false)),
                field(4, "counts", MapOf(Scalar(Builtin.STRING), Scalar(Builtin.INT32), false)),
                field(5, "by_id", MapOf(Scalar(Builtin.INT64), Ref(qn("a", "Item")), false)),
                field(6, "stamps", ListOf(Scalar(Builtin.INSTANT), false)),
            )
        val file = ProtoLowering.lower(schema(ns("a", item, color, r))).model.files.single()
        val fields = message(file, "R").fields
        assertEquals(
            listOf(
                Label.REPEATED,
                Label.REPEATED,
                Label.REPEATED,
                Label.NONE,
                Label.NONE,
                Label.REPEATED,
            ),
            fields.map { it.label },
        )
        assertEquals(ProtoType.Scalar("string"), fields[0].type)
        assertEquals(ProtoType.Named("Item"), fields[1].type)
        assertEquals(ProtoType.Named("Color"), fields[2].type)
        assertEquals(
            ProtoType.MapOf(ProtoType.Scalar("string"), ProtoType.Scalar("int32")),
            fields[3].type,
        )
        assertEquals(
            ProtoType.MapOf(ProtoType.Scalar("int64"), ProtoType.Named("Item")),
            fields[4].type,
        )
        assertEquals(ProtoType.Named("google.protobuf.Timestamp"), fields[5].type)
        assertEquals(listOf("google/protobuf/timestamp.proto"), file.imports)
    }

    @Test
    fun `references are spelled relative to where they are used and imported across packages`() {
        val note =
            record(
                "a",
                "Note",
                field(1, "t", Scalar(Builtin.STRING)),
                path = listOf("Order", "Note"),
            )
        val line =
            record(
                "a",
                "Line",
                field(1, "gift", Ref(qn("a", "Order", "Note"))),
                field(2, "order", Ref(qn("a", "Order"))),
                field(3, "who", Ref(qn("b", "Person"))),
                path = listOf("Order", "Line"),
            )
        val order =
            record(
                "a",
                "Order",
                field(1, "lines", ListOf(Ref(qn("a", "Order", "Line")), false)),
                field(2, "parent", Ref(qn("a", "Order")), nullable = true),
                field(3, "note", Ref(qn("a", "Order", "Note"))),
                nested = listOf(line, note),
            )
        val audit =
            record(
                "a",
                "Audit",
                field(1, "line", Ref(qn("a", "Order", "Line"))),
                field(2, "who", Ref(qn("b", "Person"))),
            )
        val person =
            record("b", "Person", field(1, "friends", ListOf(Ref(qn("b", "Person")), false)))
        val files =
            ProtoLowering.lower(
                    schema(
                        ns("a", order, audit),
                        ns("b", person, annotations = proto("package" to "people.v1")),
                    )
                )
                .model
                .files
        val a = files[0]
        assertEquals(listOf("b.proto"), a.imports)
        val orderMessage = message(a, "Order")
        assertEquals(
            listOf(ProtoType.Named("Line"), ProtoType.Named("Order"), ProtoType.Named("Note")),
            orderMessage.fields.map { it.type },
        )
        assertEquals(Label.NONE, orderMessage.fields[1].label)
        val lineMessage = orderMessage.nested.first { it.name == "Line" } as ProtoMessage
        assertEquals(
            listOf(
                ProtoType.Named("Note"),
                ProtoType.Named("Order"),
                ProtoType.Named("people.v1.Person"),
            ),
            lineMessage.fields.map { it.type },
        )
        assertEquals(
            listOf(ProtoType.Named("Order.Line"), ProtoType.Named("people.v1.Person")),
            message(a, "Audit").fields.map { it.type },
        )
        val b = files[1]
        assertEquals("b.proto" to "people.v1", b.path to b.packageName)
        assertEquals(emptyList(), b.imports)
        assertEquals(ProtoType.Named("Person"), message(b, "Person").fields.single().type)
    }

    @Test
    fun `a relative name proto would resolve to a closer declaration is package-qualified`() {
        val innerCard =
            record("a", "Card", field(1, "x", Scalar(Builtin.BOOL)), path = listOf("Order", "Card"))
        val order =
            record(
                "a",
                "Order",
                field(1, "outer", Ref(qn("a", "Card"))),
                field(2, "inner", Ref(qn("a", "Order", "Card"))),
                nested = listOf(innerCard),
            )
        val card = record("a", "Card", field(1, "y", Scalar(Builtin.BOOL)))
        val file = ProtoLowering.lower(schema(ns("a", card, order))).model.files.single()
        assertEquals(
            listOf(ProtoType.Named("a.Card"), ProtoType.Named("Card")),
            message(file, "Order").fields.map { it.type },
        )
    }

    @Test
    fun `enums get a synthesized zero and prefixed values, and reserved names are transformed`() {
        val status =
            enum(
                "a",
                "OrderStatus",
                "pending",
                "paid",
                reserved = Reserved(listOf(5..6), setOf("old_status")),
            )
        val file = ProtoLowering.lower(schema(ns("a", status))).model.files.single()
        val e = file.declarations.single() as ProtoEnum
        assertEquals("OrderStatus", e.name)
        assertEquals(
            listOf(
                "ORDER_STATUS_UNSPECIFIED" to 0,
                "ORDER_STATUS_PENDING" to 1,
                "ORDER_STATUS_PAID" to 2,
            ),
            e.values.map { it.name to it.number },
        )
        assertEquals(ProtoReserved(listOf(5..6), listOf("ORDER_STATUS_OLD_STATUS")), e.reserved)
    }

    @Test
    fun `unions become a wrapper message with a oneof named from the member types`() {
        val card = record("a", "Card", field(1, "l", Scalar(Builtin.STRING)))
        val transfer = record("a", "BankTransfer", field(1, "i", Scalar(Builtin.STRING)))
        val payment =
            union(
                "a",
                "Payment",
                Ref(qn("a", "Card")),
                Ref(qn("a", "BankTransfer")),
                Scalar(Builtin.UUID),
                Scalar(Builtin.INT64),
            )
        val order =
            record(
                "a",
                "Order",
                field(1, "payment", Ref(qn("a", "Payment"))),
                field(2, "history", ListOf(Ref(qn("a", "Payment")), false)),
                field(3, "fallback", Ref(qn("a", "Payment")), nullable = true),
            )
        val file =
            ProtoLowering.lower(schema(ns("a", card, transfer, payment, order)))
                .model
                .files
                .single()
        val wrapper = message(file, "Payment")
        assertEquals(emptyList(), wrapper.fields)
        val oneof = wrapper.oneofs.single()
        assertEquals("kind", oneof.name)
        assertEquals(
            listOf(
                "card" to ProtoType.Named("Card"),
                "bank_transfer" to ProtoType.Named("BankTransfer"),
                "uuid" to ProtoType.Scalar("string"),
                "int64" to ProtoType.Scalar("int64"),
            ),
            oneof.fields.map { it.name to it.type },
        )
        assertEquals(listOf(1, 2, 3, 4), oneof.fields.map { it.number })
        val fields = message(file, "Order").fields
        assertEquals(listOf(Label.NONE, Label.REPEATED, Label.NONE), fields.map { it.label })
        assertTrue(fields.all { it.type == ProtoType.Named("Payment") })
    }

    @Test
    fun `record reserved ordinals and names pass through`() {
        val r =
            record(
                "a",
                "R",
                field(1, "x", Scalar(Builtin.BOOL)),
                reserved = Reserved(listOf(11..11, 5..7), setOf("legacy_ref", "a")),
            )
        val m = message(ProtoLowering.lower(schema(ns("a", r))).model.files.single(), "R")
        assertEquals(ProtoReserved(listOf(11..11, 5..7), listOf("a", "legacy_ref")), m.reserved)
    }

    @Test
    fun `proto name overrides apply to declarations, fields, and values`() {
        val status =
            EnumType(
                qn("a", "Status"),
                "Status",
                listOf(
                    EnumValue(
                        1,
                        "cancelled",
                        null,
                        at(31),
                        at(31),
                        proto("name" to "CANCELLED_BY_USER"),
                    )
                ),
                Reserved.NONE,
                emptyList(),
                null,
                at(30),
                at(30),
                proto("name" to "State"),
            )
        val r =
            record(
                "a",
                "Product",
                field(1, "sku", Scalar(Builtin.STRING), annotations = proto("name" to "sku_code")),
                field(2, "s", Ref(qn("a", "Status"))),
                annotations = proto("name" to "Item"),
            )
        val file = ProtoLowering.lower(schema(ns("a", status, r))).model.files.single()
        assertEquals(listOf("State", "Item"), file.declarations.map { it.name })
        assertEquals(
            listOf("STATE_UNSPECIFIED", "CANCELLED_BY_USER"),
            (file.declarations[0] as ProtoEnum).values.map { it.name },
        )
        assertEquals(
            listOf("sku_code" to ProtoType.Scalar("string"), "s" to ProtoType.Named("State")),
            message(file, "Item").fields.map { it.name to it.type },
        )
    }
}
