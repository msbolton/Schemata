package io.schemata.target.proto

import io.schemata.testkit.Golden
import io.schemata.testkit.Protoc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProtoRendererTest {
    private fun scalar(keyword: String) = ProtoType.Scalar(keyword)

    private val user =
        ProtoFile(
            path = "shop/orders.proto",
            packageName = "shop.orders",
            imports = emptyList(),
            declarations =
                listOf(
                    ProtoMessage(
                        name = "User",
                        doc = null,
                        fields =
                            listOf(
                                ProtoField(1, "id", scalar("string"), notes = listOf("uuid")),
                                ProtoField(2, "email", scalar("string"), label = Label.OPTIONAL),
                                ProtoField(3, "name", scalar("string")),
                                ProtoField(4, "age", scalar("int32")),
                            ),
                        oneofs = emptyList(),
                        nested = emptyList(),
                        reserved = ProtoReserved.NONE,
                    )
                ),
        )

    private val customer =
        ProtoFile(
            path = "shop/customers.proto",
            packageName = "shop.customers",
            imports = emptyList(),
            declarations =
                listOf(
                    ProtoMessage(
                        "Customer",
                        null,
                        listOf(ProtoField(1, "name", scalar("string"))),
                        emptyList(),
                        emptyList(),
                        ProtoReserved.NONE,
                    )
                ),
        )

    /** Every construct the renderer prints, in one file. */
    private val kitchen =
        ProtoFile(
            path = "shop/kitchen.proto",
            packageName = "shop.kitchen",
            imports = listOf("google/protobuf/timestamp.proto", "shop/customers.proto"),
            declarations =
                listOf(
                    ProtoEnum(
                        name = "Status",
                        doc = "Every construct the renderer prints.",
                        values =
                            listOf(
                                ProtoEnumValue("STATUS_UNSPECIFIED", 0),
                                ProtoEnumValue("STATUS_PENDING", 1, doc = "Waiting."),
                                ProtoEnumValue("STATUS_PAID", 2, deprecated = true),
                            ),
                        reserved = ProtoReserved(listOf(3..3, 5..7), listOf("STATUS_OLD")),
                    ),
                    ProtoMessage(
                        "Card",
                        null,
                        emptyList(),
                        emptyList(),
                        emptyList(),
                        ProtoReserved.NONE,
                    ),
                    ProtoMessage(
                        name = "Payment",
                        doc = null,
                        fields = emptyList(),
                        oneofs =
                            listOf(
                                ProtoOneof(
                                    "kind",
                                    null,
                                    listOf(
                                        ProtoField(1, "card", ProtoType.Named("Card")),
                                        ProtoField(
                                            2,
                                            "cash",
                                            scalar("string"),
                                            notes = listOf("string(max = 5)"),
                                        ),
                                    ),
                                )
                            ),
                        nested = emptyList(),
                        reserved = ProtoReserved.NONE,
                    ),
                    ProtoMessage(
                        "Empty",
                        null,
                        emptyList(),
                        emptyList(),
                        emptyList(),
                        ProtoReserved.NONE,
                    ),
                    ProtoMessage(
                        name = "Order",
                        doc = "An order.",
                        fields =
                            listOf(
                                ProtoField(1, "id", scalar("string"), notes = listOf("uuid")),
                                ProtoField(
                                    2,
                                    "customer",
                                    ProtoType.Named(".shop.customers.Customer"),
                                ),
                                ProtoField(
                                    3,
                                    "status",
                                    ProtoType.Named("Status"),
                                    Label.OPTIONAL,
                                    notes = listOf("default = pending"),
                                ),
                                ProtoField(4, "lines", ProtoType.Named("Line"), Label.REPEATED),
                                ProtoField(
                                    5,
                                    "counts",
                                    ProtoType.MapOf(scalar("string"), scalar("int32")),
                                ),
                                ProtoField(
                                    6,
                                    "placed_at",
                                    ProtoType.Named(".google.protobuf.Timestamp"),
                                    deprecated = true,
                                ),
                            ),
                        oneofs = emptyList(),
                        nested =
                            listOf(
                                ProtoMessage(
                                    "Line",
                                    null,
                                    listOf(ProtoField(1, "quantity", scalar("int32"))),
                                    emptyList(),
                                    emptyList(),
                                    ProtoReserved.NONE,
                                )
                            ),
                        reserved = ProtoReserved(listOf(11..11), listOf("legacy_ref")),
                        deprecated = true,
                    ),
                ),
        )

    @Test
    fun `renders the phase 1 golden unchanged`() {
        val out = ProtoRenderer.render(ProtoModel(listOf(user))).single()
        assertEquals("shop/orders.proto", out.path)
        Golden.assertMatches("user.proto", out.content)
    }

    @Test
    fun `renders every construct`() {
        val out = ProtoRenderer.render(ProtoModel(listOf(kitchen))).single()
        assertEquals("shop/kitchen.proto", out.path)
        Golden.assertMatches("kitchen.proto", out.content)
    }

    @Test
    fun `a blank doc line renders as a bare comment marker`() {
        val file =
            ProtoFile(
                path = "a.proto",
                packageName = "a",
                imports = emptyList(),
                declarations =
                    listOf(
                        ProtoMessage(
                            name = "Spaced",
                            doc = "Line one.\n\nLine three.",
                            fields = listOf(ProtoField(1, "x", scalar("bool"))),
                            oneofs = emptyList(),
                            nested = emptyList(),
                            reserved = ProtoReserved.NONE,
                        )
                    ),
            )
        assertEquals(
            """
            syntax = "proto3";

            package a;

            // Line one.
            //
            // Line three.
            message Spaced {
              bool x = 1;
            }

            """
                .trimIndent(),
            ProtoRenderer.render(ProtoModel(listOf(file))).single().content,
        )
    }

    @Test
    fun `renders one output per file in model order`() {
        val outs = ProtoRenderer.render(ProtoModel(listOf(customer, user)))
        assertEquals(listOf("shop/customers.proto", "shop/orders.proto"), outs.map { it.path })
    }

    @Test
    fun `rendered output compiles under protoc, well-known types included`() {
        val outs = ProtoRenderer.render(ProtoModel(listOf(customer, user, kitchen)))
        assertNull(Protoc.compile(outs.associate { it.path to it.content }))
    }

    @Test
    fun `target is named proto`() {
        assertEquals("proto", ProtoTarget.name)
    }
}
