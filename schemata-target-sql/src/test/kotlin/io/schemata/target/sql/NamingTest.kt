package io.schemata.target.sql

import io.schemata.core.ir.AnnotationValue
import io.schemata.core.ir.Annotations
import io.schemata.core.ir.BoolValue
import io.schemata.core.ir.Builtin
import io.schemata.core.ir.EnumRef
import io.schemata.core.ir.Field
import io.schemata.core.ir.IntValue
import io.schemata.core.ir.Namespace
import io.schemata.core.ir.QualifiedName
import io.schemata.core.ir.RealValue
import io.schemata.core.ir.RecordType
import io.schemata.core.ir.Reserved
import io.schemata.core.ir.Scalar
import io.schemata.core.ir.StringValue
import io.schemata.lang.Span
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NamingTest {
    private val at = Span("t.schemata", 1, 1, 1, 1)

    private fun sql(vararg pairs: Pair<String, String>) =
        Annotations(mapOf("sql" to pairs.associate { (k, v) -> k to AnnotationValue.Str(v) }))

    @Test
    fun `converts UpperCamel to lower_snake with the shared rule`() {
        assertEquals("user", Naming.snakeCase("User"))
        assertEquals("order_line", Naming.snakeCase("OrderLine"))
        assertEquals("http2_server", Naming.snakeCase("Http2Server"))
        assertEquals("http_status", Naming.snakeCase("HTTPStatus"))
    }

    @Test
    fun `quotes identifiers and literals`() {
        assertEquals("\"user\"", Naming.quote("user"))
        assertEquals("\"we\"\"ird\"", Naming.quote("we\"ird"))
        assertEquals("'n/a'", Naming.literal("n/a"))
        assertEquals("'it''s'", Naming.literal("it's"))
        assertEquals("3", Naming.literal(IntValue(3)))
        assertEquals("1.25", Naming.literal(RealValue(BigDecimal("1.25"))))
        assertEquals(
            "'pending'",
            Naming.literal(EnumRef(QualifiedName("a", listOf("Status")), "pending")),
        )
        assertEquals("true", Naming.literal(BoolValue(true)))
        assertEquals("'x'", Naming.literal(StringValue("x")))
    }

    @Test
    fun `identifiers over 63 characters are truncated with a stable hash`() {
        val long =
            "a_very_long_field_name_that_goes_well_beyond_the_sixty_three_character_limit_of_postgres"
        assertEquals(88, long.length)
        assertTrue(Naming.truncated(long))
        val short = Naming.identifier(long)
        assertEquals("a_very_long_field_name_that_goes_well_beyond_the_sixty__b7ff5eb", short)
        assertEquals(63, short.length)
        assertFalse(Naming.truncated("order_line"))
        assertEquals("order_line", Naming.identifier("order_line"))
        assertEquals("a".repeat(63), Naming.identifier("a".repeat(63)))
        assertEquals(60, Naming.identifier("t".repeat(61), reserve = 3).length)
        assertEquals("t".repeat(60), Naming.identifier("t".repeat(60), reserve = 3))
    }

    @Test
    fun `derived names honour sql overrides`() {
        val ns = Namespace("shop.orders", emptyList(), at)
        assertEquals("orders", Naming.schemaOf(ns))
        assertEquals("shop", Naming.schemaOf(ns.copy(annotations = sql("schema" to "shop"))))
        val record =
            RecordType(
                QualifiedName("a", listOf("HTTPStatus")),
                "HTTPStatus",
                emptyList(),
                Reserved.NONE,
                false,
                emptyList(),
                null,
                at,
                at,
            )
        assertEquals("http_status", Naming.tableOf(record))
        assertEquals("codes", Naming.tableOf(record.copy(annotations = sql("table" to "codes"))))
        val field = Field(1, "reason", Scalar(Builtin.STRING), false, null, null, null, at, at)
        assertEquals("reason", Naming.columnOf(field))
        assertEquals(
            "reason_phrase",
            Naming.columnOf(field.copy(annotations = sql("column" to "reason_phrase"))),
        )
        assertEquals("citext", Naming.override(sql("type" to "citext"), "type"))
        assertEquals(null, Naming.override(Annotations.NONE, "type"))
    }
}
