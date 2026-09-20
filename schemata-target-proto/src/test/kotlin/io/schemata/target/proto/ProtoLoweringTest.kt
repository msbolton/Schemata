package io.schemata.target.proto

import io.schemata.core.ir.Builtin
import io.schemata.core.ir.Field
import io.schemata.core.ir.RecordType
import io.schemata.core.ir.Schema
import io.schemata.lang.Category
import io.schemata.lang.Severity
import kotlin.test.Test
import kotlin.test.assertEquals

class ProtoLoweringTest {
    private val schema =
        Schema(
            namespace = "shop.orders",
            records =
                listOf(
                    RecordType(
                        "User",
                        listOf(
                            Field(1, "id", Builtin.UUID, nullable = false),
                            Field(2, "email", Builtin.STRING, nullable = true),
                            Field(3, "name", Builtin.STRING, nullable = false),
                            Field(4, "age", Builtin.INT32, nullable = false),
                            Field(5, "active", Builtin.BOOL, nullable = false),
                        ),
                    )
                ),
        )

    @Test
    fun `maps the namespace to a package and a file path`() {
        val file = ProtoLowering.lower(schema).model
        assertEquals("shop.orders", file.packageName)
        assertEquals("shop/orders.proto", file.path)
    }

    @Test
    fun `maps scalars and keeps ordinals as field numbers`() {
        val fields = ProtoLowering.lower(schema).model.messages.single().fields
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
        val fields = ProtoLowering.lower(schema).model.messages.single().fields
        assertEquals(listOf(false, true, false, false, false), fields.map { it.optional })
    }

    @Test
    fun `uuid lowers to string and is reported as lossy exactly once`() {
        val lowered = ProtoLowering.lower(schema)
        val id = lowered.model.messages.single().fields.first()
        assertEquals("uuid", id.loweredFrom)
        assertEquals(null, lowered.model.messages.single().fields[1].loweredFrom)
        val d = lowered.diagnostics.single()
        assertEquals(Severity.WARNING, d.severity)
        assertEquals(Category.LOSSY, d.category)
        assertEquals(
            "field 'User.id': uuid has no Protobuf representation; lowered to string",
            d.message,
        )
    }
}
