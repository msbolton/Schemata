package io.schemata.evolution

import io.schemata.core.ir.AnnotationValue
import io.schemata.core.ir.Annotations
import io.schemata.core.ir.Builtin
import io.schemata.core.ir.EnumType
import io.schemata.core.ir.EnumValue
import io.schemata.core.ir.Field
import io.schemata.core.ir.ListOf
import io.schemata.core.ir.Namespace
import io.schemata.core.ir.QualifiedName
import io.schemata.core.ir.RecordType
import io.schemata.core.ir.Ref
import io.schemata.core.ir.Reserved
import io.schemata.core.ir.Scalar
import io.schemata.core.ir.Schema
import io.schemata.core.ir.Type
import io.schemata.lang.Span
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChangeContextTest {
    private fun at(line: Int = 1) = Span("x.schemata", line, 1, line, 10)

    private fun qn(ns: String, vararg path: String) = QualifiedName(ns, path.toList())

    private fun field(
        ordinal: Int,
        name: String,
        type: Type = Scalar(Builtin.BOOL),
        annotations: Annotations = Annotations.NONE,
    ) = Field(ordinal, name, type, false, null, null, null, at(), at(), annotations)

    private fun record(
        ns: String,
        name: String,
        vararg fields: Field,
        path: List<String> = listOf(name),
        reserved: Reserved = Reserved.NONE,
        annotations: Annotations = Annotations.NONE,
    ) =
        RecordType(
            qn(ns, *path.toTypedArray()),
            name,
            fields.toList(),
            reserved,
            false,
            emptyList(),
            null,
            at(),
            at(),
            annotations,
        )

    private fun namespace(name: String, vararg declarations: RecordType) =
        Namespace(name, declarations.toList(), at())

    @Test
    fun `reservedInNew reports both ordinal only name only and neither`() {
        fun schemaWith(reserved: Reserved) =
            Schema(listOf(namespace("s", record("s", "R", field(1, "x"), reserved = reserved))))

        val old = schemaWith(Reserved.NONE)
        val path = qn("s", "R")
        assertEquals(
            ReservedStatus.BOTH,
            ChangeContext(old, schemaWith(Reserved(listOf(2..2), setOf("b"))))
                .reservedInNew(path, 2, "b"),
        )
        assertEquals(
            ReservedStatus.ORDINAL_ONLY,
            ChangeContext(old, schemaWith(Reserved(listOf(2..2), emptySet())))
                .reservedInNew(path, 2, "b"),
        )
        assertEquals(
            ReservedStatus.NAME_ONLY,
            ChangeContext(old, schemaWith(Reserved(emptyList(), setOf("b"))))
                .reservedInNew(path, 2, "b"),
        )
        assertEquals(
            ReservedStatus.NEITHER,
            ChangeContext(old, schemaWith(Reserved.NONE)).reservedInNew(path, 2, "b"),
        )
    }

    @Test
    fun `deprecatedInOld reads the removed field's own annotation`() {
        val deprecated = Annotations(mapOf("" to mapOf("deprecated" to AnnotationValue.Flag)))
        val removedField = field(2, "b", annotations = deprecated)
        val plainField = field(3, "c")
        val r = record("s", "R", removedField, plainField)
        val schema = Schema(listOf(namespace("s", r)))
        val ctx = ChangeContext(schema, schema)
        assertTrue(ctx.deprecatedInOld(FieldRemoved("s.R.b", at(), r, removedField)))
        assertFalse(ctx.deprecatedInOld(FieldRemoved("s.R.c", at(), r, plainField)))
    }

    @Test
    fun `deprecatedInOld is false for an addition with no old side`() {
        val r = record("s", "R", field(1, "x"))
        val schema = Schema(listOf(namespace("s", r)))
        val ctx = ChangeContext(schema, schema)
        assertFalse(ctx.deprecatedInOld(FieldAdded("s.R.x", at(), r, field(1, "x"))))
    }

    @Test
    fun `emittedFieldName uses the override or the declared name`() {
        val ctx = ChangeContext(Schema(emptyList()), Schema(emptyList()))
        val plain = field(1, "bankTransfer")
        val protoOverride =
            field(
                1,
                "bankTransfer",
                annotations =
                    Annotations(mapOf("proto" to mapOf("name" to AnnotationValue.Str("transfer")))),
            )
        val sqlOverride =
            field(
                1,
                "bankTransfer",
                annotations =
                    Annotations(mapOf("sql" to mapOf("column" to AnnotationValue.Str("bank_xfer")))),
            )
        assertEquals("bankTransfer", ctx.emittedFieldName("proto", plain))
        assertEquals("transfer", ctx.emittedFieldName("proto", protoOverride))
        assertEquals("bank_xfer", ctx.emittedFieldName("sql", sqlOverride))
        assertEquals("bankTransfer", ctx.emittedFieldName("xsd", plain))
        assertEquals("bankTransfer", ctx.emittedFieldName("jsonschema", plain))
    }

    @Test
    fun `emittedValueName derives the proto enum prefix or uses the override`() {
        val ctx = ChangeContext(Schema(emptyList()), Schema(emptyList()))
        val enum =
            EnumType(
                qn("s", "OrderStatus"),
                "OrderStatus",
                emptyList(),
                Reserved.NONE,
                emptyList(),
                null,
                at(),
                at(),
                Annotations.NONE,
            )
        val value = EnumValue(1, "pending", null, at(), at())
        val overridden =
            EnumValue(
                1,
                "pending",
                null,
                at(),
                at(),
                Annotations(mapOf("proto" to mapOf("name" to AnnotationValue.Str("PENDING_STATE")))),
            )
        assertEquals("ORDER_STATUS_PENDING", ctx.emittedValueName("proto", enum, value))
        assertEquals("PENDING_STATE", ctx.emittedValueName("proto", enum, overridden))
        assertEquals("pending", ctx.emittedValueName("xsd", enum, value))
    }

    @Test
    fun `isKeyed reads a field flag or a record key name tuple`() {
        val keyedByField =
            record(
                "s",
                "A",
                field(
                    1,
                    "id",
                    annotations = Annotations(mapOf("sql" to mapOf("key" to AnnotationValue.Flag))),
                ),
            )
        val keyedByRecord =
            record(
                "s",
                "B",
                field(1, "id"),
                annotations =
                    Annotations(mapOf("sql" to mapOf("key" to AnnotationValue.Names(listOf("id"))))),
            )
        val unkeyed = record("s", "C", field(1, "id"))
        val schema = Schema(listOf(namespace("s", keyedByField, keyedByRecord, unkeyed)))
        val ctx = ChangeContext(schema, schema)
        assertTrue(ctx.isKeyed(Side.NEW, qn("s", "A")))
        assertTrue(ctx.isKeyed(Side.NEW, qn("s", "B")))
        assertFalse(ctx.isKeyed(Side.NEW, qn("s", "C")))
        assertEquals(ctx.isKeyed(Side.NEW, qn("s", "A")), ctx.hasTable(Side.NEW, qn("s", "A")))
        assertEquals(ctx.isKeyed(Side.NEW, qn("s", "C")), ctx.hasTable(Side.NEW, qn("s", "C")))
    }

    @Test
    fun `isRoot is a top-level record not opted out`() {
        val root = record("s", "Order")
        val optedOut =
            record(
                "s",
                "Line",
                annotations =
                    Annotations(mapOf("xsd" to mapOf("root" to AnnotationValue.Bool(false)))),
            )
        val nested =
            RecordType(
                qn("s", "Order", "Item"),
                "Item",
                emptyList(),
                Reserved.NONE,
                false,
                emptyList(),
                null,
                at(),
                at(),
                Annotations.NONE,
            )
        val schema = Schema(listOf(namespace("s", root, optedOut, nested)))
        val ctx = ChangeContext(schema, schema)
        assertTrue(ctx.isRoot(Side.NEW, qn("s", "Order")))
        assertFalse(ctx.isRoot(Side.NEW, qn("s", "Line")))
        assertFalse(ctx.isRoot(Side.NEW, qn("s", "Order", "Item")))
    }

    @Test
    fun `isOpen reads the jsonschema open flag`() {
        val open =
            record(
                "s",
                "A",
                annotations =
                    Annotations(mapOf("jsonschema" to mapOf("open" to AnnotationValue.Flag))),
            )
        val closed = record("s", "B")
        val schema = Schema(listOf(namespace("s", open, closed)))
        val ctx = ChangeContext(schema, schema)
        assertTrue(ctx.isOpen(Side.NEW, qn("s", "A")))
        assertFalse(ctx.isOpen(Side.NEW, qn("s", "B")))
    }

    @Test
    fun `hasTable also counts the element of a list of record field as a child table`() {
        val line = record("s", "Line", field(1, "sku"))
        val embedded = record("s", "Embedded", field(1, "sku"))
        val embedStrategy =
            Annotations(mapOf("sql" to mapOf("strategy" to AnnotationValue.Name("embed"))))
        val order =
            record(
                "s",
                "Order",
                field(1, "lines", ListOf(Ref(qn("s", "Line")), false)),
                field(2, "embedded", ListOf(Ref(qn("s", "Embedded")), false), embedStrategy),
            )
        val schema = Schema(listOf(namespace("s", line, embedded, order)))
        val ctx = ChangeContext(schema, schema)
        assertFalse(ctx.isKeyed(Side.NEW, qn("s", "Line")))
        assertTrue(ctx.hasTable(Side.NEW, qn("s", "Line")))
        assertFalse(ctx.hasTable(Side.NEW, qn("s", "Embedded")))
    }

    @Test
    fun `roles are false for a name that does not resolve to a record`() {
        val ctx = ChangeContext(Schema(emptyList()), Schema(emptyList()))
        val missing = qn("s", "Missing")
        assertFalse(ctx.isKeyed(Side.NEW, missing))
        assertFalse(ctx.isRoot(Side.NEW, missing))
        assertFalse(ctx.isOpen(Side.NEW, missing))
        assertFalse(ctx.hasTable(Side.NEW, missing))
    }
}
