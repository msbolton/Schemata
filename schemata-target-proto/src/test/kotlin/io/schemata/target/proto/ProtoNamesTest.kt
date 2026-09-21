package io.schemata.target.proto

import io.schemata.core.ir.AnnotationValue
import io.schemata.core.ir.Annotations
import io.schemata.core.ir.Builtin
import io.schemata.core.ir.EnumType
import io.schemata.core.ir.EnumValue
import io.schemata.core.ir.Field
import io.schemata.core.ir.Namespace
import io.schemata.core.ir.QualifiedName
import io.schemata.core.ir.Reserved
import io.schemata.core.ir.Scalar
import io.schemata.lang.Span
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProtoNamesTest {
    private val at = Span("t.schemata", 1, 1, 1, 1)

    private fun proto(vararg pairs: Pair<String, String>) =
        Annotations(mapOf("proto" to pairs.associate { (k, v) -> k to AnnotationValue.Str(v) }))

    private val deprecated = Annotations(mapOf("" to mapOf("deprecated" to AnnotationValue.Flag)))

    @Test
    fun `snake and upper snake follow camel boundaries`() {
        assertEquals("bank_transfer", ProtoNames.snakeCase("BankTransfer"))
        assertEquals("uuid", ProtoNames.snakeCase("uuid"))
        assertEquals("order_status", ProtoNames.snakeCase("OrderStatus"))
        assertEquals("ORDER_STATUS", ProtoNames.upperSnake("OrderStatus"))
        assertEquals("STATUS", ProtoNames.upperSnake("Status"))
        assertEquals("http_status", ProtoNames.snakeCase("HTTPStatus"))
        assertEquals("io_error", ProtoNames.snakeCase("IOError"))
        assertEquals("kind2", ProtoNames.snakeCase("Kind2"))
        assertEquals("HTTP_STATUS", ProtoNames.upperSnake("HTTPStatus"))
    }

    @Test
    fun `packages and declaration names honour proto overrides`() {
        val ns = Namespace("shop.orders", emptyList(), at)
        assertEquals("shop.orders", ProtoNames.packageOf(ns))
        assertEquals(
            "corp.v1",
            ProtoNames.packageOf(ns.copy(annotations = proto("package" to "corp.v1"))),
        )
        val enum =
            EnumType(
                QualifiedName("a", listOf("Status")),
                "Status",
                emptyList(),
                Reserved.NONE,
                emptyList(),
                null,
                at,
                at,
            )
        assertEquals("Status", ProtoNames.of(enum))
        assertEquals("State", ProtoNames.of(enum.copy(annotations = proto("name" to "State"))))
        val field = Field(1, "email", Scalar(Builtin.STRING), false, null, null, null, at, at)
        assertEquals("email", ProtoNames.of(field))
        assertEquals(
            "email_address",
            ProtoNames.of(field.copy(annotations = proto("name" to "email_address"))),
        )
    }

    @Test
    fun `enum values are prefixed with the proto enum name unless overridden`() {
        val value = EnumValue(1, "pending", null, at, at)
        assertEquals("ORDER_STATUS_PENDING", ProtoNames.of("OrderStatus", value))
        assertEquals(
            "CANCELLED_BY_USER",
            ProtoNames.of(
                "OrderStatus",
                value.copy(annotations = proto("name" to "CANCELLED_BY_USER")),
            ),
        )
        assertEquals("STATUS_OLD_STATUS", ProtoNames.valueName("Status", "old_status"))
        assertEquals("STATUS_UNSPECIFIED", ProtoNames.zeroValue("Status"))
    }

    @Test
    fun `deprecation is the core flag`() {
        assertTrue(ProtoNames.deprecated(deprecated))
        assertTrue(
            ProtoNames.deprecated(
                Annotations(mapOf("" to mapOf("deprecated" to AnnotationValue.Str("why"))))
            )
        )
        assertFalse(ProtoNames.deprecated(Annotations.NONE))
    }
}
