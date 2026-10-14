package io.schemata.target.proto

import io.schemata.core.AnalysisOptions
import io.schemata.core.Analyzer
import io.schemata.core.annotations.AnnotationRegistry
import io.schemata.core.annotations.CoreAnnotations
import io.schemata.core.annotations.Element
import io.schemata.core.annotations.ValueKind
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
import io.schemata.lang.Category
import io.schemata.lang.Parser
import io.schemata.lang.SchemataText
import io.schemata.lang.Span
import io.schemata.target.Lowered
import io.schemata.target.ProtoFileOptions
import io.schemata.target.ProtoLayout
import io.schemata.testkit.Protoc
import java.io.File
import java.math.BigDecimal
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val SERVICE =
    """
schema shop.orders

import shop.catalog as catalog

model OrderId { #1 id uuid }

model Order { #1 id uuid  #2 total catalog.Money }

model Chunk { #1 bytes bytes }

/// Place and read orders.
service Orders {
  /// Fetch one order.
  #1 get(OrderId): Order  get "/orders/{id}"
  #2 list(OrderId): stream Order  get "/orders"
  #4 cancel(OrderId)  delete "/orders/{id}"
  @deprecated #5 upload(stream Chunk)
  reserved #3, "archive"
}
"""

private const val CATALOG = "schema shop.catalog\n\nmodel Money { #1 amount int64 }\n"

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

/** [ALPHA] and [BETA] form a reference cycle: each schema's model refers to the other's. */
private const val ALPHA = "schema cyc.alpha\n\nimport cyc.beta\n\nmodel A { #1 b B? }\n"

private const val BETA = "schema cyc.beta\n\nimport cyc.alpha\n\nmodel B { #1 a A? }\n"

private const val NIEM =
    "schema niem_core\n\nimport uc2_system_task as uc2\n\nmodel Task { #1 sub uc2.Task? }\n"

private const val UC2 =
    "schema uc2_system_task\n\nimport niem_core as niem\n\nmodel Task { #1 parent niem.Task? }\n"

class ProtoLoweringTest {
    private fun at(line: Int) = Span("orders.schemata", line, 3, line, 20)

    private fun qn(ns: String, vararg path: String) = QualifiedName(ns, path.toList())

    private fun proto(vararg pairs: Pair<String, String>) =
        Annotations(mapOf("proto" to pairs.associate { (k, v) -> k to AnnotationValue.Str(v) }))

