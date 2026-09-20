package io.schemata.target.proto

import io.schemata.core.ir.Builtin
import io.schemata.core.ir.Field
import io.schemata.core.ir.Namespace
import io.schemata.core.ir.RecordType
import io.schemata.core.ir.Schema
import io.schemata.lang.Category
import io.schemata.lang.Severity
import io.schemata.lang.Span
import kotlin.test.Test
import kotlin.test.assertEquals

class ProtoLoweringTest {
    private fun at(line: Int) = Span("orders.schemata", line, 3, line, 20)

    private val orders =
        Namespace(
            name = "shop.orders",
            records =
                listOf(
                    RecordType(
                        "User",
                        listOf(
                            Field(1, "id", Builtin.UUID, nullable = false, span = at(4)),
                            Field(2, "email", Builtin.STRING, nullable = true, span = at(5)),
                            Field(3, "name", Builtin.STRING, nullable = false, span = at(6)),
                            Field(4, "age", Builtin.INT32, nullable = false, span = at(7)),
                            Field(5, "active", Builtin.BOOL, nullable = false, span = at(8)),
                        ),
                        span = at(3),
                    )
                ),
            span = at(1),
        )

    private val customers =
        Namespace(
            name = "shop.customers",
            records =
                listOf(
                    RecordType(
                        "Customer",
                        listOf(Field(1, "name", Builtin.STRING, nullable = false, span = at(3))),
                        span = at(2),
                    )
                ),
            span = Span("customers.schemata", 1, 1, 1, 24),
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
    }

    @Test
    fun `nullable becomes optional`() {
        val fields = ProtoLowering.lower(schema).model.files[1].messages.single().fields
        assertEquals(listOf(false, true, false, false, false), fields.map { it.optional })
    }

    @Test
    fun `uuid lowers to string and is reported as lossy at the field's span`() {
        val lowered = ProtoLowering.lower(schema)
        val id = lowered.model.files[1].messages.single().fields.first()
        assertEquals("uuid", id.loweredFrom)
        val d = lowered.diagnostics.single()
        assertEquals(Severity.WARNING, d.severity)
        assertEquals(Category.LOSSY, d.category)
        assertEquals(
            "field 'User.id': uuid has no Protobuf representation; lowered to string",
            d.message,
        )
        assertEquals(at(4), d.span)
    }
}
