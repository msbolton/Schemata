package io.schemata.core.ir

import io.schemata.lang.Span
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class IrTest {
    private val at = Span("t", 1, 1, 1, 1)

    private val address =
        RecordType(
            qualifiedName = QualifiedName("shop.orders", listOf("Order", "Address")),
            name = "Address",
            fields = emptyList(),
            reserved = Reserved.NONE,
            recursive = false,
            nested = emptyList(),
            doc = null,
            span = at,
            nameSpan = at,
        )

    private val order =
        RecordType(
            qualifiedName = QualifiedName("shop.orders", listOf("Order")),
            name = "Order",
            fields =
                listOf(
                    Field(
                        1,
                        "id",
                        Scalar(Builtin.UUID),
                        nullable = false,
                        default = null,
                        aliasName = null,
                        doc = null,
                        span = at,
                        nameSpan = at,
                    ),
                    Field(
                        2,
                        "shipping",
                        Ref(address.qualifiedName),
                        nullable = false,
                        default = null,
                        aliasName = null,
                        doc = null,
                        span = at,
                        nameSpan = at,
                    ),
                ),
            reserved = Reserved(listOf(11..11), setOf("legacy_ref")),
            recursive = false,
            nested = listOf(address),
            doc = "An order",
            span = at,
            nameSpan = at,
        )

    private val schema = Schema(listOf(Namespace("shop.orders", listOf(order), at)))

    @Test
    fun `lookup finds top-level and nested declarations by qualified name`() {
        assertEquals(order, schema.lookup(QualifiedName("shop.orders", listOf("Order"))))
        assertEquals(
            address,
            schema.lookup(QualifiedName("shop.orders", listOf("Order", "Address"))),
        )
        assertNull(schema.lookupOrNull(QualifiedName("shop.orders", listOf("Nope"))))
        assertFailsWith<IllegalStateException> { schema.lookup(QualifiedName("x", listOf("Y"))) }
    }

    @Test
    fun `qualified names print dotted and expose the simple name`() {
        assertEquals("shop.orders.Order.Address", address.qualifiedName.toString())
        assertEquals("Address", address.qualifiedName.simpleName)
    }

    @Test
    fun `builtins cover every entry and resolve by name`() {
        assertEquals(13, Builtin.entries.size)
        assertEquals(Builtin.INSTANT, Builtin.byName("instant"))
        assertNull(Builtin.byName("money"))
    }

    @Test
    fun `refinements default to empty`() {
        assertEquals(Refinements(), Scalar(Builtin.STRING).refinements)
    }
}
