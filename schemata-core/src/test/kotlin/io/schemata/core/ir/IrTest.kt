package io.schemata.core.ir

import io.schemata.lang.Span
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    @Test
    fun `refinements and annotations have empty sentinels`() {
        assertEquals(Refinements(), Refinements.NONE)
        assertTrue(Refinements.NONE.isEmpty)
        assertEquals(false, Refinements(max = BigDecimal.ONE).isEmpty)
        assertTrue(Refinements(precision = 19, scale = 4).isEmpty.not())
        assertFalse(Refinements(precision = 19, scale = 4).hasBounds)
        assertTrue(Refinements(max = BigDecimal.ONE).hasBounds)
        assertTrue(Annotations.NONE.isEmpty)
        assertEquals(emptyMap(), Annotations.NONE["sql"])
        val a = Annotations(mapOf("sql" to mapOf("key" to AnnotationValue.Flag)))
        assertEquals(mapOf("key" to AnnotationValue.Flag), a["sql"])
        assertEquals(emptyMap(), a["proto"])
    }

    @Test
    fun `every builtin declares the refinements it accepts`() {
        assertEquals(setOf("min", "max", "pattern"), Builtin.STRING.refinementKeys)
        assertEquals(setOf("min", "max"), Builtin.DECIMAL.refinementKeys)
        assertEquals(emptySet(), Builtin.BOOL.refinementKeys)
        assertEquals(emptySet(), Builtin.INSTANT.refinementKeys)
        Builtin.entries.forEach {
            assertTrue(
                it.refinementKeys.all { key -> key in setOf("min", "max", "pattern") },
                it.name,
            )
        }
    }

    @Test
    fun `a field carries a checked default and its annotations`() {
        val f =
            Field(
                1,
                "status",
                Ref(QualifiedName("a", listOf("Status"))),
                nullable = false,
                default = EnumRef(QualifiedName("a", listOf("Status")), "pending"),
                aliasName = null,
                doc = null,
                span = at,
                nameSpan = at,
                annotations =
                    Annotations(mapOf("" to mapOf("deprecated" to AnnotationValue.Str("x")))),
            )
        assertEquals(EnumRef(QualifiedName("a", listOf("Status")), "pending"), f.default)
        assertEquals(AnnotationValue.Str("x"), f.annotations[""]["deprecated"])
        assertEquals(Annotations.NONE, order.annotations)
        assertEquals(Annotations.NONE, order.fields[0].annotations)
    }
}
