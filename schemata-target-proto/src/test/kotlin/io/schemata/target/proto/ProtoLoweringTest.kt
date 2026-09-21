package io.schemata.target.proto

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
import io.schemata.core.ir.Refinements
import io.schemata.core.ir.Reserved
import io.schemata.core.ir.Scalar
import io.schemata.core.ir.Schema
import io.schemata.core.ir.Type
import io.schemata.core.ir.TypeDecl
import io.schemata.core.ir.UnionMember
import io.schemata.core.ir.UnionType
import io.schemata.lang.Category
import io.schemata.lang.Severity
import io.schemata.lang.Span
import io.schemata.lang.ast.Literal
import kotlin.test.Test
import kotlin.test.assertEquals

class ProtoLoweringTest {
    private fun at(line: Int) = Span("orders.schemata", line, 3, line, 20)

    private fun field(
        ordinal: Int,
        name: String,
        type: Type,
        nullable: Boolean = false,
        default: Literal? = null,
        line: Int = 10 + ordinal,
    ) = Field(ordinal, name, type, nullable, default, null, null, at(line), at(line))

    private fun record(
        ns: String,
        name: String,
        vararg fields: Field,
        nested: List<TypeDecl> = emptyList(),
        line: Int = 3,
    ) =
        RecordType(
            QualifiedName(ns, listOf(name)),
            name,
            fields.toList(),
            Reserved.NONE,
            false,
            nested,
            null,
            at(line),
            at(line),
        )

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

    private val orders = Namespace("shop.orders", listOf(user), at(1))
    private val customers =
        Namespace(
            "shop.customers",
            listOf(record("shop.customers", "Customer", field(1, "name", Scalar(Builtin.STRING)))),
            Span("customers.schemata", 1, 1, 1, 24),
        )
    private val schema = Schema(listOf(customers, orders))

    @Test
    fun `emits one file per namespace with package and path`() {
        val files = ProtoLowering.lower(schema).model.files
        assertEquals(
            listOf(
                "shop/customers.proto" to "shop.customers",
                "shop/orders.proto" to "shop.orders",
            ),
            files.map { it.path to it.packageName },
        )
    }

    @Test
    fun `maps scalars and keeps ordinals as field numbers`() {
        val fields = ProtoLowering.lower(schema).model.files[1].messages.single().fields
        assertEquals(listOf(1, 2, 3, 4, 5), fields.map { it.number })
        assertEquals(
            listOf(
                ProtoScalar.STRING,
                ProtoScalar.STRING,
                ProtoScalar.STRING,
                ProtoScalar.INT32,
                ProtoScalar.BOOL,
            ),
            fields.map { it.type },
        )
        assertEquals(listOf(false, true, false, false, false), fields.map { it.optional })
    }

    @Test
    fun `uuid lowers to string and is reported as lossy at the field's span`() {
        val lowered = ProtoLowering.lower(schema)
        assertEquals("uuid", lowered.model.files[1].messages.single().fields.first().loweredFrom)
        val d = lowered.diagnostics.single()
        assertEquals(Category.LOSSY, d.category)
        assertEquals(
            "field 'User.id': uuid has no Protobuf representation; lowered to string",
            d.message,
        )
        assertEquals(at(4), d.span)
    }

