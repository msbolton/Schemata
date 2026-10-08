package io.schemata.target.sql

import io.schemata.core.ir.Builtin
import io.schemata.core.ir.Field
import io.schemata.core.ir.ListOf
import io.schemata.core.ir.Namespace
import io.schemata.core.ir.OnDelete
import io.schemata.core.ir.QualifiedName
import io.schemata.core.ir.RecordType
import io.schemata.core.ir.Ref
import io.schemata.core.ir.Relation
import io.schemata.core.ir.Reserved
import io.schemata.core.ir.Scalar
import io.schemata.core.ir.Schema
import io.schemata.core.ir.Type
import io.schemata.lang.Span
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OnDeleteTest {
    private fun at(line: Int) = Span("o.schemata", line, 1, line, 30)

    private fun qn(name: String) = QualifiedName("s", listOf(name))

    private fun field(
        ordinal: Int,
        name: String,
        type: Type,
        nullable: Boolean = false,
        key: Boolean = false,
        virtual: Boolean = false,
    ) =
        Field(
            ordinal,
            name,
            type,
            nullable,
            null,
            null,
            null,
            at(10 + ordinal),
            at(10 + ordinal),
            key = key,
            virtual = virtual,
            backReferenceOf = if (virtual) "customer" else null,
        )

    private fun record(name: String, vararg fields: Field, line: Int = 3) =
        RecordType(
            qn(name),
            name,
            fields.toList(),
            Reserved.NONE,
            false,
            emptyList(),
            null,
            at(line),
            at(line),
        )

    private fun ref(name: String, onDelete: OnDelete = OnDelete.RESTRICT) =
        Ref(qn(name), Relation(onDelete = onDelete))

    private val customer =
        record(
            "Customer",
            field(1, "id", Scalar(Builtin.UUID), key = true),
            field(2, "orders", ListOf(Ref(qn("Order")), false), virtual = true),
        )

    private val line = record("Line", field(1, "sku", Scalar(Builtin.STRING)), line = 30)

    private val order =
        record(
            "Order",
            field(1, "id", Scalar(Builtin.UUID), key = true),
            field(2, "customer", ref("Customer", OnDelete.CASCADE)),
            field(3, "referrer", ref("Customer", OnDelete.SET_NULL), nullable = true),
            field(4, "seller", ref("Customer")),
            field(5, "lines", ListOf(Ref(qn("Line")), false)),
            line = 20,
        )

    private val model =
        SqlLowering.lower(Schema(listOf(Namespace("s", listOf(customer, order, line), at(1)))))

    private val schema = model.model.schemas.single()

    private fun key(name: String) = schema.foreignKeys.single { it.name == name }

    @Test
    fun `a reference's foreign key acts on delete as its relation says`() {
        assertEquals(emptyList(), model.diagnostics.map { it.message })
        assertEquals(OnDelete.CASCADE, key("fk_order_customer").onDelete)
        assertEquals(OnDelete.SET_NULL, key("fk_order_referrer").onDelete)
        assertEquals(OnDelete.RESTRICT, key("fk_order_seller").onDelete)
    }

    @Test
    fun `a child table's key to its parent cascades`() {
        assertEquals(OnDelete.CASCADE, key("fk_order_lines_order").onDelete)
    }

    @Test
    fun `the DDL spells set null and cascade and leaves restrict unsaid`() {
        assertTrue(Ddl.addForeignKey(key("fk_order_referrer")).endsWith(" ON DELETE SET NULL;"))
        assertTrue(Ddl.addForeignKey(key("fk_order_customer")).endsWith(" ON DELETE CASCADE;"))
        assertTrue(Ddl.addForeignKey(key("fk_order_seller")).endsWith("(\"id\");"))
    }

    @Test
    fun `a virtual field produces no column and no child table`() {
        val customerTable = schema.tables.single { it.name == "customer" }
        assertEquals(listOf("id"), customerTable.columns.map { it.name })
        assertTrue(schema.tables.none { it.name == "customer_orders" })
    }
}
