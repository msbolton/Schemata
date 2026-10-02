package io.schemata.evolution

import io.schemata.core.ir.AnnotationValue
import io.schemata.core.ir.Annotations
import io.schemata.core.ir.Builtin
import io.schemata.core.ir.EnumType
import io.schemata.core.ir.EnumValue
import io.schemata.core.ir.Field
import io.schemata.core.ir.IntValue
import io.schemata.core.ir.ListOf
import io.schemata.core.ir.MapOf
import io.schemata.core.ir.Namespace
import io.schemata.core.ir.QualifiedName
import io.schemata.core.ir.RecordType
import io.schemata.core.ir.Refinements
import io.schemata.core.ir.Reserved
import io.schemata.core.ir.Scalar
import io.schemata.core.ir.Schema
import io.schemata.core.ir.Type
import io.schemata.core.ir.TypeDecl
import io.schemata.core.ir.UnionMember
import io.schemata.core.ir.UnionType
import io.schemata.core.ir.Value
import io.schemata.lang.Span
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DifferTest {
    private fun at(line: Int) = Span("orders.schemata", line, 3, line, 20)

    private fun qn(ns: String, vararg path: String) = QualifiedName(ns, path.toList())

    private fun namespace(
        name: String,
        annotations: Annotations = Annotations.NONE,
        line: Int = 1,
        declarations: List<TypeDecl> = emptyList(),
    ) = Namespace(name, declarations, at(line), annotations)

    private fun field(
        ordinal: Int,
        name: String,
        type: Type,
        nullable: Boolean = false,
        default: Value? = null,
        line: Int = 10 + ordinal,
        doc: String? = null,
        annotations: Annotations = Annotations.NONE,
    ) = Field(ordinal, name, type, nullable, default, null, doc, at(line), at(line), annotations)

    private fun record(
        ns: String,
        name: String,
        vararg fields: Field,
        path: List<String> = listOf(name),
        nested: List<TypeDecl> = emptyList(),
        reserved: Reserved = Reserved.NONE,
        line: Int = 3,
        doc: String? = null,
        annotations: Annotations = Annotations.NONE,
    ) =
        RecordType(
            qn(ns, *path.toTypedArray()),
            name,
            fields.toList(),
            reserved,
            false,
            nested,
            doc,
            at(line),
            at(line),
            annotations,
        )

    private fun enum(
        ns: String,
        name: String,
        vararg values: String,
        reserved: Reserved = Reserved.NONE,
        line: Int = 30,
        annotations: Annotations = Annotations.NONE,
    ) =
        EnumType(
            qn(ns, name),
            name,
            values.mapIndexed { i, v ->
                EnumValue(i + 1, v, null, at(line + 1 + i), at(line + 1 + i))
            },
            reserved,
            emptyList(),
            null,
            at(line),
            at(line),
            annotations,
        )

    private fun union(
        ns: String,
        name: String,
        vararg members: Type,
        line: Int = 40,
        annotations: Annotations = Annotations.NONE,
    ) =
        UnionType(
            qn(ns, name),
            name,
            members.mapIndexed { i, t -> UnionMember(i + 1, t, null, at(line + 1 + i)) },
            emptyList(),
            null,
            at(line),
            at(line),
            annotations,
        )

    /** Wraps a single declaration into the namespace it names itself. */
    private fun ns(decl: TypeDecl) =
        namespace(decl.qualifiedName.namespace, declarations = listOf(decl))

    private fun shop(): Schema {
        val order = record("shop.orders", "Order", field(1, "id", Scalar(Builtin.INT64)))
        return Schema(listOf(namespace("shop.orders", declarations = listOf(order))))
    }

    private fun diff(old: Namespace, new: Namespace) =
        Differ.diff(Schema(listOf(old)), Schema(listOf(new)))

    private fun diff(old: Schema, new: Schema) = Differ.diff(old, new)

    @Test
    fun `identical schemas have no changes`() {
        assertEquals(emptyList(), diff(shop(), shop()))
    }

    @Test
    fun `a field added removed and renamed by ordinal`() {
        val old =
            record(
                "s",
                "R",
                field(1, "a", Scalar(Builtin.BOOL)),
                field(2, "b", Scalar(Builtin.BOOL)),
            )
        val new =
            record(
                "s",
                "R",
                field(1, "a", Scalar(Builtin.BOOL)),
                field(2, "bee", Scalar(Builtin.BOOL)),
                field(3, "c", Scalar(Builtin.BOOL)),
            )
        val changes = diff(ns(old), ns(new))
        assertEquals(listOf("field.renamed", "field.added"), changes.map { it.kind })
        assertEquals(listOf("s.R.bee", "s.R.c"), changes.map { it.path })
        val removed = diff(ns(new), ns(old)).single { it.kind == "field.removed" }
        assertEquals("s.R.c", removed.path)
        assertEquals(13, removed.span.startLine) // OLD side's line for a removal (at(10 + ordinal))
    }

    @Test
    fun `type refinement nullability default and doc changes are told apart`() {
        val old =
            record(
                "s",
                "R",
                field(1, "a", Scalar(Builtin.INT32)),
                field(2, "b", Scalar(Builtin.STRING, Refinements(max = BigDecimal(10)))),
                field(3, "c", Scalar(Builtin.BOOL)),
                field(4, "d", Scalar(Builtin.INT32), default = IntValue(1)),
                field(5, "e", Scalar(Builtin.BOOL), doc = "x"),
            )
        val new =
            record(
                "s",
                "R",
                field(1, "a", Scalar(Builtin.INT64)),
                field(2, "b", Scalar(Builtin.STRING, Refinements(max = BigDecimal(5)))),
                field(3, "c", Scalar(Builtin.BOOL), nullable = true),
                field(4, "d", Scalar(Builtin.INT32), default = IntValue(2)),
                field(5, "e", Scalar(Builtin.BOOL), doc = "y"),
            )
        assertEquals(
            listOf(
                "field.typeChanged",
                "field.refinementChanged",
                "field.nullabilityChanged",
                "field.defaultChanged",
                "doc.changed",
            ),
            diff(ns(old), ns(new)).map { it.kind },
        )
        assertTrue((diff(ns(old), ns(new))[1] as FieldRefinementChanged).tightened)
        assertFalse((diff(ns(new), ns(old))[1] as FieldRefinementChanged).tightened)
    }

    @Test
    fun `a list element bound change is a refinement change`() {
        fun list(max: Int) =
            ListOf(Scalar(Builtin.STRING, Refinements(max = BigDecimal(max))), false)
        val old = record("s", "R", field(1, "tags", list(20)))
        val new = record("s", "R", field(1, "tags", list(10)))
        val tightened = diff(ns(old), ns(new)).single() as FieldRefinementChanged
        assertTrue(tightened.tightened)
        assertFalse((diff(ns(new), ns(old)).single() as FieldRefinementChanged).tightened)
    }

    @Test
    fun `a map key or value bound change is a refinement change`() {
        fun map(keyMax: Int, valueMin: Int) =
            MapOf(
                Scalar(Builtin.STRING, Refinements(max = BigDecimal(keyMax))),
                Scalar(Builtin.INT32, Refinements(min = BigDecimal(valueMin))),
                false,
            )
        val old = record("s", "R", field(1, "m", map(16, 0)))
        val keyTightened = record("s", "R", field(1, "m", map(8, 0)))
        val valueLoosened = record("s", "R", field(1, "m", map(16, -5)))
        assertTrue((diff(ns(old), ns(keyTightened)).single() as FieldRefinementChanged).tightened)
        assertFalse((diff(ns(old), ns(valueLoosened)).single() as FieldRefinementChanged).tightened)
    }

    @Test
    fun `a nested decimal precision or scale change is a type change`() {
        fun decimals(precision: Int, scale: Int) =
            MapOf(
                Scalar(Builtin.STRING),
                Scalar(Builtin.DECIMAL, Refinements(precision = precision, scale = scale)),
                false,
            )
        val old = record("s", "R", field(1, "prices", decimals(19, 4)))
        val scaled = record("s", "R", field(1, "prices", decimals(19, 2)))
        val listed =
            record(
                "s",
                "R",
                field(
                    1,
                    "prices",
                    ListOf(Scalar(Builtin.DECIMAL, Refinements(precision = 10, scale = 2)), false),
                ),
            )
        val listedWider =
            record(
                "s",
                "R",
                field(
                    1,
                    "prices",
                    ListOf(Scalar(Builtin.DECIMAL, Refinements(precision = 12, scale = 2)), false),
                ),
            )
        assertEquals(listOf("field.typeChanged"), diff(ns(old), ns(scaled)).map { it.kind })
        assertEquals(listOf("field.typeChanged"), diff(ns(listed), ns(listedWider)).map { it.kind })
    }

    @Test
    fun `an annotation change carries its owner on both sides`() {
        val pin = Annotations(mapOf("sql" to mapOf("column" to AnnotationValue.Str("note"))))
        val old = record("s", "R", field(9, "note", Scalar(Builtin.STRING)))
        val new = record("s", "R", field(9, "comment", Scalar(Builtin.STRING), annotations = pin))
        val change = diff(ns(old), ns(new)).filterIsInstance<AnnotationChanged>().single()
        assertEquals("note", (change.oldOwner as FieldOwner).field.name)
        assertEquals("comment", (change.newOwner as FieldOwner).field.name)
    }

    @Test
    fun `declarations namespaces enums unions reserved annotations and deprecation`() {
        // declaration.added, declaration.removed, declaration.kindChanged: a record becomes a union
        val oldR = record("s", "R", field(1, "x", Scalar(Builtin.BOOL)))
        val newU = union("s", "R", Scalar(Builtin.BOOL))
        val added = record("s", "Added", field(1, "x", Scalar(Builtin.BOOL)))
        val removed = record("s", "Removed", field(1, "x", Scalar(Builtin.BOOL)))
        val oldNs = namespace("s", declarations = listOf(oldR, removed))
        val newNs = namespace("s", declarations = listOf(newU, added))
        val declChanges = Differ.diff(Schema(listOf(oldNs)), Schema(listOf(newNs)))
        assertEquals(
            listOf("declaration.kindChanged", "declaration.added", "declaration.removed"),
            declChanges.map { it.kind },
        )
        assertEquals(listOf("s.R", "s.Added", "s.Removed"), declChanges.map { it.path })
        assertEquals(oldR, (declChanges[0] as DeclarationKindChanged).from)
        assertEquals(newU, (declChanges[0] as DeclarationKindChanged).to)

        // namespace.added, namespace.removed
        val a = namespace("a", line = 1)
        val b = namespace("b", line = 2)
        val nsChanges = Differ.diff(Schema(listOf(a)), Schema(listOf(b)))
        assertEquals(listOf("namespace.added", "namespace.removed"), nsChanges.map { it.kind })
        assertEquals(listOf("b", "a"), nsChanges.map { it.path })

        // enumValue.added, enumValue.removed, enumValue.renamed
        val oldEnum = enum("s", "E", "one", "two")
        val newEnum =
            EnumType(
                qn("s", "E"),
                "E",
                listOf(
                    EnumValue(1, "uno", null, at(31), at(31)),
                    EnumValue(3, "three", null, at(33), at(33)),
                ),
                Reserved.NONE,
                emptyList(),
                null,
                at(30),
                at(30),
                Annotations.NONE,
            )
        val enumChanges = diff(ns(oldEnum), ns(newEnum))
        assertEquals(
            listOf("enumValue.renamed", "enumValue.added", "enumValue.removed"),
            enumChanges.map { it.kind },
        )
        assertEquals(listOf("s.E.uno", "s.E.three", "s.E.two"), enumChanges.map { it.path })

        // unionMember.added, unionMember.removed, unionMember.typeChanged
        val oldUnion = union("s", "U", Scalar(Builtin.BOOL), Scalar(Builtin.INT32))
        val newUnion =
            union("s", "U", Scalar(Builtin.STRING), Scalar(Builtin.INT32), Scalar(Builtin.INT64))
        val unionChanges = diff(ns(oldUnion), ns(newUnion))
        assertEquals(
            listOf("unionMember.typeChanged", "unionMember.added"),
            unionChanges.map { it.kind },
        )
        assertEquals(listOf("s.U.#1", "s.U.#3"), unionChanges.map { it.path })
        val extraRemoved =
            diff(
                    ns(
                        union(
                            "s",
                            "U",
                            Scalar(Builtin.BOOL),
                            Scalar(Builtin.INT32),
                            Scalar(Builtin.INT64),
                        )
                    ),
                    ns(oldUnion),
                )
                .single { it.kind == "unionMember.removed" }
        assertEquals("s.U.#3", extraRemoved.path)

        // reserved.changed: ordinals and names
        val oldReservedRecord =
            record(
                "s",
                "R2",
                field(1, "x", Scalar(Builtin.BOOL)),
                reserved = Reserved(listOf(2..3), setOf("old")),
            )
        val newReservedRecord =
            record(
                "s",
                "R2",
                field(1, "x", Scalar(Builtin.BOOL)),
                reserved = Reserved(listOf(2..4), setOf("old", "new")),
            )
        val reservedChange = diff(ns(oldReservedRecord), ns(newReservedRecord)).single()
        assertEquals("reserved.changed", reservedChange.kind)
        assertEquals("s.R2", reservedChange.path)
        assertEquals(Reserved(listOf(2..3), setOf("old")), (reservedChange as ReservedChanged).from)
        assertEquals(Reserved(listOf(2..4), setOf("old", "new")), reservedChange.to)

        // annotation.changed: target "sql", key "column", from null to Str("x")
        val oldAnnotated = record("s", "R3", field(1, "x", Scalar(Builtin.BOOL)))
        val newAnnotated =
            record(
                "s",
                "R3",
                field(1, "x", Scalar(Builtin.BOOL)),
                annotations =
                    Annotations(mapOf("sql" to mapOf("column" to AnnotationValue.Str("x")))),
            )
        val annotationChange =
            diff(ns(oldAnnotated), ns(newAnnotated)).single() as AnnotationChanged
        assertEquals("annotation.changed", annotationChange.kind)
        assertEquals("s.R3", annotationChange.path)
        assertEquals("sql", annotationChange.target)
        assertEquals("column", annotationChange.key)
        assertEquals(null, annotationChange.from)
        assertEquals(AnnotationValue.Str("x"), annotationChange.to)

        // deprecation.changed: true when @deprecated appears in NEW
        val oldPlain = record("s", "R4", field(1, "x", Scalar(Builtin.BOOL)))
        val newDeprecated =
            record(
                "s",
                "R4",
                field(1, "x", Scalar(Builtin.BOOL)),
                annotations = Annotations(mapOf("" to mapOf("deprecated" to AnnotationValue.Flag))),
            )
        val deprecationChange = diff(ns(oldPlain), ns(newDeprecated)).single() as DeprecationChanged
        assertEquals("deprecation.changed", deprecationChange.kind)
        assertEquals("s.R4", deprecationChange.path)
        assertTrue(deprecationChange.deprecated)
    }

    @Test
    fun `a namespace annotation change is reported on the namespace`() {
        val record = record("s", "R", field(1, "x", Scalar(Builtin.BOOL)))
        val old =
            namespace(
                "s",
                annotations =
                    Annotations(mapOf("proto" to mapOf("package" to AnnotationValue.Str("s.v1")))),
                declarations = listOf(record),
            )
        val new =
            namespace(
                "s",
                annotations =
                    Annotations(mapOf("proto" to mapOf("package" to AnnotationValue.Str("s.v2")))),
                declarations = listOf(record),
            )
        val change = diff(old, new).single() as AnnotationChanged
        assertEquals("annotation.changed", change.kind)
        assertEquals("s", change.path)
        assertEquals("proto", change.target)
        assertEquals("package", change.key)
        assertEquals(AnnotationValue.Str("s.v1"), change.from)
        assertEquals(AnnotationValue.Str("s.v2"), change.to)
    }

    @Test
    fun `changes come in new side order then removals`() {
        val keep = record("s", "Keep", field(1, "x", Scalar(Builtin.BOOL)))
        val oldOnly = record("s", "OldOnly", field(1, "x", Scalar(Builtin.BOOL)))
        val newOnly = record("s", "NewOnly", field(1, "x", Scalar(Builtin.BOOL)))
        val old = namespace("s", declarations = listOf(oldOnly, keep))
        val new = namespace("s", declarations = listOf(newOnly, keep))
        val changes = Differ.diff(Schema(listOf(old)), Schema(listOf(new)))
        assertEquals(listOf("declaration.added", "declaration.removed"), changes.map { it.kind })
        assertEquals(listOf("s.NewOnly", "s.OldOnly"), changes.map { it.path })
    }

    @Test
    fun `an enum value doc change is reported on the value's path`() {
        val old =
            EnumType(
                qn("s", "Status"),
                "Status",
                listOf(EnumValue(1, "pending", null, at(31), at(31))),
                Reserved.NONE,
                emptyList(),
                null,
                at(30),
                at(30),
                Annotations.NONE,
            )
        val new =
            EnumType(
                qn("s", "Status"),
                "Status",
                listOf(EnumValue(1, "pending", "Waiting to be paid.", at(31), at(31))),
                Reserved.NONE,
                emptyList(),
                null,
                at(30),
                at(30),
                Annotations.NONE,
            )
        val change = diff(ns(old), ns(new)).single()
        assertEquals("doc.changed", change.kind)
        assertEquals("s.Status.pending", change.path)
    }

    @Test
    fun `an enum value xsd name annotation change is reported on the value's path`() {
        val old =
            EnumType(
                qn("s", "Status"),
                "Status",
                listOf(EnumValue(1, "pending", null, at(31), at(31), Annotations.NONE)),
                Reserved.NONE,
                emptyList(),
                null,
                at(30),
                at(30),
                Annotations.NONE,
            )
        val new =
            EnumType(
                qn("s", "Status"),
                "Status",
                listOf(
                    EnumValue(
                        1,
                        "pending",
                        null,
                        at(31),
                        at(31),
                        Annotations(mapOf("xsd" to mapOf("name" to AnnotationValue.Str("Pending")))),
                    )
                ),
                Reserved.NONE,
                emptyList(),
                null,
                at(30),
                at(30),
                Annotations.NONE,
            )
        val change = diff(ns(old), ns(new)).single() as AnnotationChanged
        assertEquals("annotation.changed", change.kind)
        assertEquals("s.Status.pending", change.path)
        assertEquals("xsd", change.target)
        assertEquals("name", change.key)
        assertEquals(null, change.from)
        assertEquals(AnnotationValue.Str("Pending"), change.to)
    }

    @Test
    fun `a union member doc change is reported on an ordinal path`() {
        val old =
            UnionType(
                qn("s", "Payment"),
                "Payment",
                listOf(UnionMember(1, Scalar(Builtin.BOOL), null, at(41))),
                emptyList(),
                null,
                at(40),
                at(40),
                Annotations.NONE,
            )
        val new =
            UnionType(
                qn("s", "Payment"),
                "Payment",
                listOf(UnionMember(1, Scalar(Builtin.BOOL), "Paid by card.", at(41))),
                emptyList(),
                null,
                at(40),
                at(40),
                Annotations.NONE,
            )
        val change = diff(ns(old), ns(new)).single()
        assertEquals("doc.changed", change.kind)
        assertEquals("s.Payment.#1", change.path)
    }

    @Test
    fun `a deprecation reason change alone is an annotation change not a deprecation change`() {
        val old =
            record(
                "s",
                "R5",
                field(1, "x", Scalar(Builtin.BOOL)),
                annotations =
                    Annotations(mapOf("" to mapOf("deprecated" to AnnotationValue.Str("a")))),
            )
        val new =
            record(
                "s",
                "R5",
                field(1, "x", Scalar(Builtin.BOOL)),
                annotations =
                    Annotations(mapOf("" to mapOf("deprecated" to AnnotationValue.Str("b")))),
            )
        val change = diff(ns(old), ns(new)).single() as AnnotationChanged
        assertEquals("annotation.changed", change.kind)
        assertEquals("s.R5", change.path)
        assertEquals("", change.target)
        assertEquals("deprecated", change.key)
        assertEquals(AnnotationValue.Str("a"), change.from)
        assertEquals(AnnotationValue.Str("b"), change.to)
    }

    @Test
    fun `a nested declaration's field rename and its removal are pathed through the outer declaration`() {
        val oldLine =
            record(
                "s",
                "Line",
                field(1, "code", Scalar(Builtin.STRING)),
                path = listOf("Order", "Line"),
                line = 5,
            )
        val oldOrder = record("s", "Order", nested = listOf(oldLine), line = 3)
        val newLine =
            record(
                "s",
                "Line",
                field(1, "sku", Scalar(Builtin.STRING)),
                path = listOf("Order", "Line"),
                line = 5,
            )
        val newOrder = record("s", "Order", nested = listOf(newLine), line = 3)

        val renameChanges = diff(ns(oldOrder), ns(newOrder))
        assertEquals(listOf("field.renamed"), renameChanges.map { it.kind })
        assertEquals(listOf("s.Order.Line.sku"), renameChanges.map { it.path })

        val removalChanges = diff(ns(oldOrder), namespace("s"))
        assertEquals(
            listOf("declaration.removed", "declaration.removed"),
            removalChanges.map { it.kind },
        )
        assertEquals(listOf("s.Order", "s.Order.Line"), removalChanges.map { it.path })
    }
}
