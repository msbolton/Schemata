package io.schemata.target.proto

import io.schemata.testkit.Golden
import io.schemata.testkit.Protoc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProtoRendererTest {
    private val user =
        ProtoFile(
            path = "shop/orders.proto",
            packageName = "shop.orders",
            messages =
                listOf(
                    ProtoMessage(
                        "User",
                        listOf(
                            ProtoField(
                                1,
                                "id",
                                ProtoScalar.STRING,
                                optional = false,
                                loweredFrom = "uuid",
                            ),
                            ProtoField(
                                2,
                                "email",
                                ProtoScalar.STRING,
                                optional = true,
                                loweredFrom = null,
                            ),
                            ProtoField(
                                3,
                                "name",
                                ProtoScalar.STRING,
                                optional = false,
                                loweredFrom = null,
                            ),
                            ProtoField(
                                4,
                                "age",
                                ProtoScalar.INT32,
                                optional = false,
                                loweredFrom = null,
                            ),
                        ),
                    )
                ),
        )

    private val customer =
        ProtoFile(
            path = "shop/customers.proto",
            packageName = "shop.customers",
            messages =
                listOf(
                    ProtoMessage(
                        "Customer",
                        listOf(
                            ProtoField(
                                1,
                                "name",
                                ProtoScalar.STRING,
                                optional = false,
                                loweredFrom = null,
                            )
                        ),
                    )
                ),
        )

    @Test
    fun `renders the golden file`() {
        val out = ProtoRenderer.render(ProtoModel(listOf(user))).single()
        assertEquals("shop/orders.proto", out.path)
        Golden.assertMatches("user.proto", out.content)
    }

    @Test
    fun `renders one output per file in model order`() {
        val outs = ProtoRenderer.render(ProtoModel(listOf(customer, user)))
        assertEquals(listOf("shop/customers.proto", "shop/orders.proto"), outs.map { it.path })
    }

    @Test
    fun `rendered output compiles under protoc`() {
        val outs = ProtoRenderer.render(ProtoModel(listOf(customer, user)))
        assertNull(Protoc.compile(outs.associate { it.path to it.content }))
    }

    @Test
    fun `target is named proto`() {
        assertEquals("proto", ProtoTarget.name)
    }
}
