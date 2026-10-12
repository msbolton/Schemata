package io.schemata.target.sql

import io.schemata.core.ir.AnnotationValue
import io.schemata.core.ir.Annotations
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
import io.schemata.target.Lowered
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
        annotations: Annotations = Annotations.NONE,
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
            annotations = annotations,
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

    private val json =
        Annotations(mapOf("sql" to mapOf("strategy" to AnnotationValue.Name("json"))))

    /** A keyed `Customer` and a `Holder` carrying [fields] after its key. */
    private fun holder(vararg fields: Field): Lowered<RelationalModel> {
        val target = record("Customer", field(1, "id", Scalar(Builtin.UUID), key = true))
        val holder =
            record("Holder", field(1, "id", Scalar(Builtin.UUID), key = true), *fields, line = 20)
        return SqlLowering.lower(Schema(listOf(Namespace("s", listOf(target, holder), at(1)))))
    }

    private fun ddl(lowered: Lowered<RelationalModel>) =
        SqlRenderer.render(lowered.model).single().content

    @Test
    fun `on delete on a json reference warns`() {
        val lowered =
            holder(field(2, "customer", ref("Customer", OnDelete.CASCADE), annotations = json))
        val plain = holder(field(2, "customer", ref("Customer"), annotations = json))
        assertEquals(
            listOf(
                "field 'Holder.customer': model contents are not typed by Postgres; lowered to jsonb",
                "field 'Holder.customer': onDelete is not enforced; the reference is lowered to jsonb",
            ),
            lowered.diagnostics.map { it.message },
        )
        assertEquals(
            "remove onDelete, or reference the model by key",
            lowered.diagnostics.last().help,
        )
        assertEquals(1, plain.diagnostics.size)
        assertEquals(ddl(plain), ddl(lowered))
    }

    @Test
    fun `on delete on an embedded reference warns`() {
        val lowered =
            holder(
                field(
                    2,
                    "customer",
                    Ref(qn("Customer"), Relation(embed = true, onDelete = OnDelete.SET_NULL)),
                    nullable = true,
                )
            )
        val plain =
            holder(
                field(2, "customer", Ref(qn("Customer"), Relation(embed = true)), nullable = true)
            )
        assertEquals(
            listOf(
                "field 'Holder.customer': onDelete is not enforced; the reference is lowered to embedded columns"
            ),
            lowered.diagnostics.map { it.message },
        )
        assertEquals(
            "remove onDelete, or reference the model by key",
            lowered.diagnostics.single().help,
        )
        assertEquals(emptyList(), plain.diagnostics.map { it.message })
        assertEquals(ddl(plain), ddl(lowered))
    }

    @Test
    fun `on delete on a json list warns`() {
        val lowered =
            holder(
                field(
                    2,
                    "customers",
                    ListOf(ref("Customer", OnDelete.CASCADE), false),
                    annotations = json,
                )
            )
        val plain =
            holder(field(2, "customers", ListOf(ref("Customer"), false), annotations = json))
        assertEquals(
            listOf(
                "field 'Holder.customers': list contents are not typed by Postgres; lowered to jsonb",
                "field 'Holder.customers': onDelete is not enforced; the reference is lowered to jsonb",
            ),
            lowered.diagnostics.map { it.message },
        )
        assertEquals(ddl(plain), ddl(lowered))
    }

    @Test
    fun `on delete on an embedded list warns`() {
        val lowered =
            holder(
                field(
                    2,
                    "customers",
                    ListOf(
                        Ref(qn("Customer"), Relation(embed = true, onDelete = OnDelete.CASCADE)),
                        false,
                    ),
                )
            )
        val plain =
            holder(
                field(2, "customers", ListOf(Ref(qn("Customer"), Relation(embed = true)), false))
            )
        assertEquals(
            listOf(
                "field 'Holder.customers': onDelete is not enforced; the reference is lowered to embedded columns"
            ),
            lowered.diagnostics.map { it.message },
        )
        assertEquals(emptyList(), plain.diagnostics.map { it.message })
        assertEquals(ddl(plain), ddl(lowered))
    }
}