    private val deprecated = Annotations(mapOf("" to mapOf("deprecated" to AnnotationValue.Flag)))

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
        reserved: Reserved = Reserved.NONE,
        line: Int = 3,
        doc: String? = null,
        annotations: Annotations = Annotations.NONE,
    ) =
        RecordType(
            qn(ns, *path.toTypedArray()),
            name,
            fields.toList(),
            reserved,
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

    private fun schema(vararg namespaces: Namespace) = Schema(namespaces.toList())

    private fun ns(
        name: String,
        vararg decls: TypeDecl,
        annotations: Annotations = Annotations.NONE,
    ) = Namespace(name, decls.toList(), at(1), annotations)

    private fun messages(lowered: io.schemata.target.Lowered<ProtoModel>) =
        lowered.diagnostics.map { "${it.span.startLine} ${it.code.id} ${it.message}" }

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
        assertEquals(ProtoType.Named(".google.protobuf.Timestamp"), fields[0].type)
        assertEquals(ProtoType.Named(".google.protobuf.Duration"), fields[1].type)
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
        assertEquals(ProtoType.Named(".google.protobuf.Timestamp"), fields[5].type)
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
                ProtoType.Named(".people.v1.Person"),
            ),
            lineMessage.fields.map { it.type },
        )
        assertEquals(
            listOf(ProtoType.Named("Order.Line"), ProtoType.Named(".people.v1.Person")),
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
            listOf(ProtoType.Named(".a.Card"), ProtoType.Named("Card")),
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
    fun `a union member honours the member type's name override`() {
        val card =
            record(
                "a",
                "Card",
                field(1, "l", Scalar(Builtin.STRING)),
                annotations = proto("name" to "CreditCard"),
            )
        val payment = union("a", "Payment", Ref(qn("a", "Card")))
        val file = ProtoLowering.lower(schema(ns("a", card, payment))).model.files.single()
        val oneof = message(file, "Payment").oneofs.single()
        assertEquals("CreditCard", oneof.fields.single().name)
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

    @Test
    fun `an enum value override equal to its own name is used verbatim`() {
        fun value(ordinal: Int, name: String, override: String, line: Int) =
            EnumValue(ordinal, name, null, at(line), at(line), proto("name" to override))
        fun enumOf(name: String, line: Int, vararg values: EnumValue) =
            EnumType(
                qn("a", name),
                name,
                values.toList(),
                Reserved.NONE,
                emptyList(),
                null,
                at(line),
                at(line),
            )
        val status = enumOf("Status", 30, value(1, "PENDING", "PENDING", 31))
        val other =
            enumOf("Other", 40, value(1, "a", "STATUS_PENDING", 41), value(2, "b", "PENDING", 42))
        val lowered = ProtoLowering.lower(schema(ns("a", status, other)))
        val file = lowered.model.files.single()
        assertEquals(
            listOf("STATUS_UNSPECIFIED", "PENDING"),
            (file.declarations[0] as ProtoEnum).values.map { it.name },
        )
        assertEquals(
            listOf(
                "42 SCH2004 proto name 'PENDING' is already used by value 'PENDING' (orders.schemata:31)"
            ),
            messages(lowered).filter { "SCH2004" in it },
        )
    }

    @Test
    fun `a nullable message-typed field carries its type in the note without a diagnostic`() {
        val order =
            record(
                "a",
                "Order",
                field(1, "parent", Ref(qn("a", "Order")), nullable = true),
                field(2, "at", Scalar(Builtin.INSTANT), nullable = true),
                field(3, "took", Scalar(Builtin.DURATION), nullable = true),
                field(4, "pay", Ref(qn("a", "Payment")), nullable = true),
            )
        val payment = union("a", "Payment", Scalar(Builtin.STRING), Scalar(Builtin.INT32))
        val lowered = ProtoLowering.lower(schema(ns("a", order, payment)))
        val fields = message(lowered.model.files.single(), "Order").fields
        assertEquals(
            listOf(listOf("Order?"), listOf("instant?"), listOf("duration?"), listOf("Payment?")),
            fields.map { it.notes },
        )
        assertTrue(fields.all { it.label == Label.NONE })
        assertEquals(emptyList(), messages(lowered))
    }

    @Test
    fun `a required message-typed field carries no note`() {
        val order =
            record(
                "a",
                "Order",
                field(1, "parent", Ref(qn("a", "Order"))),
                field(2, "at", Scalar(Builtin.INSTANT)),
            )
        val fields =
            message(ProtoLowering.lower(schema(ns("a", order))).model.files.single(), "Order")
                .fields
        assertEquals(listOf(emptyList(), emptyList()), fields.map { it.notes })
    }

    @Test
    fun `lossy scalars are lowered to string, warned once, and noted with the type text`() {
        val r =
            record(
                "a",
                "R",
                field(1, "id", Scalar(Builtin.UUID)),
                field(2, "total", Scalar(Builtin.DECIMAL, Refinements(precision = 19, scale = 4))),
                field(3, "day", Scalar(Builtin.DATE), nullable = true),
                field(4, "tod", Scalar(Builtin.TIME)),
            )
        val lowered = ProtoLowering.lower(schema(ns("a", r)))
        val fields = message(lowered.model.files.single(), "R").fields
        assertTrue(fields.all { it.type == ProtoType.Scalar("string") })
        assertEquals(
            listOf(Label.NONE, Label.NONE, Label.OPTIONAL, Label.NONE),
            fields.map { it.label },
        )
        assertEquals(
            listOf(listOf("uuid"), listOf("decimal(19, 4)"), listOf("date?"), listOf("time")),
            fields.map { it.notes },
        )
        assertEquals(
            listOf(
                "11 SCH2001 field 'R.id': uuid has no Protobuf representation; lowered to string",
                "12 SCH2001 field 'R.total': decimal has no Protobuf representation; lowered to string",
                "13 SCH2001 field 'R.day': date has no Protobuf representation; lowered to string",
                "14 SCH2001 field 'R.tod': time has no Protobuf representation; lowered to string",
            ),
            messages(lowered),
        )
        assertTrue(lowered.diagnostics.all { it.category == Category.LOSSY })
    }

    @Test
    fun `refinements, defaults, and nullable collections are lossy in a fixed order`() {
        val big = BigDecimal.valueOf(5)
        val r =
            record(
                "a",
                "R",
                field(1, "s", Scalar(Builtin.STRING, Refinements(max = big)), nullable = true),
                field(
                    2,
                    "n",
                    Scalar(Builtin.INT32, Refinements(min = BigDecimal.ZERO)),
                    default = IntValue(3),
                ),
                field(
                    3,
                    "l",
                    ListOf(
                        Scalar(Builtin.STRING, Refinements(max = big)),
                        true,
                        Refinements(max = big),
                    ),
                    nullable = true,
                ),
                field(
                    4,
                    "m",
                    MapOf(Scalar(Builtin.STRING), Scalar(Builtin.UUID), true),
                    nullable = true,
                ),
                field(
                    5,
                    "d",
                    Scalar(
                        Builtin.DECIMAL,
                        Refinements(min = BigDecimal.ONE, precision = 5, scale = 1),
                    ),
                    default = IntValue(2),
                ),
                field(6, "e", Ref(qn("a", "E")), default = EnumRef(qn("a", "E"), "x")),
            )
        val lowered = ProtoLowering.lower(schema(ns("a", enum("a", "E", "x"), r)))
        val fields = message(lowered.model.files.single(), "R").fields
        assertEquals(
            listOf(
                listOf("string? { max 5 }"),
                listOf("int32 { min 0 }", "default = 3"),
                listOf("string?[]? { maxItems 5, max 5 }"),
                listOf("map<string, uuid?>?"),
                listOf("decimal(5, 1) { min 1 }", "default = 2"),
                listOf("default = E_X"),
            ),
            fields.map { it.notes },
        )
        assertEquals(
            listOf(
                "30 SCH2001 enum 'E': proto3 requires a zero value; synthesized E_UNSPECIFIED = 0",
                "11 SCH2001 field 'R.s': refinements on string { max 5 } are not enforced by Protobuf",
                "12 SCH2001 field 'R.n': refinements on int32 { min 0 } are not enforced by Protobuf",
                "12 SCH2001 field 'R.n': default 3 is not carried by proto3",
                "13 SCH2001 field 'R.l': refinements on string?[] { maxItems 5, max 5 } are not enforced by Protobuf",
                "13 SCH2001 field 'R.l': a nullable list has no Protobuf representation; lowered to repeated",
                "13 SCH2001 field 'R.l': nullable list elements have no Protobuf representation; lowered to repeated",
                "14 SCH2001 field 'R.m': a nullable map has no Protobuf representation; lowered to map",
                "14 SCH2001 field 'R.m': nullable map values have no Protobuf representation; lowered to map",
                "14 SCH2001 field 'R.m': uuid has no Protobuf representation; lowered to string",
                "15 SCH2001 field 'R.d': refinements on decimal(5, 1) { min 1 } are not enforced by Protobuf",
                "15 SCH2001 field 'R.d': decimal has no Protobuf representation; lowered to string",
                "15 SCH2001 field 'R.d': default 2 is not carried by proto3",
                "16 SCH2001 field 'R.e': default E_X is not carried by proto3",
            ),
            messages(lowered),
        )
    }

    @Test
    fun `a refined scalar union member is lossy like a field`() {
        val u =
            union(
                "a",
                "U",
                Scalar(Builtin.STRING, Refinements(max = BigDecimal.valueOf(5))),
                Scalar(Builtin.UUID),
            )
        val lowered = ProtoLowering.lower(schema(ns("a", u)))
        val members = message(lowered.model.files.single(), "U").oneofs.single().fields
        assertEquals(listOf(listOf("string { max 5 }"), listOf("uuid")), members.map { it.notes })
        assertEquals(
            listOf(
                "41 SCH2001 member 'U.string': refinements on string { max 5 } are not enforced by Protobuf",
                "42 SCH2001 member 'U.uuid': uuid has no Protobuf representation; lowered to string",
            ),
            messages(lowered),
        )
    }

    @Test
    fun `docs and deprecation are carried to messages, fields, enums, and values`() {
        val status =
            EnumType(
                qn("a", "Status"),
                "Status",
                listOf(
                    EnumValue(1, "pending", "Not yet paid.", at(31), at(31)),
                    EnumValue(2, "paid", null, at(32), at(32), deprecated),
                ),
                Reserved.NONE,
                emptyList(),
                "Payment status.",
                at(30),
                at(30),
                deprecated,
            )
        val r =
            record(
                "a",
                "Order",
                field(
                    1,
                    "id",
                    Scalar(Builtin.UUID),
                    doc = "Primary key.",
                    annotations = deprecated,
                ),
                doc = "An order.",
                annotations = deprecated,
            )
        val u =
            UnionType(
                qn("a", "U"),
                "U",
                listOf(UnionMember(1, Ref(qn("a", "Order")), "The order.", at(41))),
                emptyList(),
                "A choice.",
                at(40),
                at(40),
                deprecated,
            )
        val file = ProtoLowering.lower(schema(ns("a", status, r, u))).model.files.single()
        val e = file.declarations[0] as ProtoEnum
        assertEquals("Payment status." to true, e.doc to e.deprecated)
        assertEquals(
            listOf(null to false, "Not yet paid." to false, null to true),
            e.values.map { it.doc to it.deprecated },
        )
        val m = message(file, "Order")
        assertEquals("An order." to true, m.doc to m.deprecated)
        assertEquals("Primary key." to true, m.fields.single().doc to m.fields.single().deprecated)
        val w = message(file, "U")
        assertEquals("A choice." to true, w.doc to w.deprecated)
        assertEquals("The order.", w.oneofs.single().fields.single().doc)
    }

    @Test
    fun `fields whose JSON names collide are errors naming both`() {
        val r =
            record(
                "a",
                "R",
                field(1, "a_1", Scalar(Builtin.BOOL)),
                field(2, "a1", Scalar(Builtin.BOOL)),
                field(3, "placed_at", Scalar(Builtin.BOOL)),
                field(4, "when", Scalar(Builtin.BOOL), annotations = proto("name" to "placedAt")),
                field(5, "same", Scalar(Builtin.BOOL)),
                field(6, "other", Scalar(Builtin.BOOL), annotations = proto("name" to "same")),
            )
        assertEquals(
            listOf(
                "16 SCH2004 proto name 'same' is already used by field 'same' (orders.schemata:15)",
                "12 SCH2008 fields 'R.a_1' and 'R.a1' share the Protobuf JSON name 'a1'",
                "14 SCH2008 fields 'R.placed_at' and 'R.when' share the Protobuf JSON name 'placedAt'",
            ),
            messages(ProtoLowering.lower(schema(ns("a", r)))),
        )
    }

    @Test
    fun `names that collide after overrides are errors naming both`() {
        val a =
            record(
                "a",
                "A",
                field(1, "x", Scalar(Builtin.BOOL)),
                line = 3,
                annotations = proto("name" to "Same"),
            )
        val b =
            record(
                "a",
                "B",
                field(1, "x", Scalar(Builtin.BOOL)),
                line = 6,
                annotations = proto("name" to "Same"),
            )
        val inner1 =
            record(
                "a",
                "In1",
                field(1, "x", Scalar(Builtin.BOOL)),
                path = listOf("C", "In1"),
                line = 10,
                annotations = proto("name" to "N"),
            )
        val inner2 =
            record(
                "a",
                "In2",
                field(1, "x", Scalar(Builtin.BOOL)),
                path = listOf("C", "In2"),
                line = 12,
                annotations = proto("name" to "N"),
            )
        val c =
            record(
                "a",
                "C",
                field(1, "p", Scalar(Builtin.BOOL), line = 15),
                field(2, "q", Scalar(Builtin.BOOL), line = 16, annotations = proto("name" to "p")),
                nested = listOf(inner1, inner2),
                line = 9,
            )
        val e =
            EnumType(
                qn("a", "E"),
                "E",
                listOf(
                    EnumValue(1, "unspecified", null, at(31), at(31)),
                    EnumValue(2, "x", null, at(32), at(32), proto("name" to "E_X")),
                    EnumValue(3, "y", null, at(33), at(33), proto("name" to "E_X")),
                ),
                Reserved.NONE,
                emptyList(),
                null,
                at(30),
                at(30),
            )
        val f =
            EnumType(
                qn("a", "F"),
                "F",
                listOf(EnumValue(1, "x", null, at(36), at(36), proto("name" to "E_X"))),
                Reserved.NONE,
                emptyList(),
                null,
                at(35),
                at(35),
            )
        val u = union("a", "U", Ref(qn("a", "Kind")), Ref(qn("a", "Kind2")), line = 40)
        val kind = record("a", "Kind", field(1, "x", Scalar(Builtin.BOOL)), line = 50)
        val kind2 =
            record(
                "a",
                "Kind2",
                field(1, "x", Scalar(Builtin.BOOL)),
                line = 52,
                annotations = proto("name" to "Kind"),
            )
        val g =
            record(
                "a",
                "G",
                field(
                    1,
                    "line",
                    Scalar(Builtin.BOOL),
                    line = 62,
                    annotations = proto("name" to "Line"),
                ),
                nested =
                    listOf(
                        record(
                            "a",
                            "Line",
                            field(1, "q", Scalar(Builtin.BOOL)),
                            path = listOf("G", "Line"),
                            line = 61,
                        )
                    ),
                line = 60,
            )
        val lowered = ProtoLowering.lower(schema(ns("a", a, b, c, kind, kind2, e, f, u, g)))
        assertEquals(
            listOf(
                "6 SCH2004 proto name 'Same' is already used by model 'A' (orders.schemata:3)",
                "52 SCH2004 proto name 'Kind' is already used by model 'Kind' (orders.schemata:50)",
                "31 SCH2004 proto name 'E_UNSPECIFIED' is already used by the synthesized zero value",
                "33 SCH2004 proto name 'E_X' is already used by value 'x' (orders.schemata:32)",
                "36 SCH2004 proto name 'E_X' is already used by value 'x' (orders.schemata:32)",
                "12 SCH2004 proto name 'N' is already used by model 'In1' (orders.schemata:10)",
                "16 SCH2004 proto name 'p' is already used by field 'p' (orders.schemata:15)",
                "41 SCH2004 proto name 'kind' is already used by the oneof",
                "62 SCH2004 proto name 'Line' is already used by model 'Line' (orders.schemata:61)",
            ),
            messages(lowered).filter { "SCH2004" in it },
        )
    }

    @Test
    fun `enum values collide across sibling enums because proto scopes them at the package`() {
        val payment = enum("a", "Payment", "method_card", line = 30)
        val method = enum("a", "PaymentMethod", "card", line = 40)
        val lowered = ProtoLowering.lower(schema(ns("a", payment, method)))
        assertEquals(
            listOf(
                "41 SCH2004 proto name 'PAYMENT_METHOD_CARD' is already used by value 'method_card' (orders.schemata:31)"
            ),
            messages(lowered).filter { "SCH2004" in it },
        )
    }

    @Test
    fun `invalid field numbers and reserved ranges are reported`() {
        val r =
            record(
                "a",
                "R",
                field(19000, "a", Scalar(Builtin.BOOL), line = 11),
                field(600000000, "b", Scalar(Builtin.BOOL), line = 12),
                reserved = Reserved(listOf(0..0, 3..5, 4..4), emptySet()),
            )
        val lowered = ProtoLowering.lower(schema(ns("a", r)))
        assertEquals(
            listOf(
                "11 SCH2006 field 'R.a': field number 19000 is reserved for the Protobuf implementation (19000 to 19999)",
                "12 SCH2006 field 'R.b': field number 600000000 exceeds the Protobuf maximum 536870911",
                "3 SCH2006 model 'R': reserved number 0 must be positive",
                "3 SCH2006 model 'R': reserved range 4 to 4 overlaps 3 to 5",
            ),
            messages(lowered),
        )
    }

    @Test
    fun `proto overrides must be identifiers`() {
        val e =
            EnumType(
                qn("corp", "E"),
                "E",
                listOf(EnumValue(1, "x", null, at(31), at(31), proto("name" to "A-B"))),
                Reserved.NONE,
                emptyList(),
                null,
                at(30),
                at(30),
            )
        val payment =
            union(
                "corp",
                "Payment",
                Scalar(Builtin.INT64),
                line = 40,
                annotations = proto("name" to "Pay"),
            )
        val r =
            record(
                "corp",
                "R",
                field(1, "sku", Scalar(Builtin.STRING), annotations = proto("name" to "1x")),
                field(2, "pay", Ref(qn("corp", "Payment")), line = 12),
                annotations = proto("name" to "Bad Name"),
            )
        val lowered =
            ProtoLowering.lower(
                schema(
                    Namespace("corp", listOf(e, payment, r), at(1), proto("package" to "corp v1"))
                )
            )
        assertEquals(
            listOf(
                "1 SCH2007 schema 'corp': @proto(package: \"corp v1\") is not a valid package name",
                "31 SCH2007 enum value 'E.x': @proto(name: \"A-B\") is not a valid identifier",
                "3 SCH2007 model 'R': @proto(name: \"Bad Name\") is not a valid identifier",
                "11 SCH2007 field 'R.sku': @proto(name: \"1x\") is not a valid identifier",
            ),
            messages(lowered).filter { "SCH2007" in it },
        )
        val file = lowered.model.files.single()
        assertEquals("Pay", message(file, "Pay").name)
        val record = message(file, "R")
        assertEquals(listOf("sku", "pay"), record.fields.map { it.name })
        assertEquals(ProtoType.Named("Pay"), record.fields[1].type)
    }

    @Test
    fun `an invalid name override is reported once and the declared name is used`() {
        val r =
            record(
                "a",
                "R",
                field(1, "x", Scalar(Builtin.BOOL), annotations = proto("name" to "1x")),
                field(2, "y", Scalar(Builtin.BOOL)),
            )
        val lowered = ProtoLowering.lower(schema(ns("a", r)))
        assertEquals(
            listOf("11 SCH2007 field 'R.x': @proto(name: \"1x\") is not a valid identifier"),
            messages(lowered),
        )
        assertEquals("x", message(lowered.model.files.single(), "R").fields.first().name)
    }

    @Test
    fun `two namespaces lowering to one package is an error`() {
        val lowered =
            ProtoLowering.lower(
                schema(
                    ns("a.x", annotations = proto("package" to "p")),
                    Namespace("b.y", emptyList(), at(7), proto("package" to "p")),
                )
            )
        assertEquals(
            listOf("7 SCH2004 schemas a.x and b.y both lower to package 'p'"),
            messages(lowered),
        )
    }

    @Test
    fun `collections inside collections are errors`() {
        val r =
            record(
                "a",
                "R",
                field(1, "grid", ListOf(ListOf(Scalar(Builtin.INT32), false), false)),
                field(
                    2,
                    "index",
                    MapOf(Scalar(Builtin.STRING), ListOf(Scalar(Builtin.STRING), false), false),
                ),
            )
        val lowered = ProtoLowering.lower(schema(ns("a", r)))
        assertEquals(
            listOf(
                "11 SCH2005 field 'R.grid': proto cannot nest collections; list<int32[]> has a collection element",
                "12 SCH2005 field 'R.index': proto cannot nest collections; map<string, string[]> has a collection element",
            ),
            messages(lowered),
        )
    }

    /** Analyses [sources], each in a file named for its namespace, and lowers the schema. */
    private fun lower(vararg sources: String): Lowered<ProtoModel> =
        ProtoLowering.lower(analysed(*sources))

    /** Analyses [sources], each in a file named for its namespace, expecting no diagnostics. */
    private fun analysed(vararg sources: String): Schema {
        val files =
            sources.map { text ->
                val ns = Regex("""schema\s+([\w.]+)""").find(text)!!.groupValues[1]
                Parser.parse(text, "$ns.schemata").file!!
            }
        val analysis =
            Analyzer.analyze(
                files,
                AnalysisOptions(
                    annotations = AnnotationRegistry(CoreAnnotations.specs + ProtoAnnotations.specs)
                ),
            )
        assertEquals(emptyList(), analysis.diagnostics.map { "${it.code.id} ${it.message}" })
        return analysis.schema!!
    }

    private fun Lowered<ProtoModel>.codes(): List<String> =
        diagnostics.map { "${it.code.id} ${it.message}" }

    /** Each diagnostic as `<file>:<line> <code> <message>`, so the blamed schema shows. */
    private fun Lowered<ProtoModel>.located(): List<String> =
        diagnostics.map { "${it.span.file}:${it.span.startLine} ${it.code.id} ${it.message}" }

    private fun Lowered<ProtoModel>.protocErrors(): String? =
        Protoc.compile(ProtoRenderer.render(model).associate { it.path to it.content })

    private fun Lowered<ProtoModel>.file(path: String): ProtoFile =
        model.files.single { it.path == path }

    @Test
    fun `a service lowers to rpcs with notes`() {
        val file = lower(SERVICE, CATALOG).file("shop/orders.proto")
        val s = file.services.single()
        assertEquals("Orders", s.name)
        assertEquals("Place and read orders.", s.doc)
        assertEquals(listOf("reserved #3, \"archive\""), s.notes)
        assertEquals(
            listOf(
                ProtoRpc(
                    "Get",
                    ProtoRpcType("OrderId", false),
                    ProtoRpcType("Order", false),
                    "Fetch one order.",
                    listOf("get \"/orders/{id}\""),
                ),
                ProtoRpc(
                    "List",
                    ProtoRpcType("OrderId", false),
                    ProtoRpcType("Order", true),
                    null,
                    listOf("get \"/orders\""),
                ),
                ProtoRpc(
                    "Cancel",
                    ProtoRpcType("OrderId", false),
                    ProtoRpcType(".google.protobuf.Empty", false),
                    null,
                    listOf("#4", "delete \"/orders/{id}\""),
                ),
                ProtoRpc(
                    "Upload",
                    ProtoRpcType("Chunk", true),
                    ProtoRpcType(".google.protobuf.Empty", false),
                    null,
                    listOf("#5"),
                    deprecated = true,
                ),
            ),
            s.rpcs,
        )
        assertEquals(listOf("google/protobuf/empty.proto", "shop/catalog.proto"), file.imports)
    }

    @Test
    fun `a service with no reservations has no service note`() {
        val s =
            lower(
                    "schema t\n" +
                        "\n" +
                        "model R { #1 x int32 }\n" +
                        "\n" +
                        "@deprecated\n" +
                        "service S {\n" +
                        "  #1 go(R): R\n" +
                        "}"
                )
                .file("t.proto")
                .services
                .single()
        assertEquals(emptyList(), s.notes)
        assertTrue(s.deprecated)
        assertEquals(emptyList(), s.rpcs.single().notes)
    }

    @Test
    fun `an rpc carries its ordinal unless both readings give it`() {
        fun notes(vararg ordinals: Int): List<List<String>> {
            val ops = ordinals.withIndex().joinToString("") { (i, n) -> "  #$n op${i + 1}(R): R\n" }
            return lower("schema t\n\nmodel R { #1 x int32 }\n\nservice S {\n$ops}")
                .file("t.proto")
                .services
                .single()
                .rpcs
                .map { it.notes }
        }
        assertEquals(
            listOf(emptyList(), listOf("#5"), listOf("#3"), emptyList()),
            notes(1, 5, 3, 4),
        )
        assertEquals(listOf(emptyList(), listOf("#3"), listOf("#4")), notes(1, 3, 4))
    }

    @Test
    fun `reserved ranges spell as the formatter prints them`() {
        val s =
            lower(
                    "schema t\n" +
                        "\n" +
                        "model R { #1 x int32 }\n" +
                        "\n" +
                        "service S {\n" +
                        "  #1 go(R): R  post \"/r/{x}\"\n" +
                        "  reserved #2..#4, #7, \"old\", \"older\"\n" +
                        "}"
                )
                .file("t.proto")
                .services
                .single()
        assertEquals(listOf("reserved #2..#4, #7, \"old\", \"older\""), s.notes)
        assertEquals(listOf("post \"/r/{x}\""), s.rpcs.single().notes)
    }

    @Test
    fun `a cross-namespace payload imports its file`() {
        val file =
            lower(
                    "schema a\n" +
                        "\n" +
                        "import b\n" +
                        "\n" +
                        "model R { #1 x int32 }\n" +
                        "\n" +
                        "service S {\n" +
                        "  #1 go(R): T\n" +
                        "}",
                    "schema b\n\nmodel T { #1 y int32 }\n",
                )
                .file("a.proto")
        assertEquals(listOf("b.proto"), file.imports)
        assertEquals(".b.T", file.services.single().rpcs.single().response.reference)
    }

    @Test
    fun `success codes and error bodies change no proto file`() {
        val problem = "schema b\n\nmodel Problem { #1 title string }\n"
        fun lowered(service: String): Lowered<ProtoModel> =
            lower("schema a\n\nmodel R { #1 x int32 }\n\n$service", problem)
        fun rendered(l: Lowered<ProtoModel>): Map<String, String> =
            ProtoRenderer.render(l.model).associate { it.path to it.content }
        val plain = lowered("service S {\n  #1 go(R): R  post \"/r\"\n}")
        val withErrors =
            lowered(
                "service S {\n  errors { 500 b.Problem }\n" +
                    "  #1 go(R): R  post \"/r\" 201  errors { 409 b.Problem }\n}"
            )
        assertEquals(rendered(plain), rendered(withErrors))
        assertEquals(emptyList(), withErrors.file("a.proto").imports)
    }

    @Test
    fun `a nested payload is spelled by its path`() {
        val rpc =
            lower(
                    "schema t\n" +
                        "\n" +
                        "model R {\n" +
                        "  #1 x int32\n" +
                        "\n" +
                        "  model Inner { #1 y int32 }\n" +
                        "}\n" +
                        "\n" +
                        "service S {\n" +
                        "  #1 go(R.Inner): R\n" +
                        "}"
                )
                .file("t.proto")
                .services
                .single()
                .rpcs
                .single()
        assertEquals("R.Inner", rpc.request.reference)
    }

    @Test
    fun `a payload whose first segment is an rpc name of the service is spelled absolutely`() {
        val lowered =
            lower(
                "schema shop\n" +
                    "\n" +
                    "model PlaceOrder { #1 x int32 }\n" +
                    "\n" +
                    "model Order {\n" +
                    "  #1 x int32\n" +
                    "\n" +
                    "  model Line { #1 y int32 }\n" +
                    "}\n" +
                    "\n" +
                    "model Receipt { #1 x int32 }\n" +
                    "\n" +
                    "service Orders {\n" +
                    "  #1 place_order(PlaceOrder): Receipt\n" +
                    "  #2 order(Order.Line): Order\n" +
                    "}"
            )
        val rpcs = lowered.file("shop.proto").services.single().rpcs
        assertEquals(".shop.PlaceOrder", rpcs[0].request.reference)
        assertEquals("Receipt", rpcs[0].response.reference)
        assertEquals(".shop.Order.Line", rpcs[1].request.reference)
        assertEquals(".shop.Order", rpcs[1].response.reference)
        val outs = ProtoRenderer.render(lowered.model)
        assertNull(Protoc.compile(outs.associate { it.path to it.content }))
    }

    @Test
    fun `a compound payload is spelled from the package only when its head is an rpc name`() {
        val lowered =
            lower(
                "schema shop\n" +
                    "\n" +
                    "model Order {\n" +
                    "  #1 x int32\n" +
                    "\n" +
                    "  model Line { #1 y int32 }\n" +
                    "}\n" +
                    "\n" +
                    "model Other {\n" +
                    "  #1 x int32\n" +
                    "\n" +
                    "  model Line { #1 y int32 }\n" +
                    "}\n" +
                    "\n" +
                    "service Orders {\n" +
                    "  #1 order(Order.Line): Other.Line\n" +
                    "}"
            )
        val rpc = lowered.file("shop.proto").services.single().rpcs.single()
        assertEquals(".shop.Order.Line", rpc.request.reference)
        assertEquals("Other.Line", rpc.response.reference)
    }

    @Test
    fun `a schema without services imports no Empty`() {
        val file = lower("schema t\n\nmodel R { #1 x int32 }").file("t.proto")
        assertEquals(emptyList(), file.imports)
        assertEquals(emptyList(), file.services)
    }

    @Test
    fun `a service whose rpcs all carry both payloads imports no Empty`() {
        val file =
            lower(
                    "schema t\n" +
                        "\n" +
                        "model R { #1 x int32 }\n" +
                        "\n" +
                        "service S {\n" +
                        "  #1 get(R): R\n" +
                        "  #2 put(R): R\n" +
                        "}"
                )
                .file("t.proto")
        assertEquals(emptyList(), file.imports)
    }

    @Test
    fun `Empty is spelled absolutely so a google package cannot capture it`() {
        val lowered =
            lower(
                "schema acme.google\n" +
                    "\n" +
                    "model R { #1 x int32 }\n" +
                    "\n" +
                    "service S {\n" +
                    "  #1 ping(): R\n" +
                    "}"
            )
        val rpc = lowered.file("acme/google.proto").services.single().rpcs.single()
        assertEquals(".google.protobuf.Empty", rpc.request.reference)
        val outs = ProtoRenderer.render(lowered.model)
        assertNull(Protoc.compile(outs.associate { it.path to it.content }))
    }

    @Test
    fun `an rpc override that repeats a derived name collides`() {
        val out =
            lower(
                "schema t\n" +
                    "\n" +
                    "model R { #1 x int32 }\n" +
                    "\n" +
                    "service S {\n" +
                    "  #1 get(R): R\n" +
                    "  @proto(name: \"Get\") #2 fetch(R): R\n" +
                    "}"
            )
        assertEquals(
            listOf("SCH2004 proto name 'Get' is already used by operation 'get' (t.schemata:6)"),
            out.codes(),
        )
    }

    @Test
    fun `rpc names collide after casing`() {
        val out =
            lower(
                "schema t\n" +
                    "\n" +
                    "model R { #1 x int32 }\n" +
                    "\n" +
                    "service S {\n" +
                    "  #1 get_v2(R): R\n" +
                    "  #2 get_v_2(R): R\n" +
                    "}"
            )
        assertEquals(
            listOf(
                "SCH2004 proto name 'GetV2' is already used by operation 'get_v2' (t.schemata:6)"
            ),
            out.codes(),
        )
    }

    @Test
    fun `rpc names in different services do not collide`() {
        val out =
            lower(
                "schema t\n" +
                    "\n" +
                    "model R { #1 x int32 }\n" +
                    "\n" +
                    "service S {\n" +
                    "  #1 get(R): R\n" +
                    "}\n" +
                    "\n" +
                    "service U {\n" +
                    "  #1 get(R): R\n" +
                    "}"
            )
        assertEquals(emptyList(), out.codes())
    }

    @Test
    fun `overrides name services and rpcs`() {
        val file =
            lower(
                    "schema t\n" +
                        "\n" +
                        "model R { #1 x int32 }\n" +
                        "\n" +
                        "@proto(name: \"OrderApi\")\n" +
                        "service S {\n" +
                        "  @proto(name: \"Fetch\") #1 get(R): R\n" +
                        "}"
                )
                .file("t.proto")
        assertEquals("OrderApi", file.services.single().name)
        assertEquals("Fetch", file.services.single().rpcs.single().name)
        val bad =
            lower(
                "schema t\n" +
                    "\n" +
                    "model R { #1 x int32 }\n" +
                    "\n" +
                    "service S {\n" +
                    "  @proto(name: \"1x\") #1 get(R): R\n" +
                    "}"
            )
        assertEquals(
            listOf("SCH2007 operation 'S.get': @proto(name: \"1x\") is not a valid identifier"),
            bad.codes(),
        )
        assertEquals("Get", bad.file("t.proto").services.single().rpcs.single().name)
        val badService =
            lower(
                "schema t\n" +
                    "\n" +
                    "model R { #1 x int32 }\n" +
                    "\n" +
                    "@proto(name: \"a b\")\n" +
                    "service S {\n" +
                    "  #1 get(R): R\n" +
                    "}"
            )
        assertEquals(
            listOf("SCH2007 service 'S': @proto(name: \"a b\") is not a valid identifier"),
            badService.codes(),
        )
        assertEquals("S", badService.file("t.proto").services.single().name)
    }

    @Test
    fun `a record named Empty does not clash with the well-known type`() {
        val file =
            lower(
                    "schema t\n" +
                        "\n" +
                        "model Empty { #1 x int32 }\n" +
                        "\n" +
                        "service S {\n" +
                        "  #1 ping()\n" +
                        "  #2 take(Empty)\n" +
                        "}"
                )
                .file("t.proto")
        assertEquals("Empty", file.declarations.single().name)
        assertEquals(".google.protobuf.Empty", file.services.single().rpcs[0].request.reference)
        assertEquals("Empty", file.services.single().rpcs[1].request.reference)
        assertEquals(listOf("google/protobuf/empty.proto"), file.imports)
    }

    @Test
    fun `a service name collides with a message`() {
        val out =
            lower(
                "schema t\n" +
                    "\n" +
                    "model R { #1 x int32 }\n" +
                    "\n" +
                    "@proto(name: \"R\")\n" +
                    "service S {\n" +
                    "  #1 get(R): R\n" +
                    "}"
            )
        assertEquals(
            listOf("SCH2004 proto name 'R' is already used by model 'R' (t.schemata:3)"),
            out.codes(),
        )
    }

    private fun shape(message: ProtoMessage) =
        message.fields.map { listOf(it.number, it.name, it.type, it.label) }

    @Test
    fun `a reference to a keyed model emits its key`() {
        val order = message(lower(RELATIONS).file("shop.proto"), "Order")
        assertEquals(
            listOf(
                listOf(2, "customer_id", ProtoType.Scalar("string"), Label.NONE),
                listOf(4, "tags", ProtoType.Scalar("string"), Label.REPEATED),
                listOf(7, "backup_id", ProtoType.Scalar("string"), Label.OPTIONAL),
            ),
            shape(order).filter { it[0] in setOf(2, 4, 7) },
        )
    }

    @Test
    fun `embed restores the record`() {
        val order = message(lower(RELATIONS).file("shop.proto"), "Order")
        assertEquals(
            listOf(3, "billing", ProtoType.Named("Customer"), Label.NONE),
            shape(order).single { it[0] == 3 },
        )
    }

    @Test
    fun `a composite-key reference emits one key object`() {
        val file = lower(RELATIONS).file("shop.proto")
        assertEquals(
            listOf("Customer", "Tag", "Pair", "PairKey", "Order"),
            file.declarations.map { it.name },
        )
        assertEquals(
            listOf(
                listOf(1, "a", ProtoType.Scalar("int32"), Label.NONE),
                listOf(2, "b", ProtoType.Scalar("int32"), Label.NONE),
            ),
            shape(message(file, "PairKey")),
        )
        assertEquals(
            listOf(
                listOf(5, "pair", ProtoType.Named("PairKey"), Label.NONE),
                listOf(6, "pairs", ProtoType.Named("PairKey"), Label.REPEATED),
            ),
            shape(message(file, "Order")).filter { it[0] in setOf(5, 6) },
        )
    }

    @Test
    fun `a key record in another package is imported and spelled absolutely`() {
        val file =
            lower(
                    "schema a\n\nmodel Pair { #1 x int32 { id }  #2 y int32 { id } }\n",
                    "schema b\n\nimport a\n\nmodel Use { #1 pair Pair }\n",
                )
                .file("b.proto")
        assertEquals(listOf("a.proto"), file.imports)
        assertEquals(
            listOf(listOf(1, "pair", ProtoType.Named(".a.PairKey"), Label.NONE)),
            shape(message(file, "Use")),
        )
    }

    @Test
    fun `a reference field that repeats a key's name collides`() {
        val out =
            lower("schema t\n\nmodel C { #1 id uuid { id } }\n\nmodel R { #1 c C  #2 c_id uuid }\n")
        assertEquals(
            listOf("SCH2004 proto name 'c_id' is already used by field 'c_id' (t.schemata:5)"),
            out.codes().filter { it.startsWith("SCH2004") },
        )
    }

    @Test
    fun `the target declares its annotation keys`() {
        assertEquals(
            listOf(
                "package" to setOf(Element.NAMESPACE),
                "name" to
                    setOf(
                        Element.RECORD,
                        Element.ENUM,
                        Element.UNION,
                        Element.FIELD,
                        Element.ENUM_VALUE,
                        Element.SERVICE,
                        Element.OPERATION,
                    ),
            ) + ProtoFileOptions.keys.map { it.name to setOf(Element.NAMESPACE) },
            ProtoTarget.annotationSpecs.map { it.key to it.elements },
        )
        assertTrue(ProtoTarget.annotationSpecs.all { it.target == "proto" })
        assertEquals(
            listOf("java_multiple_files"),
            ProtoTarget.annotationSpecs.filter { it.valueKind == ValueKind.BOOL }.map { it.key },
        )
    }

    @Test
    fun `a union member typed as a keyed model carries its key`() {
        val party = message(lower(MEMBERS).file("shop.proto"), "Party").oneofs.single()
        assertEquals(
            listOf(
                listOf(1, "customer", ProtoType.Scalar("string")),
                listOf(2, "pair", ProtoType.Named("PairKey")),
            ),
            party.fields.map { listOf(it.number, it.name, it.type) },
        )
    }

    @Test
    fun `a map value typed as a keyed model carries its key`() {
        val book = message(lower(MEMBERS).file("shop.proto"), "Book")
        val string = ProtoType.Scalar("string")
        assertEquals(
            listOf(
                listOf(2, "by", ProtoType.MapOf(string, string)),
                listOf(3, "pairs", ProtoType.MapOf(string, ProtoType.Named("PairKey"))),
            ),
            book.fields.filter { it.number in 2..3 }.map { listOf(it.number, it.name, it.type) },
        )
    }

    @Test
    fun `schemas in a reference cycle are written as one file under their common package`() {
        val lowered = lower(ALPHA, BETA)
        val file = lowered.model.files.single()
        assertEquals("cyc.proto" to "cyc", file.path to file.packageName)
        assertEquals(emptyList(), file.imports)
        assertEquals(listOf("A", "B"), file.declarations.map { it.name })
        assertEquals(ProtoType.Named("B"), message(file, "A").fields.single().type)
        assertEquals(ProtoType.Named("A"), message(file, "B").fields.single().type)
        assertNull(lowered.protocErrors())
    }

    @Test
    fun `a cycle reports the merge once at its first member`() {
        val lowered = lower(ALPHA, BETA)
        assertEquals(
            listOf(
                "cyc.alpha.schemata:1 SCH2001 schemas cyc.alpha and cyc.beta reference each other; " +
                    "Protobuf cannot import files in a cycle, so they are written as one file under package 'cyc'"
            ),
            lowered.located(),
        )
        assertEquals(
            "set `@proto(package: \"…\")` to one value on each of them to choose the package",
            lowered.diagnostics.single().help,
        )
    }

    @Test
    fun `three schemas in a cycle are listed with commas`() {
        val lowered =
            lower(
                "schema cyc.a\n\nimport cyc.b\n\nmodel A { #1 b B? }\n",
                "schema cyc.b\n\nimport cyc.c\n\nmodel B { #1 c C? }\n",
                "schema cyc.c\n\nimport cyc.a\n\nmodel C { #1 a A? }\n",
            )
        assertEquals(
            listOf(
                "SCH2001 schemas cyc.a, cyc.b and cyc.c reference each other; " +
                    "Protobuf cannot import files in a cycle, so they are written as one file under package 'cyc'"
            ),
            lowered.codes(),
        )
        assertEquals(listOf("cyc.proto"), lowered.model.files.map { it.path })
    }

    @Test
    fun `a schema outside the cycle imports the merged file and spells its package`() {
        val lowered = lower(ALPHA, BETA, "schema other\n\nimport cyc.beta\n\nmodel O { #1 b B }\n")
        val other = lowered.file("other.proto")
        assertEquals(listOf("cyc.proto"), other.imports)
        assertEquals(ProtoType.Named(".cyc.B"), message(other, "O").fields.single().type)
        assertNull(lowered.protocErrors())
    }

    @Test
    fun `services of every member follow all declarations`() {
        val lowered =
            lower(
                ALPHA + "\nservice Alphas {\n  #1 get(A): B\n}\n",
                BETA + "\nservice Betas {\n  #1 get(B): A\n}\n",
            )
        val file = lowered.file("cyc.proto")
        assertEquals(listOf("A", "B"), file.declarations.map { it.name })
        assertEquals(listOf("Alphas", "Betas"), file.services.map { it.name })
        val get = file.services.first().rpcs.single()
        assertEquals("Get" to "A", get.name to get.request.reference)
        assertEquals("B", get.response.reference)
        val text = ProtoRenderer.render(lowered.model).single().content
        assertTrue(text.contains("rpc Get(A) returns (B)"), text)
        assertTrue(text.indexOf("message B") < text.indexOf("service Alphas"), text)
        assertNull(lowered.protocErrors())
    }

    @Test
    fun `a nested declaration that shadows a member's type forces the absolute spelling`() {
        val lowered =
            lower(
                "schema cyc.alpha\n" +
                    "\n" +
                    "import cyc.beta as beta\n" +
                    "\n" +
                    "model A {\n" +
                    "  #1 b     beta.B?\n" +
                    "  #2 inner B\n" +
                    "\n" +
                    "  model B { #1 x int32 }\n" +
                    "}\n",
                BETA,
            )
        val a = message(lowered.file("cyc.proto"), "A")
        assertEquals(
            listOf(ProtoType.Named(".cyc.B"), ProtoType.Named("B")),
            a.fields.map { it.type },
        )
        assertNull(lowered.protocErrors())
    }

    @Test
    fun `a composite key from another member of the cycle is spelled relatively`() {
        val lowered =
            lower(
                "schema cyc.alpha\n\nimport cyc.beta\n\nmodel A { #1 pair Pair }\n",
                "schema cyc.beta\n" +
                    "\n" +
                    "import cyc.alpha\n" +
                    "\n" +
                    "model Pair { #1 a int32 { id }  #2 b int32 { id }  #3 owner A? }\n",
            )
        val file = lowered.file("cyc.proto")
        assertEquals(listOf("A", "Pair", "PairKey"), file.declarations.map { it.name })
        assertEquals(ProtoType.Named("PairKey"), message(file, "A").fields.single().type)
        assertNull(lowered.protocErrors())
    }

    @Test
    fun `one declared package names the merged file without a warning`() {
        val lowered =
            lower(
                ALPHA.replace("schema cyc.alpha", "schema cyc.alpha @proto(package: \"shop.v1\")"),
                BETA,
            )
        val file = lowered.model.files.single()
        assertEquals("shop/v1.proto" to "shop.v1", file.path to file.packageName)
        assertEquals(emptyList(), lowered.codes())
    }

    @Test
    fun `members declaring one package are not a package collision`() {
        val lowered =
            lower(
                ALPHA.replace("schema cyc.alpha", "schema cyc.alpha @proto(package: \"shop.v1\")"),
                BETA.replace("schema cyc.beta", "schema cyc.beta @proto(package: \"shop.v1\")"),
            )
        assertEquals(emptyList(), lowered.codes())
        assertEquals(listOf("shop/v1.proto"), lowered.model.files.map { it.path })
    }

    @Test
    fun `conflicting declared packages in a cycle are an error`() {
        val schema =
            analysed(
                ALPHA.replace("schema cyc.alpha", "schema cyc.alpha @proto(package: \"p.one\")"),
                BETA.replace("schema cyc.beta", "schema cyc.beta @proto(package: \"p.two\")"),
            )
        val lowered = ProtoLowering.lower(schema)
        assertEquals(
            listOf(
                "cyc.beta.schemata:1 SCH2007 schemas cyc.alpha and cyc.beta reference each other " +
                    "but declare packages 'p.one' and 'p.two'"
            ),
            lowered.located(),
        )
        assertEquals(
            "give every schema in the cycle the same `@proto(package: \"…\")`",
            lowered.diagnostics.single().help,
        )
        assertEquals(emptyList(), ProtoTarget.compile(schema).files)
    }

    @Test
    fun `a cycle's package colliding with another schema's package is an error`() {
        val lowered =
            lower(
                ALPHA.replace("schema cyc.alpha", "schema cyc.alpha @proto(package: \"cyc\")"),
                BETA,
                "schema x @proto(package: \"cyc\")\n\nmodel X { #1 y int32 }\n",
            )
        assertEquals(
            listOf("x.schemata:1 SCH2004 schemas cyc.alpha and x both lower to package 'cyc'"),
            lowered.located(),
        )
    }

    @Test
    fun `a derived package avoids another schema's declared package`() {
        val lowered =
            lower(ALPHA, BETA, "schema x @proto(package: \"cyc\")\n\nmodel X { #1 y int32 }\n")
        assertEquals(
            listOf(
                "cyc.alpha.schemata:1 SCH2001 schemas cyc.alpha and cyc.beta reference each other; " +
                    "Protobuf cannot import files in a cycle, so they are written as one file under package 'cyc.alpha'"
            ),
            lowered.located(),
        )
        assertNull(lowered.protocErrors())
    }

    @Test
    fun `files follow the name of their first schema`() {
        val lowered =
            lower(
                "schema a\n\nimport n\n\nmodel A { #1 n N? }\n",
                "schema m\n\nmodel M { #1 x int32 }\n",
                "schema n\n\nimport a\n\nmodel N { #1 a A? }\n",
                "schema z\n\nmodel Z { #1 x int32 }\n",
            )
        assertEquals(listOf("a.proto", "m.proto", "z.proto"), lowered.model.files.map { it.path })
        assertEquals(listOf("A", "N"), lowered.file("a.proto").declarations.map { it.name })
    }

    @Test
    fun `a cycle broken by a single key writes one file per schema`() {
        val lowered =
            lower(
                "schema cyc.alpha\n\nimport cyc.beta\n\nmodel A { #1 id uuid { id }  #2 b B? }\n",
                "schema cyc.beta\n\nimport cyc.alpha\n\nmodel B { #1 id uuid { id }  #2 a A? }\n",
            )
        assertEquals(
            listOf("cyc/alpha.proto", "cyc/beta.proto"),
            lowered.model.files.map { it.path },
        )
        assertEquals(
            listOf(emptyList<String>(), emptyList()),
            lowered.model.files.map { it.imports },
        )
        assertEquals(emptyList(), lowered.codes().filter { "but declare" in it })
        assertNull(lowered.protocErrors())
    }

    @Test
    fun `a name two members of a cycle declare is prefixed in the later member`() {
        val lowered = lower(NIEM, UC2)
        val file = lowered.file("niem_core.proto")
        assertEquals(listOf("Task", "Uc2SystemTaskTask"), file.declarations.map { it.name })
        assertEquals(
            "uc2_system_task.schemata:5 SCH2001 model 'uc2_system_task.Task': proto name 'Task' " +
                "is also used by model 'niem_core.Task'; written as 'Uc2SystemTaskTask'",
            lowered.located().last(),
        )
        assertEquals(
            "set `@proto(name: \"…\")` on one of them to choose the name",
            lowered.diagnostics.last().help,
        )
        assertEquals(emptyList(), lowered.codes().filter { it.startsWith("SCH2004") })
    }

    @Test
    fun `references from any file spell the renamed declaration`() {
        val lowered =
            lower(
                NIEM,
                UC2,
                "schema other\n\nimport uc2_system_task as uc2\n\nmodel O { #1 t uc2.Task }\n",
            )
        val merged = lowered.file("niem_core.proto")
        assertEquals(
            ProtoType.Named("Uc2SystemTaskTask"),
            message(merged, "Task").fields.single().type,
        )
        assertEquals(
            ProtoType.Named("Task"),
            message(merged, "Uc2SystemTaskTask").fields.single().type,
        )
        val other = lowered.file("other.proto")
        assertEquals(listOf("niem_core.proto"), other.imports)
        assertEquals(
            ProtoType.Named(".niem_core.Uc2SystemTaskTask"),
            message(other, "O").fields.single().type,
        )
        assertNull(lowered.protocErrors())
    }

    @Test
    fun `a renamed enum prefixes its values with its new name`() {
        val lowered =
            lower(
                "schema niem_core\n" +
                    "\n" +
                    "import uc2_system_task as uc2\n" +
                    "\n" +
                    "enum Status { #1 open }\n" +
                    "\n" +
                    "model Task { #1 status uc2.Status }\n",
                "schema uc2_system_task\n" +
                    "\n" +
                    "import niem_core as niem\n" +
                    "\n" +
                    "enum Status { #1 open }\n" +
                    "\n" +
                    "model Job { #1 task niem.Task? }\n",
            )
        val file = lowered.file("niem_core.proto")
        val renamed = file.declarations.first { it.name == "Uc2SystemTaskStatus" } as ProtoEnum
        assertEquals(
            listOf("UC2_SYSTEM_TASK_STATUS_UNSPECIFIED", "UC2_SYSTEM_TASK_STATUS_OPEN"),
            renamed.values.map { it.name },
        )
        assertEquals(
            ProtoType.Named("Uc2SystemTaskStatus"),
            message(file, "Task").fields.single().type,
        )
        assertEquals(emptyList(), lowered.codes().filter { it.startsWith("SCH2004") })
        assertNull(lowered.protocErrors())
    }

    @Test
    fun `a union member named for a renamed model takes the new stem`() {
        val lowered =
            lower(
                "schema niem_core\n" +
                    "\n" +
                    "import uc2_system_task as uc2\n" +
                    "\n" +
                    "model Task { #1 x int32 }\n" +
                    "\n" +
                    "union Work = Task | uc2.Task\n",
                "schema uc2_system_task\n" +
                    "\n" +
                    "import niem_core as niem\n" +
                    "\n" +
                    "model Task { #1 work niem.Work? }\n",
            )
        val work = message(lowered.file("niem_core.proto"), "Work")
        assertEquals(
            listOf(
                "task" to ProtoType.Named("Task"),
                "uc2_system_task_task" to ProtoType.Named("Uc2SystemTaskTask"),
            ),
            work.oneofs.single().fields.map { it.name to it.type },
        )
        assertNull(lowered.protocErrors())
    }

    @Test
    fun `a declared proto name is never renamed`() {
        val overridden =
            lower(
                "schema cyc.alpha\n\nimport cyc.beta\n\nmodel Task { #1 j Job? }\n",
                "schema cyc.beta\n" +
                    "\n" +
                    "import cyc.alpha as alpha\n" +
                    "\n" +
                    "@proto(name: \"Task\")\n" +
                    "model Job { #1 t alpha.Task? }\n",
            )
        assertEquals(
            listOf("CycAlphaTask", "Task"),
            overridden.file("cyc.proto").declarations.map { it.name },
        )
        assertEquals(
            "cyc.alpha.schemata:5 SCH2001 model 'cyc.alpha.Task': proto name 'Task' " +
                "is also used by model 'cyc.beta.Job'; written as 'CycAlphaTask'",
            overridden.located().last(),
        )
        assertNull(overridden.protocErrors())
        val service =
            lower(
                "schema cyc.alpha\n\nimport cyc.beta\n\nmodel Task { #1 b B? }\n",
                BETA.replace("import cyc.alpha", "import cyc.alpha as alpha")
                    .replace("A?", "alpha.Task?") + "\nservice Task {\n  #1 get(B): B\n}\n",
            )
        val file = service.file("cyc.proto")
        assertEquals(listOf("CycAlphaTask", "B"), file.declarations.map { it.name })
        assertEquals(listOf("Task"), file.services.map { it.name })
        assertEquals(
            "cyc.alpha.schemata:5 SCH2001 model 'cyc.alpha.Task': proto name 'Task' " +
                "is also used by service 'cyc.beta.Task'; written as 'CycAlphaTask'",
            service.located().last(),
        )
        assertNull(service.protocErrors())
    }

    @Test
    fun `two declared proto names that collide are still a name collision`() {
        val lowered =
            lower(
                "schema cyc.alpha\n\nimport cyc.beta\n\n@proto(name: \"Same\")\nmodel A { #1 b B? }\n",
                "schema cyc.beta\n\nimport cyc.alpha\n\n@proto(name: \"Same\")\nmodel B { #1 a A? }\n",
            )
        assertEquals(
            listOf(
                "cyc.beta.schemata:6 SCH2004 proto name 'Same' is already used by model 'A' (cyc.alpha.schemata:6)"
            ),
            lowered.located().filter { "reference each other" !in it },
        )
    }

    @Test
    fun `a collision within one member is not renamed`() {
        val lowered =
            lower(
                "schema cyc.alpha\n" +
                    "\n" +
                    "import cyc.beta\n" +
                    "\n" +
                    "model A { #1 b B? }\n" +
                    "\n" +
                    "@proto(name: \"A\")\n" +
                    "model Twin { #1 x int32 }\n",
                BETA,
            )
        assertEquals(
            listOf("SCH2004 proto name 'A' is already used by model 'A' (cyc.alpha.schemata:5)"),
            lowered.codes().filter { "reference each other" !in it },
        )
    }

    @Test
    fun `a rename that would collide again stays a name collision`() {
        val lowered =
            lower(
                NIEM.replace(
                    "uc2.Task? }",
                    "uc2.Task? }\n\nmodel Uc2SystemTaskTask { #1 x int32 }",
                ),
                UC2,
            )
        assertEquals(
            listOf(
                "uc2_system_task.schemata:5 SCH2004 proto name 'Task' is already used by model 'Task' (niem_core.schemata:5)"
            ),
            lowered.located().filter { "reference each other" !in it },
        )
    }

    @Test
    fun `a note on a field of a renamed type names the new name`() {
        val file = lower(NIEM, UC2).file("niem_core.proto")
        assertEquals(listOf("Uc2SystemTaskTask?"), message(file, "Task").fields.single().notes)
        assertEquals(listOf("Task?"), message(file, "Uc2SystemTaskTask").fields.single().notes)
    }

    @Test
    fun `a note names a declaration by its proto name`() {
        val file =
            lower(
                    "schema t\n" +
                        "\n" +
                        "@proto(name: \"Wire\")\n" +
                        "model R { #1 x int32 }\n" +
                        "\n" +
                        "model S { #1 r R?  #2 rs R[] { minItems 1 } }\n"
                )
                .file("t.proto")
        assertEquals(
            listOf(listOf("Wire?"), listOf("Wire[] { minItems 1 }")),
            message(file, "S").fields.map { it.notes },
        )
    }

    @Test
    fun `a renamed key record is reported as its model's key`() {
        val pair = "model Pair { #1 x int32 { id }  #2 y int32 { id } }\n"
        val lowered =
            lower(
                "schema cyc.alpha\n\nimport cyc.beta as beta\n\n$pair\nmodel A { #1 p Pair  #2 b beta.B? }\n",
                "schema cyc.beta\n\nimport cyc.alpha as alpha\n\n$pair\nmodel B { #1 p Pair  #2 a alpha.A? }\n",
            )
        assertEquals(
            "cyc.beta.schemata:5 SCH2001 the key of model 'cyc.beta.Pair': proto name 'PairKey' " +
                "is also used by the key of model 'cyc.alpha.Pair'; written as 'CycBetaPairKey'",
            lowered.located().last(),
        )
        assertEquals(
            "set `@proto(name: \"…\")` on one of the models to choose the name",
            lowered.diagnostics.last().help,
        )
        assertNull(lowered.protocErrors())
    }

    @Test
    fun `the renamed file compiles under protoc`() {
        val lowered =
            lower(
                "schema niem_core\n" +
                    "\n" +
                    "import uc2_system_task as uc2\n" +
                    "\n" +
                    "enum Status { #1 open }\n" +
                    "\n" +
                    "model Task { #1 sub uc2.Task?  #2 status uc2.Status  #3 work Work }\n" +
                    "\n" +
                    "union Work = Task | uc2.Task\n" +
                    "\n" +
                    "service Tasks {\n  #1 get(uc2.Task): Task\n}\n",
                "schema uc2_system_task\n" +
                    "\n" +
                    "import niem_core as niem\n" +
                    "\n" +
                    "enum Status { #1 open  #2 closed }\n" +
                    "\n" +
                    "model Task { #1 parent niem.Task?  #2 status Status }\n" +
                    "\n" +
                    "service Jobs {\n  #1 get(niem.Task): Task\n}\n",
                "schema other\n\nimport uc2_system_task as uc2\n\nmodel O { #1 t uc2.Task  #2 s uc2.Status }\n",
            )
        assertEquals(
            listOf("Uc2SystemTaskStatus", "Uc2SystemTaskTask"),
            lowered
                .codes()
                .filter { "; written as '" in it }
                .map { it.substringAfter("written as '").removeSuffix("'") },
        )
        assertNull(lowered.protocErrors())
    }

    /** A schema `shop.orders` with two models, its schema line carrying [attributes]. */
    private fun orders(attributes: String) =
        "schema shop.orders $attributes\n\nmodel Status { #1 open bool }\n\nmodel Order { #1 status Status }\n"

    @Test
    fun `language options are written as file options in a fixed order`() {
        val lowered =
            lower(
                orders(
                    "@proto(go_package: \"example.com/orders\") " +
                        "@proto(java_package: \"com.example.orders\") " +
                        "@proto(java_multiple_files: true)"
                )
            )
        assertEquals(emptyList(), lowered.codes())
        assertEquals(
            listOf(
                "java_package" to "\"com.example.orders\"",
                "java_multiple_files" to "true",
                "go_package" to "\"example.com/orders\"",
            ),
            lowered.model.files.single().options,
        )
        assertNull(lowered.protocErrors())
    }

    @Test
    fun `a schema without language options writes none`() {
        val lowered = lower(orders(""))
        assertEquals(emptyList(), lowered.model.files.single().options)
    }

    @Test
    fun `an invalid language option is reported at its schema`() {
        val dotted = "use dotted identifiers, for example"
        val single = "use a single identifier, for example"
        val php =
            "is not a valid PHP namespace" to
                "use identifiers separated by backslashes, for example `\"Example\\\\Orders\"`"
        val rows =
            listOf(
                Triple(
                    "java_package",
                    "com.example-orders",
                    "is not a valid Java package" to "$dotted `com.example.orders`",
                ),
                Triple(
                    "java_outer_classname",
                    "Orders.Proto",
                    "is not a valid Java class name" to "$single `OrdersProto`",
                ),
                Triple(
                    "csharp_namespace",
                    "Example..Orders",
                    "is not a valid C# namespace" to "$dotted `Example.Orders`",
                ),
                Triple(
                    "go_package",
                    "",
                    "is empty or holds a control character" to
                        "give the Go import path, for example `example.com/orders;orderspb`",
                ),
                Triple(
                    "objc_class_prefix",
                    "EX O",
                    "is not a valid Objective-C class prefix" to "$single `EXO`",
                ),
                Triple("php_namespace", "Example/Orders", php),
                Triple(
                    "php_class_prefix",
                    "Exo-",
                    "is not a valid PHP class prefix" to "$single `Exo`",
                ),
                Triple("php_metadata_namespace", "Example.Orders", php),
                Triple(
                    "ruby_package",
                    "Example:Orders",
                    "is not a valid Ruby module" to
                        "use identifiers separated by `::`, for example `Example::Orders`",
                ),
                Triple("swift_prefix", "1EXO", "is not a valid Swift prefix" to "$single `EXO`"),
            )
        assertEquals(
            ProtoFileOptions.keys.filter { !it.bool }.map { it.name },
            rows.map { it.first },
        )
        for ((key, value, expected) in rows) {
            val (tail, help) = expected
            val lowered = lower(orders("@proto($key: ${SchemataText.string(value)})"))
            assertEquals(
                listOf(
                    "shop.orders.schemata:1 SCH2007 schema 'shop.orders': @proto($key: \"$value\") $tail"
                ),
                lowered.located(),
            )
            assertEquals(help, lowered.diagnostics.single().help)
        }
    }

    @Test
    fun `a control character in an option value is escaped in the message`() {
        val lowered = lower(orders("@proto(go_package: \"example.com/\\torders\\n\")"))
        assertEquals(
            listOf(
                "SCH2007 schema 'shop.orders': @proto(go_package: \"example.com/\\torders\\n\") " +
                    "is empty or holds a control character"
            ),
            lowered.codes(),
        )
    }

    @Test
    fun `an empty namespace or prefix option is written as an empty string`() {
        val lowered = lower(orders("@proto(csharp_namespace: \"\") @proto(swift_prefix: \"\")"))
        assertEquals(emptyList(), lowered.codes())
        assertEquals(
            listOf("csharp_namespace" to "\"\"", "swift_prefix" to "\"\""),
            lowered.model.files.single().options,
        )
        assertNull(lowered.protocErrors())
    }

    @Test
    fun `a conflict value with a control character is escaped in the message`() {
        val lowered =
            lower(
                ALPHA.replace(
                    "schema cyc.alpha",
                    "schema cyc.alpha @proto(go_package: \"example.com/a\\nb\")",
                ),
                BETA.replace(
                    "schema cyc.beta",
                    "schema cyc.beta @proto(go_package: \"example.com/c\")",
                ),
            )
        assertEquals(
            listOf(
                "SCH2007 schemas cyc.alpha and cyc.beta reference each other but declare " +
                    "go_package 'example.com/a\\nb' and 'example.com/c'"
            ),
            lowered.codes().filter { "but declare" in it },
        )
    }

    @Test
    fun `one cycle member declaring java_package gives the merged file its value`() {
        val lowered =
            lower(
                ALPHA.replace(
                    "schema cyc.alpha",
                    "schema cyc.alpha @proto(package: \"cyc\") @proto(java_package: \"com.example.cyc\")",
                ),
                BETA.replace("schema cyc.beta", "schema cyc.beta @proto(package: \"cyc\")"),
            )
        assertEquals(emptyList(), lowered.codes())
        assertEquals(
            listOf("java_package" to "\"com.example.cyc\""),
            lowered.model.files.single().options,
        )
        assertNull(lowered.protocErrors())
    }

    @Test
    fun `cycle members declaring the same option do not conflict`() {
        val option = "@proto(package: \"cyc\") @proto(csharp_namespace: \"Example.Cyc\")"
        val lowered =
            lower(
                ALPHA.replace("schema cyc.alpha", "schema cyc.alpha $option"),
                BETA.replace("schema cyc.beta", "schema cyc.beta $option"),
            )
        assertEquals(emptyList(), lowered.codes())
        assertEquals(
            listOf("csharp_namespace" to "\"Example.Cyc\""),
            lowered.model.files.single().options,
        )
    }

    @Test
    fun `cycle members declaring different java_package values are an error at the later member`() {
        val schema =
            analysed(
                ALPHA.replace(
                    "schema cyc.alpha",
                    "schema cyc.alpha @proto(java_package: \"com.example.one\")",
                ),
                BETA.replace(
                    "schema cyc.beta",
                    "schema cyc.beta @proto(java_package: \"com.example.two\")",
                ),
            )
        val lowered = ProtoLowering.lower(schema)
        val conflict =
            "cyc.beta.schemata:1 SCH2007 schemas cyc.alpha and cyc.beta reference each other " +
                "but declare java_package 'com.example.one' and 'com.example.two'"
        assertEquals(listOf(conflict), lowered.located().filter { "SCH2007" in it })
        assertEquals(
            "give every schema in the cycle the same `@proto(java_package: \"…\")`",
            lowered.diagnostics.single { "SCH2007" in it.code.id }.help,
        )
        assertEquals(emptyList(), ProtoTarget.compile(schema).files)
    }

    @Test
    fun `a cycle conflict on java_multiple_files names both values`() {
        val lowered =
            lower(
                ALPHA.replace(
                    "schema cyc.alpha",
                    "schema cyc.alpha @proto(java_multiple_files: true)",
                ),
                BETA.replace(
                    "schema cyc.beta",
                    "schema cyc.beta @proto(java_multiple_files: false)",
                ),
            )
        val conflict = lowered.diagnostics.single { it.code.id == "SCH2007" }
        assertEquals(
            "schemas cyc.alpha and cyc.beta reference each other " +
                "but declare java_multiple_files 'true' and 'false'",
            conflict.message,
        )
        assertEquals(
            "give every schema in the cycle the same `@proto(java_multiple_files: …)`",
            conflict.help,
        )
    }

    /** Renders [lowered] and runs protoc's [language] generator over it into a fresh directory. */
    private fun generated(lowered: Lowered<ProtoModel>, language: String, check: (File) -> Unit) {
        val out = Files.createTempDirectory("schemata-generated").toFile()
        try {
            val files = ProtoRenderer.render(lowered.model).associate { it.path to it.content }
            assertNull(Protoc.generate(files, language, out))
            check(out)
        } finally {
            out.deleteRecursively()
        }
    }

    @Test
    fun `java_multiple_files writes each message to its own class under the java_package directory`() {
        val lowered =
            lower(
                orders(
                    "@proto(java_package: \"com.example.orders\") @proto(java_multiple_files: true)"
                )
            )
        assertEquals(emptyList(), lowered.codes())
        generated(lowered, "java") { out ->
            assertTrue(
                File(out, "com/example/orders/Order.java").isFile,
                out.walk().toList().toString(),
            )
            assertTrue(
                File(out, "com/example/orders/Status.java").isFile,
                out.walk().toList().toString(),
            )
        }
    }

    @Test
    fun `csharp_namespace names the namespace of the generated C# file`() {
        val lowered = lower(orders("@proto(csharp_namespace: \"Example.Orders\")"))
        assertEquals(emptyList(), lowered.codes())
        generated(lowered, "csharp") { out ->
            val cs = File(out, "Orders.cs")
            assertTrue(cs.isFile, out.walk().toList().toString())
            assertTrue("namespace Example.Orders" in cs.readText(), cs.readText())
        }
    }

    private fun lowerTypes(vararg sources: String) =
        ProtoLowering.lower(analysed(*sources), ProtoLayout.TYPE)

    /** The `shop.orders` schema holding [body]. */
    private fun shop(body: String) = "schema shop.orders\n\n$body"

    @Test
    fun `the schema layout is the default and unchanged`() {
        for (sources in listOf(arrayOf(ALPHA, BETA), arrayOf(SERVICE, CATALOG))) {
            val schema = analysed(*sources)
            val default = ProtoLowering.lower(schema)
            val explicit = ProtoLowering.lower(schema, ProtoLayout.SCHEMA)
            assertEquals(default.model, explicit.model)
            assertEquals(default.codes(), explicit.codes())
            assertEquals(
                ProtoTarget.compile(schema).files,
                ProtoTarget(ProtoLayout.SCHEMA).compile(schema).files,
            )
        }
    }

    @Test
    fun `the type layout writes one file per top-level declaration under the package directory`() {
        val source =
            shop("model Order { #1 id uuid  #2 status Status }\n\nenum Status { #1 placed }\n")
        val lowered = lowerTypes(source)
        assertEquals(
            listOf("shop/orders/order.proto", "shop/orders/status.proto"),
            lowered.model.files.map { it.path },
        )
        assertEquals(listOf("shop.orders"), lowered.model.files.map { it.packageName }.distinct())
        val order = lowered.file("shop/orders/order.proto")
        assertEquals(listOf("shop/orders/status.proto"), order.imports)
        assertEquals(ProtoType.Named("Status"), message(order, "Order").fields[1].type)
        assertEquals(emptyList(), lowered.file("shop/orders/status.proto").imports)
        // The layout adds nothing to what the schema layout reports about these declarations.
        assertEquals(lower(source).codes(), lowered.codes())
        assertNull(lowered.protocErrors())
    }

    @Test
    fun `a declared package names the directory`() {
        val lowered =
            lowerTypes(
                "schema shop.orders @proto(package: \"shop.orders.v1\")\n\nmodel Order { #1 n int32 }\n"
            )
        assertEquals(listOf("shop/orders/v1/order.proto"), lowered.model.files.map { it.path })
        assertNull(lowered.protocErrors())
    }

    @Test
    fun `a file is named by the emitted name`() {
        assertEquals(
            listOf("shop/orders/http_status.proto"),
            lowerTypes(shop("model HTTPStatus { #1 n int32 }\n")).model.files.map { it.path },
        )
        assertEquals(
            listOf("shop/orders/purchase_order.proto"),
            lowerTypes(
                    shop("model Order {\n  #1 n int32\n\n  @@proto(name: \"PurchaseOrder\")\n}\n")
                )
                .model
                .files
                .map { it.path },
        )
    }

    @Test
    fun `nested declarations stay in their parent's file`() {
        val lowered =
            lowerTypes(shop("model Order {\n  #1 line Line\n\n  model Line { #1 n int32 }\n}\n"))
        val file = lowered.model.files.single()
        assertEquals("shop/orders/order.proto", file.path)
        assertEquals(listOf("Line"), message(file, "Order").nested.map { it.name })
        assertNull(lowered.protocErrors())
    }

    @Test
    fun `declarations that reference each other share a file named after the first`() {
        val lowered =
            lowerTypes(
                shop("model Order { #1 customer Customer? }\n\nmodel Customer { #1 last Order? }\n")
            )
        val file = lowered.model.files.single()
        assertEquals("shop/orders/customer.proto", file.path)
        assertEquals(listOf("Order", "Customer"), file.declarations.map { it.name })
        assertEquals(
            listOf(
                "shop.orders.schemata:5 SCH2001 model 'Customer' and model 'Order' reference each other; " +
                    "Protobuf cannot import files in a cycle, so they share shop/orders/customer.proto"
            ),
            lowered.located(),
        )
        assertEquals(
            "set `@proto(name: \"…\")` on 'Customer' to name the file",
            lowered.diagnostics.single().help,
        )
        assertNull(lowered.protocErrors())
    }

    @Test
    fun `three declarations in a cycle are listed with commas`() {
        val lowered =
            lowerTypes(shop("model A { #1 b B? }\n\nmodel B { #1 c C? }\n\nmodel C { #1 a A? }\n"))
        assertEquals(
            listOf(
                "SCH2001 model 'A', model 'B' and model 'C' reference each other; " +
                    "Protobuf cannot import files in a cycle, so they share shop/orders/a.proto"
            ),
            lowered.codes(),
        )
    }

    @Test
    fun `a cycle through a third declaration shares one file`() {
        val lowered =
            lowerTypes(
                shop(
                    "model A { #1 b B? }\n\nmodel B { #1 c C? }\n\nmodel C { #1 a A? }\n\n" +
                        "model D { #1 a A }\n"
                )
            )
        assertEquals(
            listOf("shop/orders/a.proto", "shop/orders/d.proto"),
            lowered.model.files.map { it.path },
        )
        assertEquals(listOf("shop/orders/a.proto"), lowered.file("shop/orders/d.proto").imports)
        assertNull(lowered.protocErrors())
    }

    @Test
    fun `a key record is its own file and reports nothing`() {
        val source =
            shop(
                "model A { #1 pair Pair }\n\nmodel Pair { #1 a int32 { id }  #2 b int32 { id } }\n"
            )
        val lowered = lowerTypes(source)
        assertEquals(
            listOf("shop/orders/a.proto", "shop/orders/pair.proto", "shop/orders/pair_key.proto"),
            lowered.model.files.map { it.path },
        )
        val a = lowered.file("shop/orders/a.proto")
        assertEquals(listOf("shop/orders/pair_key.proto"), a.imports)
        assertEquals(ProtoType.Named("PairKey"), message(a, "A").fields.single().type)
        assertEquals(
            listOf("Pair"),
            lowered.file("shop/orders/pair.proto").declarations.map { it.name },
        )
        // The layout adds no shared-file note: the schema layout's notes are all there is.
        assertEquals(lower(source).codes(), lowered.codes())
        assertNull(lowered.protocErrors())
    }

    @Test
    fun `a service is its own file importing its payloads`() {
        val lowered = lowerTypes(SERVICE, CATALOG)
        val service = lowered.file("shop/orders/orders.proto")
        assertEquals(emptyList(), service.declarations)
        assertEquals(listOf("Orders"), service.services.map { it.name })
        assertEquals(
            listOf(
                "google/protobuf/empty.proto",
                "shop/orders/chunk.proto",
                "shop/orders/order.proto",
                "shop/orders/order_id.proto",
            ),
            service.imports,
        )
        assertEquals(
            listOf("shop/orders/orders.proto"),
            lowered.model.files
                .filter { "google/protobuf/empty.proto" in it.imports }
                .map { it.path },
        )
        assertNull(lowered.protocErrors())
    }

    @Test
    fun `a reference to another unit imports that type's file`() {
        val lowered = lowerTypes(SERVICE, CATALOG)
        val order = lowered.file("shop/orders/order.proto")
        assertEquals(listOf("shop/catalog/money.proto"), order.imports)
        assertEquals(ProtoType.Named(".shop.catalog.Money"), message(order, "Order").fields[1].type)
        assertNull(lowered.protocErrors())
    }

    @Test
    fun `well-known imports go to the file that uses them`() {
        val lowered =
            lowerTypes(shop("model Stamp { #1 at instant }\n\nmodel Plain { #1 n int32 }\n"))
        assertEquals(
            listOf("shop/orders/stamp.proto"),
            lowered.model.files
                .filter { "google/protobuf/timestamp.proto" in it.imports }
                .map { it.path },
        )
        assertEquals(emptyList(), lowered.file("shop/orders/plain.proto").imports)
        assertNull(lowered.protocErrors())
    }

    @Test
    fun `a type cycle across schemas is one file in the host package`() {
        val lowered = lowerTypes(ALPHA, BETA)
        val file = lowered.model.files.single()
        assertEquals("cyc/alpha/a.proto" to "cyc.alpha", file.path to file.packageName)
        assertEquals(listOf("A", "B"), file.declarations.map { it.name })
        assertEquals(emptyList(), file.imports)
        assertEquals(ProtoType.Named("B"), message(file, "A").fields.single().type)
        assertEquals(ProtoType.Named("A"), message(file, "B").fields.single().type)
        assertEquals(
            listOf(
                "cyc.alpha.schemata:5 SCH2001 model 'A' and model 'B' reference each other; " +
                    "Protobuf cannot import files in a cycle, so they share cyc/alpha/a.proto",
                "cyc.beta.schemata:5 SCH2001 model 'cyc.beta.B' is written under package " +
                    "'cyc.alpha' in cyc/alpha/a.proto",
            ),
            lowered.located(),
        )
        assertEquals(
            "break the reference cycle, or set `@proto(name: \"…\")` so that a declaration " +
                "of its own schema names the file",
            lowered.diagnostics[1].help,
        )
        assertNull(lowered.protocErrors())
    }

    @Test
    fun `two moved declarations are listed in one note`() {
        val lowered =
            lowerTypes(
                ALPHA,
                "schema cyc.beta\n\nimport cyc.alpha\n\nmodel B { #1 a A?  #2 c C? }\n\n" +
                    "model C { #1 b B? }\n",
            )
        assertEquals(
            listOf(
                "cyc.alpha.schemata:5 SCH2001 model 'A', model 'B' and model 'C' reference each " +
                    "other; Protobuf cannot import files in a cycle, so they share cyc/alpha/a.proto",
                "cyc.beta.schemata:5 SCH2001 model 'cyc.beta.B' and model 'cyc.beta.C' are " +
                    "written under package 'cyc.alpha' in cyc/alpha/a.proto",
            ),
            lowered.located(),
        )
        assertNull(lowered.protocErrors())
    }

    @Test
    fun `a schema cycle without a type cycle writes per schema packages that import each other`() {
        val lowered =
            lowerTypes(
                "schema cyc.alpha\n\nimport cyc.beta\n\nmodel Order { #1 customer Customer }\n\n" +
                    "model Note { #1 text string }\n",
                "schema cyc.beta\n\nimport cyc.alpha\n\nmodel Customer { #1 note Note? }\n",
            )
        // No merge, so no note: the schema layout would report the cycle as one file.
        assertEquals(emptyList(), lowered.codes())
        assertEquals(
            listOf(
                "cyc/alpha/note.proto" to "cyc.alpha",
                "cyc/alpha/order.proto" to "cyc.alpha",
                "cyc/beta/customer.proto" to "cyc.beta",
            ),
            lowered.model.files.map { it.path to it.packageName },
        )
        val order = lowered.file("cyc/alpha/order.proto")
        assertEquals(listOf("cyc/beta/customer.proto"), order.imports)
        assertEquals(
            ProtoType.Named(".cyc.beta.Customer"),
            message(order, "Order").fields.single().type,
        )
        val customer = lowered.file("cyc/beta/customer.proto")
        assertEquals(listOf("cyc/alpha/note.proto"), customer.imports)
        assertEquals(
            ProtoType.Named(".cyc.alpha.Note"),
            message(customer, "Customer").fields.single().type,
        )
        assertNull(lowered.protocErrors())
    }

    @Test
    fun `a service in a cycle schema stays in its own package`() {
        val lowered =
            lowerTypes(
                ALPHA + "\nservice Alphas {\n  #1 get(A): B\n}\n",
                BETA + "\nservice Betas {\n  #1 get(B): A\n}\n",
            )
        assertEquals(
            listOf(
                "cyc/alpha/a.proto" to "cyc.alpha",
                "cyc/alpha/alphas.proto" to "cyc.alpha",
                "cyc/beta/betas.proto" to "cyc.beta",
            ),
            lowered.model.files.map { it.path to it.packageName },
        )
        val alphas = lowered.file("cyc/alpha/alphas.proto").services.single().rpcs.single()
        assertEquals("A" to "B", alphas.request.reference to alphas.response.reference)
        val betas = lowered.file("cyc/beta/betas.proto")
        assertEquals(listOf("cyc/alpha/a.proto"), betas.imports)
        val get = betas.services.single().rpcs.single()
        assertEquals(
            ".cyc.alpha.B" to ".cyc.alpha.A",
            get.request.reference to get.response.reference,
        )
        assertNull(lowered.protocErrors())
    }

    @Test
    fun `a self reference stays a singleton file in its own package`() {
        val lowered =
            lowerTypes(
                "schema tree\n\nmodel Node {\n  #1 kids Node[]\n  #2 payload Leaf?\n}\n" +
                    "model Leaf { #1 text string }\n",
                "schema other\n\nimport tree\n\nmodel Root { #1 node tree.Node }\n",
            )
        assertEquals(
            listOf(
                "other/root.proto" to "other",
                "tree/leaf.proto" to "tree",
                "tree/node.proto" to "tree",
            ),
            lowered.model.files.map { it.path to it.packageName },
        )
        assertEquals(listOf("Node"), lowered.file("tree/node.proto").declarations.map { it.name })
        assertEquals(listOf("tree/node.proto"), lowered.file("other/root.proto").imports)
        assertTrue(lowered.diagnostics.none { "written under package" in it.message })
        assertNull(lowered.protocErrors())
    }

    @Test
    fun `a third schema references a moved type through the host package`() {
        val lowered =
            lowerTypes(ALPHA, BETA, "schema other\n\nimport cyc.beta\n\nmodel O { #1 b B }\n")
        assertEquals(
            listOf("cyc/alpha/a.proto", "other/o.proto"),
            lowered.model.files.map { it.path },
        )
        val other = lowered.file("other/o.proto")
        assertEquals(listOf("cyc/alpha/a.proto"), other.imports)
        assertEquals(ProtoType.Named(".cyc.alpha.B"), message(other, "O").fields.single().type)
        assertNull(lowered.protocErrors())
    }

    @Test
    fun `a shared file takes the host options without a conflict`() {
        val lowered =
            lowerTypes(
                ALPHA.replace(
                    "schema cyc.alpha",
                    "schema cyc.alpha @proto(java_package: \"com.example.alpha\")",
                ),
                "schema cyc.beta @proto(java_package: \"com.example.beta\")\n\nimport cyc.alpha\n\n" +
                    "model B { #1 a A? }\n\nmodel Solo { #1 n int32 }\n",
            )
        assertEquals(
            listOf(
                "SCH2001 model 'A' and model 'B' reference each other; " +
                    "Protobuf cannot import files in a cycle, so they share cyc/alpha/a.proto",
                "SCH2001 model 'cyc.beta.B' is written under package 'cyc.alpha' in cyc/alpha/a.proto",
            ),
            lowered.codes(),
        )
        assertEquals(
            listOf("java_package" to "\"com.example.alpha\""),
            lowered.file("cyc/alpha/a.proto").options,
        )
        assertEquals(
            listOf("java_package" to "\"com.example.beta\""),
            lowered.file("cyc/beta/solo.proto").options,
        )
        assertNull(lowered.protocErrors())
    }

    @Test
    fun `conflicting declared packages in a type cycle are not an error`() {
        val schema =
            analysed(
                ALPHA.replace("schema cyc.alpha", "schema cyc.alpha @proto(package: \"p.one\")"),
                BETA.replace("schema cyc.beta", "schema cyc.beta @proto(package: \"p.two\")"),
            )
        val lowered = ProtoLowering.lower(schema, ProtoLayout.TYPE)
        assertEquals(
            listOf(
                "cyc.alpha.schemata:5 SCH2001 model 'A' and model 'B' reference each other; " +
                    "Protobuf cannot import files in a cycle, so they share p/one/a.proto",
                "cyc.beta.schemata:5 SCH2001 model 'cyc.beta.B' is written under package " +
                    "'p.one' in p/one/a.proto",
            ),
            lowered.located(),
        )
        assertEquals(
            listOf("p/one/a.proto" to "p.one"),
            lowered.model.files.map { it.path to it.packageName },
        )
        assertEquals(
            listOf("p/one/a.proto"),
            ProtoTarget(ProtoLayout.TYPE).compile(schema).files.map { it.path },
        )
    }

    @Test
    fun `a declared outer classname is counted per host`() {
        val alpha = "schema cyc.alpha @proto(java_outer_classname: \"AlphaProto\")"
        val beta =
            BETA.replace(
                "schema cyc.beta",
                "schema cyc.beta @proto(java_outer_classname: \"BetaProto\")",
            )
        // cyc.alpha hosts the shared file and Extra's file; cyc.beta hosts none.
        val two =
            lowerTypes(
                ALPHA.replace("schema cyc.alpha", alpha) + "\nmodel Extra { #1 n int32 }\n",
                beta,
            )
        assertEquals(
            listOf(
                "cyc.alpha.schemata:1 SCH2007 schema 'cyc.alpha': " +
                    "@proto(java_outer_classname: \"AlphaProto\") names one Java class for every " +
                    "file of the type layout"
            ),
            two.located().filter { "SCH2007" in it },
        )
        // One hosted file keeps the declared name, and beta's own value applies to no file.
        val one = lowerTypes(ALPHA.replace("schema cyc.alpha", alpha), beta)
        assertEquals(emptyList(), one.codes().filter { it.startsWith("SCH2007") })
        assertEquals(
            listOf("java_outer_classname" to "\"AlphaProto\""),
            one.file("cyc/alpha/a.proto").options,
        )
        assertNull(one.protocErrors())
    }

    @Test
    fun `a moved declaration that collides with a host name is prefixed`() {
        val lowered = lowerTypes(NIEM, UC2)
        val file = lowered.model.files.single()
        assertEquals("niem_core/task.proto" to "niem_core", file.path to file.packageName)
        assertEquals(listOf("Task", "Uc2SystemTaskTask"), file.declarations.map { it.name })
        assertEquals(
            ProtoType.Named("Uc2SystemTaskTask"),
            message(file, "Task").fields.single().type,
        )
        assertEquals(
            ProtoType.Named("Task"),
            message(file, "Uc2SystemTaskTask").fields.single().type,
        )
        assertEquals(
            listOf(
                "uc2_system_task.schemata:5 SCH2001 model 'uc2_system_task.Task': proto name 'Task' " +
                    "is also used by model 'niem_core.Task'; written as 'Uc2SystemTaskTask'",
                "niem_core.schemata:5 SCH2001 model 'Task' and model 'Uc2SystemTaskTask' reference " +
                    "each other; Protobuf cannot import files in a cycle, so they share " +
                    "niem_core/task.proto",
                "uc2_system_task.schemata:5 SCH2001 model 'uc2_system_task.Task' is written under " +
                    "package 'niem_core' in niem_core/task.proto",
            ),
            lowered.located(),
        )
        assertEquals(1, lowered.codes().count { "; written as '" in it })
        assertEquals(
            "set `@proto(name: \"…\")` on one of them to choose the name",
            lowered.diagnostics.first().help,
        )
        assertNull(lowered.protocErrors())
    }

    @Test
    fun `a moved declaration with a proto name override keeps it`() {
        val kept =
            lowerTypes(
                NIEM,
                UC2.replace(
                    "model Task { #1 parent niem.Task? }",
                    "model Task {\n  #1 parent niem.Task?\n\n  @@proto(name: \"TaskDetail\")\n}",
                ),
            )
        assertEquals(
            listOf(
                "SCH2001 model 'Task' and model 'TaskDetail' reference each other; " +
                    "Protobuf cannot import files in a cycle, so they share niem_core/task.proto",
                "SCH2001 model 'uc2_system_task.Task' is written under package 'niem_core' in " +
                    "niem_core/task.proto",
            ),
            kept.codes(),
        )
        assertEquals(
            listOf("Task", "TaskDetail"),
            kept.model.files.single().declarations.map { it.name },
        )
        assertNull(kept.protocErrors())
        // A declared name that is taken is the author's collision: it is not prefixed.
        val taken =
            lowerTypes(
                NIEM,
                UC2.replace(
                    "model Task { #1 parent niem.Task? }",
                    "model Task {\n  #1 parent niem.Task?\n\n  @@proto(name: \"Task\")\n}",
                ),
            )
        assertEquals(
            listOf(
                "niem_core.schemata:5 SCH2001 model 'Task' and model 'Task' reference each other; " +
                    "Protobuf cannot import files in a cycle, so they share niem_core/task.proto",
                "uc2_system_task.schemata:5 SCH2001 model 'uc2_system_task.Task' is written under " +
                    "package 'niem_core' in niem_core/task.proto",
                "uc2_system_task.schemata:5 SCH2004 proto name 'Task' is already used by model " +
                    "'Task' (niem_core.schemata:5)",
            ),
            taken.located(),
        )
    }

    @Test
    fun `a same package cycle scope keeps the schema layout renames`() {
        val lowered =
            lowerTypes(
                NIEM.replace("schema niem_core", "schema niem_core @proto(package: \"uc2\")"),
                "schema uc2_system_task @proto(package: \"uc2\")\n\nimport niem_core as niem\n\n" +
                    "model Task { #1 n int32 }\n\nmodel Link { #1 to niem.Task? }\n",
            )
        // One scope: two would both lower to package 'uc2' (SCH2004).
        assertEquals(
            listOf(
                "uc2_system_task.schemata:5 SCH2001 model 'uc2_system_task.Task': proto name 'Task' " +
                    "is also used by model 'niem_core.Task'; written as 'Uc2SystemTaskTask'"
            ),
            lowered.located(),
        )
        assertEquals(
            listOf("uc2/link.proto", "uc2/task.proto", "uc2/uc2_system_task_task.proto"),
            lowered.model.files.map { it.path },
        )
        assertEquals(listOf("uc2"), lowered.model.files.map { it.packageName }.distinct())
        assertEquals(
            ProtoType.Named("Uc2SystemTaskTask"),
            message(lowered.file("uc2/task.proto"), "Task").fields.single().type,
        )
        assertNull(lowered.protocErrors())
    }

    @Test
    fun `a same package scope takes one value per option as a schema layout unit does`() {
        val a =
            "schema a @proto(package: \"p.v1\") @proto(java_package: \"com.a\") " +
                "@proto(java_multiple_files: true)\n\nimport b\n\nmodel Xa { #1 y b.Yb? }\n"
        val b =
            "schema b @proto(package: \"p.v1\") @proto(java_package: \"com.b\")\n\n" +
                "import c\n\nmodel Yb { #1 z c.Zc? }\n"
        val c = "schema c\n\nimport a\n\nmodel Zc { #1 x a.Xa? }\n"
        val conflict =
            "b.schemata:1 SCH2007 schemas a and b reference each other " +
                "but declare java_package 'com.a' and 'com.b'"
        assertEquals(listOf(conflict), lower(a, b, c).located().filter { "SCH2007" in it })
        val lowered = lowerTypes(a, b, c)
        assertEquals(listOf(conflict), lowered.located().filter { "SCH2007" in it })
        // b shares a's package, so its declaration is not moved: only c's is.
        assertEquals(
            listOf(
                "c.schemata:5 SCH2001 model 'c.Zc' is written under package 'p.v1' in " +
                    "p/v1/xa.proto"
            ),
            lowered.located().filter { "written under package" in it },
        )
    }

    @Test
    fun `same package schemas without a type cycle take one value per option`() {
        fun sources(alpha: String, beta: String) =
            arrayOf(
                "schema a @proto(package: \"p\") @proto(java_package: \"$alpha\")\n\n" +
                    "import b\n\nmodel Order { #1 customer b.Customer }\n\n" +
                    "model Note { #1 text string }\n",
                "schema b @proto(package: \"p\") @proto(java_package: \"$beta\")\n\n" +
                    "import a\n\nmodel Customer { #1 note a.Note? }\n",
            )
        val differing = lowerTypes(*sources("com.a", "com.b"))
        assertEquals(
            listOf(
                "b.schemata:1 SCH2007 schemas a and b reference each other " +
                    "but declare java_package 'com.a' and 'com.b'"
            ),
            differing.located(),
        )
        val agreeing = lowerTypes(*sources("com.p", "com.p"))
        assertEquals(emptyList(), agreeing.codes())
        assertEquals(
            listOf("p/customer.proto", "p/note.proto", "p/order.proto"),
            agreeing.model.files.map { it.path },
        )
        assertTrue(agreeing.model.files.all { it.options == listOf("java_package" to "\"com.p\"") })
        assertNull(agreeing.protocErrors())
    }

    @Test
    fun `a same package scope checks every wrapper class against every declaration`() {
        val option = "@proto(package: \"p\") @proto(java_multiple_files: true)"
        val lowered =
            lowerTypes(
                "schema a $option\n\nimport b\n\nmodel Order { #1 thing b.Thing }\n\n" +
                    "model Note { #1 text string }\n",
                "schema b $option\n\nimport a\n\nmodel Thing { #1 note a.Note? }\n\n" +
                    "model OrderProto { #1 x bool }\n",
            )
        assertEquals(
            listOf(
                "b.schemata:7 SCH2004 model 'OrderProto' and the Java class of order.proto " +
                    "both lower to 'OrderProto'"
            ),
            lowered.located(),
        )
    }

    @Test
    fun `a composite key of a moved model is referenced in its own package`() {
        val lowered =
            lowerTypes(
                "schema alpha\n\nimport beta\n\n" +
                    "model Seat {\n  #1 row int32\n  #2 num int32\n  #3 act beta.Act?\n\n" +
                    "  @@id(row, num)\n}\n",
                "schema beta\n\nimport alpha\n\nmodel Act { #1 seat alpha.Seat { embed } }\n",
                "schema other\n\nimport alpha\n\nmodel Ticket { #2 seat alpha.Seat }\n",
            )
        // Seat is moved into the host's file; its key record stays in its own package.
        assertEquals(
            listOf("Seat", "Act"),
            lowered.file("beta/act.proto").declarations.map { it.name },
        )
        assertEquals(
            listOf("SeatKey"),
            lowered.file("alpha/seat_key.proto").declarations.map { it.name },
        )
        val ticket = lowered.file("other/ticket.proto")
        assertEquals(listOf("alpha/seat_key.proto"), ticket.imports)
        assertEquals(
            ProtoType.Named(".alpha.SeatKey"),
            message(ticket, "Ticket").fields.single().type,
        )
        assertNull(lowered.protocErrors())
    }

    @Test
    fun `the file is named by the minimum name after renames`() {
        val lowered =
            lowerTypes(
                "schema m\n\nimport x.y\n\nmodel Zed { #1 other x.y.Zed? }\n",
                "schema x.y\n\nimport m\n\nmodel Zed { #1 back m.Zed? }\n",
            )
        // m hosts on the tie (m sorts before x.y); the rename sorts below the host's name.
        val file = lowered.model.files.single()
        assertEquals("m/xy_zed.proto" to "m", file.path to file.packageName)
        assertEquals(listOf("Zed", "XYZed"), file.declarations.map { it.name })
        assertEquals(ProtoType.Named("XYZed"), message(file, "Zed").fields.single().type)
        assertEquals(
            listOf(
                "x.y.schemata:5 SCH2001 model 'x.y.Zed': proto name 'Zed' is also used by model " +
                    "'m.Zed'; written as 'XYZed'",
                "x.y.schemata:5 SCH2001 model 'XYZed' and model 'Zed' reference each other; " +
                    "Protobuf cannot import files in a cycle, so they share m/xy_zed.proto",
                "x.y.schemata:5 SCH2001 model 'x.y.Zed' is written under package 'm' in " +
                    "m/xy_zed.proto",
            ),
            lowered.located(),
        )
        assertNull(lowered.protocErrors())
    }

    @Test
    fun `the shadowing rule holds across files`() {
        val lowered =
            lowerTypes(
                "schema corpus.shadow\n" +
                    "\n" +
                    "model Card { #1 top bool }\n" +
                    "\n" +
                    "model Order {\n" +
                    "  #1 outer corpus.shadow.Card\n" +
                    "  #2 inner Card\n" +
                    "\n" +
                    "  model Card { #1 nested bool }\n" +
                    "\n" +
                    "  model Line { #1 again corpus.shadow.Card  #2 near Card }\n" +
                    "}\n"
            )
        val order = lowered.file("corpus/shadow/order.proto")
        assertEquals(listOf("corpus/shadow/card.proto"), order.imports)
        assertEquals(
            listOf(ProtoType.Named(".corpus.shadow.Card"), ProtoType.Named("Card")),
            message(order, "Order").fields.map { it.type },
        )
        assertNull(lowered.protocErrors())
    }

    @Test
    fun `the same options print in every file`() {
        val lowered =
            lowerTypes(
                orders(
                    "@proto(java_package: \"com.example.orders\") @proto(java_multiple_files: true)"
                )
            )
        assertEquals(2, lowered.model.files.size)
        // Only the wrapper class name differs per file.
        assertEquals(
            1,
            lowered.model.files
                .map { file -> file.options.filter { it.first != "java_outer_classname" } }
                .distinct()
                .size,
        )
        assertEquals(emptyList(), lowered.codes())
        generated(lowered, "java") { out ->
            assertTrue(
                File(out, "com/example/orders/Order.java").isFile,
                out.walk().toList().toString(),
            )
            assertTrue(
                File(out, "com/example/orders/Status.java").isFile,
                out.walk().toList().toString(),
            )
        }
    }

    @Test
    fun `java_outer_classname is an error under the type layout`() {
        val source = orders("@proto(java_outer_classname: \"OrdersProto\")")
        val lowered = lowerTypes(source)
        assertEquals(
            listOf(
                "shop.orders.schemata:1 SCH2007 schema 'shop.orders': " +
                    "@proto(java_outer_classname: \"OrdersProto\") names one Java class for every file of the type layout"
            ),
            lowered.located(),
        )
        assertEquals(
            "drop the key or compile with --proto-layout schema",
            lowered.diagnostics.single().help,
        )
        assertEquals(emptyList(), ProtoTarget(ProtoLayout.TYPE).compile(analysed(source)).files)
        assertEquals(emptyList(), lower(source).codes())
    }

    @Test
    fun `a one-file unit keeps a declared java_outer_classname under the type layout`() {
        val declared =
            lowerTypes(
                "schema shop.orders @proto(java_outer_classname: \"X\") " +
                    "@proto(java_multiple_files: true)\n\nmodel Order { #1 n int32 }\n"
            )
        assertEquals(emptyList(), declared.codes())
        assertEquals(
            listOf("java_outer_classname" to "\"X\"", "java_multiple_files" to "true"),
            declared.model.files.single().options,
        )
        assertNull(declared.protocErrors())
        val absent =
            lowerTypes(
                "schema shop.orders @proto(java_multiple_files: true)\n\n" +
                    "model Order { #1 n int32 }\n"
            )
        assertEquals(
            listOf("java_outer_classname" to "\"OrderProto\"", "java_multiple_files" to "true"),
            absent.model.files.single().options,
        )
    }

    @Test
    fun `two declarations with one file name are an error at the later`() {
        val lowered =
            lowerTypes(shop("model HTTPServer { #1 a bool }\n\nmodel HttpServer { #1 b bool }\n"))
        assertEquals(
            listOf(
                "shop.orders.schemata:5 SCH2004 model 'HTTPServer' and model 'HttpServer' " +
                    "both lower to file 'shop/orders/http_server.proto'"
            ),
            lowered.located(),
        )
        assertEquals("set `@proto(name: \"…\")` on one of them", lowered.diagnostics.single().help)
    }

    @Test
    fun `a type and a service with one file name are an error at the later`() {
        val lowered =
            lowerTypes(
                shop(
                    "model OrderService { #1 a bool }\n\n" +
                        "@proto(name: \"Order_Service\") service Orders {\n" +
                        "  #1 get(OrderService): OrderService\n" +
                        "}\n"
                )
            )
        assertEquals(
            listOf(
                "shop.orders.schemata:5 SCH2004 model 'OrderService' and service 'Order_Service' " +
                    "both lower to file 'shop/orders/order_service.proto'"
            ),
            lowered.located(),
        )
    }

    @Test
    fun `files are ordered by path within a unit and units keep their order`() {
        val lowered =
            lowerTypes(
                "schema b\n\nmodel Zed { #1 n int32 }\n\nmodel Alpha { #1 n int32 }\n",
                "schema a\n\nmodel Thing { #1 n int32 }\n",
            )
        assertEquals(
            listOf("a/thing.proto", "b/alpha.proto", "b/zed.proto"),
            lowered.model.files.map { it.path },
        )
    }

    @Test
    fun `java_multiple_files writes a wrapper class name per file under the type layout`() {
        val lowered =
            lowerTypes(
                shop(
                    "model Order { #1 status HTTPStatus }\n\nmodel HTTPStatus { #1 code int32 }\n"
                ),
                "schema shop.web @proto(java_package: \"com.example.web\") " +
                    "@proto(java_multiple_files: true)\n\nmodel HTTPStatus { #1 code int32 }\n",
            )
        assertEquals(emptyList(), lowered.codes())
        // A unit without java_multiple_files names no wrapper.
        assertEquals(emptyList(), lowered.file("shop/orders/order.proto").options)
        assertEquals(
            listOf(
                "java_package" to "\"com.example.web\"",
                "java_outer_classname" to "\"HttpStatusProto\"",
                "java_multiple_files" to "true",
            ),
            lowered.file("shop/web/http_status.proto").options,
        )
        assertNull(lowered.protocErrors())
        generated(lowered, "java") { out ->
            // Listed by name: a case-insensitive file system would answer either spelling.
            val written = File(out, "com/example/web").list().orEmpty().toSet()
            assertTrue("HTTPStatus.java" in written, written.toString())
            assertTrue("HttpStatusProto.java" in written, written.toString())
        }
    }

    @Test
    fun `every file of a unit with java_multiple_files names its own wrapper class`() {
        val source =
            "schema shop.orders @proto(java_multiple_files: true)\n\n" +
                "model Order { #1 seat Seat }\n\n" +
                "model Seat { #1 row int32 { id }  #2 number int32 { id } }\n\n" +
                "service Orders { #1 get(Order): Order }\n"
        val lowered = lowerTypes(source)
        assertEquals(
            listOf(
                "shop/orders/order.proto" to "\"OrderProto\"",
                "shop/orders/orders.proto" to "\"OrdersProto\"",
                "shop/orders/seat.proto" to "\"SeatProto\"",
                "shop/orders/seat_key.proto" to "\"SeatKeyProto\"",
            ),
            lowered.model.files.map { it.path to it.options.first().second },
        )
        // In key order: the wrapper name stands where a declared one would, before
        // java_multiple_files.
        assertEquals(
            listOf(listOf("java_outer_classname", "java_multiple_files")),
            lowered.model.files.map { file -> file.options.map { it.first } }.distinct(),
        )
        assertNull(lowered.protocErrors())
        // The schema layout is untouched.
        assertEquals(
            listOf("java_multiple_files" to "true"),
            lower(source).model.files.single().options,
        )
    }

    @Test
    fun `a declaration named like a wrapper class is an error`() {
        val lowered =
            lowerTypes(
                "schema shop.orders @proto(java_multiple_files: true)\n\n" +
                    "model OrderProto { #1 x bool }\n\n" +
                    "model Order { #1 y bool }\n\n" +
                    "service OrdersProto { #1 get(Order): Order }\n\n" +
                    "service Orders { #1 put(Order): Order }\n"
            )
        assertEquals(
            listOf(
                "shop.orders.schemata:3 SCH2004 model 'OrderProto' and the Java class of order.proto " +
                    "both lower to 'OrderProto'",
                "shop.orders.schemata:7 SCH2004 service 'OrdersProto' and the Java class of " +
                    "orders.proto both lower to 'OrdersProto'",
            ),
            lowered.located(),
        )
        assertEquals(
            listOf("set `@proto(name: \"…\")` on it"),
            lowered.diagnostics.map { it.help }.distinct(),
        )
        val caseOnly =
            lowerTypes(
                "schema shop.orders @proto(java_multiple_files: true)\n\n" +
                    "model ORDERPROTO { #1 x bool }\n\nmodel Order { #1 y bool }\n"
            )
        assertEquals(
            listOf(
                "SCH2004 model 'ORDERPROTO' and the Java class of order.proto both lower to 'OrderProto'"
            ),
            caseOnly.codes(),
        )
    }

    @Test
    fun `without java_multiple_files no wrapper class name is written`() {
        val lowered =
            lowerTypes(
                "schema shop.orders @proto(java_package: \"com.example.orders\")\n\n" +
                    "model OrderProto { #1 x bool }\n\nmodel Order { #1 y bool }\n"
            )
        assertEquals(emptyList(), lowered.codes())
        assertTrue(
            lowered.model.files.all {
                it.options == listOf("java_package" to "\"com.example.orders\"")
            }
        )
        assertNull(lowered.protocErrors())
    }

    @Test
    fun `a renamed declaration colliding by name is reported once`() {
        val lowered =
            lowerTypes(
                shop(
                    "model Order { #1 a bool }\n\n" +
                        "model Label {\n  #1 b bool\n\n  @@proto(name: \"Order\")\n}\n"
                )
            )
        assertEquals(
            listOf(
                "shop.orders.schemata:5 SCH2004 proto name 'Order' is already used by model 'Order' " +
                    "(shop.orders.schemata:3)"
            ),
            lowered.located(),
        )
    }

    @Test
    fun `a key record in a file collision is named by its model`() {
        val seat = "model Seat { #1 row int32 { id }  #2 number int32 { id } }\n\n"
        val order = "model Order { #1 seat Seat }\n\n"
        val keyFirst = lowerTypes(shop(order + seat + "model SEATKey { #1 x bool }\n"))
        assertEquals(
            listOf(
                "shop.orders.schemata:7 SCH2004 the key of model 'Seat' and model 'SEATKey' " +
                    "both lower to file 'shop/orders/seat_key.proto'"
            ),
            keyFirst.located(),
        )
        assertEquals(
            "set `@proto(name: \"…\")` on model 'SEATKey'",
            keyFirst.diagnostics.single().help,
        )
        // Reported at the key record, the help points at its model, the one an author renames.
        val keyLater = lowerTypes(shop("model SEATKey { #1 x bool }\n\n" + order + seat))
        assertEquals(
            listOf(
                "SCH2004 model 'SEATKey' and the key of model 'Seat' " +
                    "both lower to file 'shop/orders/seat_key.proto'"
            ),
            keyLater.codes(),
        )
        assertEquals(
            "set `@proto(name: \"…\")` on model 'Seat'",
            keyLater.diagnostics.single().help,
        )
    }

    @Test
    fun `an invalid service name is reported before declaration overrides`() {
        val lowered =
            lower(
                shop(
                    "model Order {\n  #1 a bool\n\n  @@proto(name: \"1x\")\n}\n\n" +
                        "@proto(name: \"2y\") service Orders { #1 get(Order): Order }\n"
                )
            )
        assertEquals(
            listOf(
                "shop.orders.schemata:9 SCH2007 service 'Orders': @proto(name: \"2y\") " +
                    "is not a valid identifier",
                "shop.orders.schemata:3 SCH2007 model 'Order': @proto(name: \"1x\") " +
                    "is not a valid identifier",
            ),
            lowered.located(),
        )
    }

    @Test
    fun `a nested type in another unit imports that unit's type file`() {
        val lowered =
            lowerTypes(
                "schema shop.catalog\n\n" +
                    "model Money {\n  #1 currency Currency\n\n  model Currency { #1 code string }\n}\n",
                "schema shop.orders\n\nimport shop.catalog as catalog\n\n" +
                    "model Order { #1 currency catalog.Money.Currency }\n",
            )
        val order = lowered.file("shop/orders/order.proto")
        assertEquals(listOf("shop/catalog/money.proto"), order.imports)
        assertEquals(
            ProtoType.Named(".shop.catalog.Money.Currency"),
            message(order, "Order").fields.single().type,
        )
        assertNull(lowered.protocErrors())
    }

    @Test
    fun `an empty schema writes no file under the type layout`() {
        val lowered = lowerTypes("schema shop.orders\n")
        assertEquals(emptyList(), lowered.codes())
        assertEquals(emptyList(), lowered.model.files)
    }
}
