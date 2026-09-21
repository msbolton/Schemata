package io.schemata.target.sql

import io.schemata.core.annotations.Element
import io.schemata.core.annotations.ValueKind
import io.schemata.core.ir.AnnotationValue
import io.schemata.core.ir.Annotations
import io.schemata.core.ir.Builtin
import io.schemata.core.ir.EnumType
import io.schemata.core.ir.EnumValue
import io.schemata.core.ir.Field
import io.schemata.core.ir.IntValue
import io.schemata.core.ir.ListOf
import io.schemata.core.ir.Namespace
import io.schemata.core.ir.QualifiedName
import io.schemata.core.ir.RecordType
import io.schemata.core.ir.Ref
import io.schemata.core.ir.Refinements
import io.schemata.core.ir.Reserved
import io.schemata.core.ir.Scalar
import io.schemata.core.ir.Schema
import io.schemata.core.ir.StringValue
import io.schemata.core.ir.Type
import io.schemata.core.ir.TypeDecl
import io.schemata.core.ir.Value
import io.schemata.lang.Span
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SqlLoweringTest {
    private fun at(file: String, line: Int) = Span(file, line, 1, line, 30)

    private fun field(
        ordinal: Int,
        name: String,
        type: Type,
        nullable: Boolean = false,
        default: Value? = null,
    ) =
        Field(
            ordinal,
            name,
            type,
            nullable,
            default,
            null,
            null,
            at("o.schemata", 10 + ordinal),
            at("o.schemata", 10 + ordinal),
        )

    private fun namespace(name: String, vararg decls: TypeDecl, file: String = "o.schemata") =
        Namespace(name, decls.toList(), at(file, 1))

    private fun record(
        ns: String,
        name: String,
        vararg fields: Field,
        nested: List<TypeDecl> = emptyList(),
        line: Int = 3,
    ) =
        RecordType(
            QualifiedName(ns, listOf(name)),
            name,
            fields.toList(),
            Reserved.NONE,
            false,
            nested,
            null,
            at("o.schemata", line),
            at("o.schemata", line),
        )

    private val orders =
        namespace(
            "shop.orders",
            record(
                "shop.orders",
                "OrderLine",
                field(1, "id", Scalar(Builtin.UUID)),
                field(2, "note", Scalar(Builtin.STRING), nullable = true),
                field(3, "quantity", Scalar(Builtin.INT32)),
                field(4, "gift", Scalar(Builtin.BOOL)),
            ),
        )

    @Test
    fun `emits one relational schema per namespace with path and last-segment name`() {
        val customers =
            namespace(
                "shop.customers",
                record("shop.customers", "Customer", field(1, "name", Scalar(Builtin.STRING))),
            )
        val schemas = SqlLowering.lower(Schema(listOf(customers, orders))).model.schemas
        assertEquals(
            listOf("shop/customers.sql" to "customers", "shop/orders.sql" to "orders"),
            schemas.map { it.path to it.schemaName },
        )
    }

    @Test
    fun `lowers flat scalar records to tables without diagnostics`() {
        val lowered = SqlLowering.lower(Schema(listOf(orders)))
        assertEquals(emptyList(), lowered.diagnostics)
        val table = lowered.model.schemas.single().tables.single()
        assertEquals("order_line", table.name)
        assertEquals(listOf("id", "note", "quantity", "gift"), table.columns.map { it.name })
        assertEquals(
            listOf(ColumnType.UUID, ColumnType.TEXT, ColumnType.INTEGER, ColumnType.BOOLEAN),
            table.columns.map { it.type },
        )
        assertEquals(listOf(false, true, false, false), table.columns.map { it.nullable })
    }

    @Test
    fun `diagnoses table-name and schema-name collisions`() {
        val ns =
            namespace(
                "shop.orders",
                record("shop.orders", "Abc", field(1, "id", Scalar(Builtin.UUID)), line = 3),
                record("shop.orders", "ABC", field(1, "id", Scalar(Builtin.UUID)), line = 4),
            )
        val d = SqlLowering.lower(Schema(listOf(ns))).diagnostics.single()
        assertEquals("records Abc and ABC both lower to table 'abc'", d.message)
        assertEquals(3, d.span.startLine)
        val a =
            namespace(
                "a.x",
                record("a.x", "A", field(1, "id", Scalar(Builtin.UUID))),
                file = "a.schemata",
            )
        val b =
            namespace(
                "b.x",
                record("b.x", "B", field(1, "id", Scalar(Builtin.UUID))),
                file = "b.schemata",
            )
        val c =
            namespace(
                "c.x",
                record("c.x", "C", field(1, "id", Scalar(Builtin.UUID))),
                file = "c.schemata",
            )
        assertEquals(
            "namespaces a.x, b.x and c.x all lower to schema 'x'",
            SqlLowering.lower(Schema(listOf(a, b, c))).diagnostics.single().message,
        )
    }

    @Test
    fun `reports every shape it cannot lower yet and omits those columns`() {
        val ns = "a"
        val leaf = record(ns, "Leaf", field(1, "v", Scalar(Builtin.INT32)), line = 2)
        val inner = record(ns, "Inner", field(1, "z", Scalar(Builtin.BOOL)), line = 20)
        val status =
            EnumType(
                QualifiedName(ns, listOf("Status")),
                "Status",
                listOf(EnumValue(1, "x", null, at("o.schemata", 30), at("o.schemata", 30))),
                Reserved.NONE,
                emptyList(),
                null,
                at("o.schemata", 30),
                at("o.schemata", 30),
            )
        val r =
            record(
                ns,
                "R",
                field(1, "ok", Scalar(Builtin.STRING)),
                field(2, "ref", Ref(leaf.qualifiedName)),
                field(3, "when", Scalar(Builtin.INSTANT)),
                field(4, "many", ListOf(Scalar(Builtin.STRING), false)),
                field(5, "dflt", Scalar(Builtin.STRING), default = StringValue("x")),
                nested = listOf(inner),
                line = 10,
            )
        val lowered = SqlLowering.lower(Schema(listOf(namespace(ns, leaf, status, r))))
        assertEquals(
            listOf(
                "30 SCH2103 target 'sql' cannot lower enums yet (SCH-28)",
                "12 SCH2103 field 'R.ref': target 'sql' cannot lower record references yet (SCH-28)",
                "13 SCH2103 field 'R.when': target 'sql' cannot lower instant yet (SCH-31)",
                "14 SCH2103 field 'R.many': target 'sql' cannot lower lists yet (SCH-28)",
                "15 SCH2104 field 'R.dflt': target 'sql' cannot lower field defaults yet (SCH-32)",
                "20 SCH2103 target 'sql' cannot lower nested declarations yet (SCH-28)",
            ),
            lowered.diagnostics.map { "${it.span.startLine} ${it.code.id} ${it.message}" },
        )
        assertEquals(
            listOf("ok"),
            lowered.model.schemas.single().tables.single { it.name == "r" }.columns.map { it.name },
        )
    }

    @Test
    fun `refinements anywhere in a type are reported`() {
        val r =
            record(
                "a",
                "R",
                field(1, "s", Scalar(Builtin.STRING, Refinements(max = BigDecimal.valueOf(5)))),
                field(
                    2,
                    "l",
                    ListOf(Scalar(Builtin.STRING, Refinements(max = BigDecimal.valueOf(5))), false),
                ),
            )
        val ds = SqlLowering.lower(Schema(listOf(namespace("a", r)))).diagnostics
        assertEquals(
            listOf(
                "11 SCH2104 field 'R.s': target 'sql' cannot lower type refinements yet (SCH-31)",
                "12 SCH2104 field 'R.l': target 'sql' cannot lower type refinements yet (SCH-31)",
            ),
            ds.map { "${it.span.startLine} ${it.code.id} ${it.message}" },
        )
    }

    @Test
    fun `reserved is accepted without diagnostics`() {
        val r =
            RecordType(
                QualifiedName("a", listOf("R")),
                "R",
                listOf(field(1, "x", Scalar(Builtin.STRING))),
                Reserved(listOf(2..2), setOf("old")),
                false,
                emptyList(),
                null,
                at("o.schemata", 3),
                at("o.schemata", 3),
            )
        assertEquals(emptyList(), SqlLowering.lower(Schema(listOf(namespace("a", r)))).diagnostics)
    }

    private val tagged = Annotations(mapOf("sql" to mapOf("key" to AnnotationValue.Flag)))

    @Test
    fun `annotations on a namespace, record, or field are reported`() {
        val r =
            record("a", "R", field(1, "a", Scalar(Builtin.BOOL)).copy(annotations = tagged))
                .copy(annotations = tagged)
        val ns = namespace("a", r).copy(annotations = tagged)
        val ds = SqlLowering.lower(Schema(listOf(ns))).diagnostics
        assertEquals(
            listOf(
                "1 SCH2104 target 'sql' cannot lower annotations yet (SCH-31)",
                "3 SCH2104 target 'sql' cannot lower annotations yet (SCH-31)",
                "11 SCH2104 field 'R.a': target 'sql' cannot lower annotations yet (SCH-31)",
            ),
            ds.map { "${it.span.startLine} ${it.code.id} ${it.message}" },
        )
    }

    @Test
    fun `a default is reported before an unsupported type`() {
        val r = record("a", "R", field(1, "n", Scalar(Builtin.INT64), default = IntValue(1)))
        val ds = SqlLowering.lower(Schema(listOf(namespace("a", r)))).diagnostics
        assertEquals(
            listOf("11 SCH2104 field 'R.n': target 'sql' cannot lower field defaults yet (SCH-32)"),
            ds.map { "${it.span.startLine} ${it.code.id} ${it.message}" },
        )
    }

    @Test
    fun `the target declares the whole sql key table`() {
        val specs = SqlTarget.annotationSpecs
        assertTrue(specs.all { it.target == "sql" })
        assertEquals(
            listOf(
                "column",
                "index",
                "key",
                "key",
                "schema",
                "strategy",
                "table",
                "type",
                "unique",
            ),
            specs.map { it.key }.sorted(),
        )
        val keyOnField = specs.single { it.key == "key" && Element.FIELD in it.elements }
        val keyOnRecord = specs.single { it.key == "key" && Element.RECORD in it.elements }
        assertEquals(ValueKind.FLAG, keyOnField.valueKind)
        assertEquals(ValueKind.NAME_TUPLE, keyOnRecord.valueKind)
        val strategy = specs.single { it.key == "strategy" }
        assertEquals(
            ValueKind.NAME to setOf("embed", "table", "json"),
            strategy.valueKind to strategy.choices,
        )
        assertEquals(setOf(Element.NAMESPACE), specs.single { it.key == "schema" }.elements)
    }
}
