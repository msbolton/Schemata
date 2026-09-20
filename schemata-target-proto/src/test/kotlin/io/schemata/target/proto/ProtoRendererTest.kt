package io.schemata.target.proto

import io.schemata.testkit.Golden
import io.schemata.testkit.Protoc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProtoRendererTest {
    private val file =
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

    @Test
    fun `renders the golden file`() {
        val out = ProtoRenderer.render(file).single()
        assertEquals("shop/orders.proto", out.path)
        Golden.assertMatches("user.proto", out.content)
    }

    @Test
    fun `rendered output compiles under protoc`() {
        val out = ProtoRenderer.render(file).single()
        assertNull(Protoc.compile(mapOf(out.path to out.content)))
    }

    @Test
    fun `target exposes lower and render under the name proto`() {
        assertEquals("proto", ProtoTarget.name)
    }
}