    @Test
    fun `reports every shape it cannot lower yet and omits those fields`() {
        val ns = "a"
        val leaf = record(ns, "Leaf", field(1, "v", Scalar(Builtin.INT32)), line = 2)
        val inner = record(ns, "Inner", field(1, "z", Scalar(Builtin.BOOL)), line = 20)
        val status =
            EnumType(
                QualifiedName(ns, listOf("Status")),
                "Status",
                listOf(EnumValue(1, "x", null, at(30), at(30))),
                Reserved.NONE,
                emptyList(),
                null,
                at(30),
                at(30),
            )
        val union =
            UnionType(
                QualifiedName(ns, listOf("U")),
                "U",
                listOf(UnionMember(1, Ref(leaf.qualifiedName), null, at(40))),
                emptyList(),
                null,
                at(40),
                at(40),
            )
        val r =
            record(
                ns,
                "R",
                field(1, "ok", Scalar(Builtin.STRING), line = 11),
                field(2, "ref", Ref(leaf.qualifiedName), line = 12),
                field(3, "big", Scalar(Builtin.INT64), line = 13),
                field(4, "many", ListOf(Scalar(Builtin.STRING), false), line = 14),
                field(
                    5,
                    "map",
                    MapOf(Scalar(Builtin.STRING), Scalar(Builtin.BOOL), false),
                    line = 15,
                ),
                field(
                    6,
                    "dflt",
                    Scalar(Builtin.STRING),
                    default = Literal.StringLit("x", at(16)),
                    line = 16,
                ),
                nested = listOf(inner),
                line = 10,
            )
        val lowered =
            ProtoLowering.lower(
                Schema(listOf(Namespace(ns, listOf(leaf, status, union, r), at(1))))
            )
        assertEquals(
            listOf(
                "30 SCH2002 target 'proto' cannot lower enums yet (SCH-24)",
                "40 SCH2002 target 'proto' cannot lower unions yet (SCH-24)",
                "12 SCH2002 field 'R.ref': target 'proto' cannot lower record references yet (SCH-24)",
                "13 SCH2002 field 'R.big': target 'proto' cannot lower int64 yet (SCH-23)",
                "14 SCH2002 field 'R.many': target 'proto' cannot lower lists yet (SCH-24)",
                "15 SCH2002 field 'R.map': target 'proto' cannot lower maps yet (SCH-24)",
                "16 SCH2003 field 'R.dflt': target 'proto' cannot lower field defaults yet (SCH-25)",
                "20 SCH2002 target 'proto' cannot lower nested declarations yet (SCH-24)",
            ),
            lowered.diagnostics.map { "${it.span.startLine} ${it.code.id} ${it.message}" },
        )
        assertEquals(listOf(Severity.ERROR), lowered.diagnostics.map { it.severity }.distinct())
        val message = lowered.model.files.single().messages.single { it.name == "R" }
        assertEquals(listOf("ok"), message.fields.map { it.name })
    }

    @Test
    fun `reserved ordinals and names are reported until proto emits them`() {
        val r =
            RecordType(
                QualifiedName("a", listOf("R")),
                "R",
                listOf(field(1, "x", Scalar(Builtin.STRING))),
                Reserved(listOf(2..2), setOf("old")),
                false,
                emptyList(),
                null,
                at(3),
                at(3),
            )
        val d =
            ProtoLowering.lower(Schema(listOf(Namespace("a", listOf(r), at(1)))))
                .diagnostics
                .single()
        assertEquals(
            "SCH2002 target 'proto' cannot lower reserved ordinals and names yet (SCH-24)",
            "${d.code.id} ${d.message}",
        )
        assertEquals(at(3), d.span)
    }

    @Test
    fun `refinements anywhere in a type are reported`() {
        val r =
            record(
                "a",
                "R",
                field(1, "s", Scalar(Builtin.STRING, Refinements(max = 5)), line = 11),
                field(
                    2,
                    "l",
                    ListOf(Scalar(Builtin.STRING, Refinements(max = 5)), false),
                    line = 12,
                ),
            )
        val ds = ProtoLowering.lower(Schema(listOf(Namespace("a", listOf(r), at(1))))).diagnostics
        assertEquals(
            listOf(
                "11 SCH2003 field 'R.s': target 'proto' cannot lower type refinements yet (SCH-23)",
                "12 SCH2003 field 'R.l': target 'proto' cannot lower type refinements yet (SCH-23)",
            ),
            ds.map { "${it.span.startLine} ${it.code.id} ${it.message}" },
        )
    }
}
