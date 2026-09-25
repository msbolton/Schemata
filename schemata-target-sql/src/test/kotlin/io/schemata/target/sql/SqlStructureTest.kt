package io.schemata.target.sql

import io.schemata.core.ir.AnnotationValue
import io.schemata.core.ir.Annotations
import io.schemata.core.ir.Builtin
import io.schemata.core.ir.EnumType
import io.schemata.core.ir.EnumValue
import io.schemata.core.ir.Field
import io.schemata.core.ir.IntValue
import io.schemata.core.ir.Namespace
import io.schemata.core.ir.QualifiedName
import io.schemata.core.ir.RecordType
import io.schemata.core.ir.Ref
import io.schemata.core.ir.Refinements
import io.schemata.core.ir.Reserved
import io.schemata.core.ir.Scalar
import io.schemata.core.ir.Schema
import io.schemata.core.ir.Type
import io.schemata.core.ir.TypeDecl
import io.schemata.core.ir.Value
import io.schemata.lang.Span
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SqlStructureTest {
    private fun at(line: Int, file: String = "o.schemata") = Span(file, line, 1, line, 30)

    private fun qn(ns: String, vararg path: String) = QualifiedName(ns, path.toList())

    private fun sql(vararg pairs: Pair<String, AnnotationValue>) =
        Annotations(mapOf("sql" to pairs.toMap()))

    private fun str(s: String) = AnnotationValue.Str(s)

    private fun big(n: Long) = BigDecimal.valueOf(n)

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
        nested: List<TypeDecl> = emptyList(),
        line: Int = 3,
        doc: String? = null,
        annotations: Annotations = Annotations.NONE,
    ) =
        RecordType(
            qn(ns, name),
            name,
            fields.toList(),
            Reserved.NONE,
            false,
            nested,
            doc,
            at(line),
            at(line),
            annotations,
        )

    private fun enum(ns: String, name: String, vararg values: String, line: Int = 30) =
        EnumType(
            qn(ns, name),
            name,
            values.mapIndexed { i, v ->
                EnumValue(i + 1, v, null, at(line + 1 + i), at(line + 1 + i))
            },
            Reserved.NONE,
            emptyList(),
            null,
            at(line),
            at(line),
        )

    private fun namespace(
        name: String,
        vararg decls: TypeDecl,
        file: String = "o.schemata",
        annotations: Annotations = Annotations.NONE,
    ) = Namespace(name, decls.toList(), at(1, file), annotations)

    private fun lower(vararg namespaces: Namespace) = SqlLowering.lower(Schema(namespaces.toList()))

    private fun messages(lowered: io.schemata.target.Lowered<RelationalModel>) =
        lowered.diagnostics.map { "${it.span.startLine} ${it.code.id} ${it.message}" }

    private fun table(lowered: io.schemata.target.Lowered<RelationalModel>, name: String) =
        lowered.model.schemas.single().tables.first { it.name == name }

    private fun key() = sql("key" to AnnotationValue.Flag)

    private fun schemaOf(lowered: io.schemata.target.Lowered<RelationalModel>, name: String) =
        lowered.model.schemas.first { it.schemaName == name }

    @Test
    fun `a reference to a keyed record becomes key columns and a foreign key`() {
        val customer =
            record(
                "shop.customers",
                "Customer",
                field(1, "id", Scalar(Builtin.UUID), annotations = key()),
                field(2, "name", Scalar(Builtin.STRING)),
            )
        val order =
            record(
                "shop.orders",
                "Order",
                field(1, "id", Scalar(Builtin.UUID), annotations = key()),
                field(2, "customer", Ref(qn("shop.customers", "Customer"))),
                field(3, "parent", Ref(qn("shop.orders", "Order")), nullable = true),
            )
        val lowered =
            lower(
                namespace("shop.customers", customer, file = "c.schemata"),
                namespace("shop.orders", order),
            )
        assertEquals(emptyList(), messages(lowered))
        val orders = schemaOf(lowered, "orders")
        val t = orders.tables.single()
        assertEquals(
            listOf("id" to false, "customer_id" to false, "parent_id" to true),
            t.columns.map { it.name to it.nullable },
        )
        assertEquals(
            listOf(ColumnType.UUID, ColumnType.UUID, ColumnType.UUID),
            t.columns.map { it.type },
        )
        assertEquals(
            listOf(
                ForeignKey(
                    "fk_order_customer",
                    "orders",
                    "order",
                    listOf("customer_id"),
                    "customers",
                    "customer",
                    listOf("id"),
                    cascade = false,
                ),
                ForeignKey(
                    "fk_order_parent",
                    "orders",
                    "order",
                    listOf("parent_id"),
                    "orders",
                    "order",
                    listOf("id"),
                    cascade = false,
                ),
            ),
            orders.foreignKeys,
        )
        assertEquals(emptyList(), schemaOf(lowered, "customers").foreignKeys)
    }

    @Test
    fun `a composite key is referenced column by column`() {
        val plan =
            record(
                "a",
                "Plan",
                field(1, "tenant_id", Scalar(Builtin.UUID)),
                field(
                    2,
                    "code",
                    Scalar(Builtin.STRING, Refinements(max = big(8))),
                    annotations = sql("column" to str("plan_code")),
                ),
                annotations = sql("key" to AnnotationValue.Names(listOf("tenant_id", "code"))),
            )
        val sub =
            record(
                "a",
                "Subscription",
                field(1, "id", Scalar(Builtin.UUID), annotations = key()),
                field(2, "plan", Ref(qn("a", "Plan"))),
            )
        val lowered = lower(namespace("a", plan, sub))
        assertEquals(emptyList(), messages(lowered))
        val t = table(lowered, "subscription")
        assertEquals(listOf("id", "plan_tenant_id", "plan_plan_code"), t.columns.map { it.name })
        assertEquals(
            listOf(ColumnType.UUID, ColumnType.UUID, ColumnType.VARCHAR(8)),
            t.columns.map { it.type },
        )
        assertEquals(
            listOf(
                ForeignKey(
                    "fk_subscription_plan",
                    "a",
                    "subscription",
                    listOf("plan_tenant_id", "plan_plan_code"),
                    "a",
                    "plan",
                    listOf("tenant_id", "plan_code"),
                    false,
                )
            ),
            lowered.model.schemas.single().foreignKeys,
        )
    }

    @Test
    fun `a cross-schema foreign key lands in the file that sorts later`() {
        val a =
            record(
                "x.alpha",
                "A",
                field(1, "id", Scalar(Builtin.UUID), annotations = key()),
                field(2, "b", Ref(qn("x.beta", "B"))),
            )
        val b =
            record(
                "x.beta",
                "B",
                field(1, "id", Scalar(Builtin.UUID), annotations = key()),
                field(2, "a", Ref(qn("x.alpha", "A"))),
            )
        val lowered =
            lower(
                namespace("x.alpha", a, file = "a.schemata"),
                namespace("x.beta", b, file = "b.schemata"),
            )
        assertEquals(emptyList(), messages(lowered))
        assertEquals(emptyList(), schemaOf(lowered, "alpha").foreignKeys)
        assertEquals(
            listOf(
                ForeignKey(
                    "fk_b_a",
                    "beta",
                    "b",
                    listOf("a_id"),
                    "alpha",
                    "a",
                    listOf("id"),
                    false,
                ),
                ForeignKey("fk_a_b", "alpha", "a", listOf("b_id"), "beta", "b", listOf("id"), false),
            ),
            schemaOf(lowered, "beta").foreignKeys,
        )
        val beta = SqlRenderer.render(lowered.model).first { it.path == "x/beta.sql" }.content
        assertTrue(
            beta
                .lines()
                .contains(
                    "ALTER TABLE \"alpha\".\"a\" ADD CONSTRAINT \"fk_a_b\" FOREIGN KEY (\"b_id\") REFERENCES \"beta\".\"b\" (\"id\");"
                ),
            beta,
        )
    }

    @Test
    fun `keyless records are value types, and an unused one is an error`() {
        val used = record("a", "Money", field(1, "amount", Scalar(Builtin.INT64)), line = 3)
        val loose = record("a", "Loose", field(1, "x", Scalar(Builtin.BOOL)), line = 6)
        val owner =
            record(
                "a",
                "Owner",
                field(1, "id", Scalar(Builtin.UUID), annotations = key()),
                field(2, "price", Ref(qn("a", "Money"))),
                line = 9,
            )
        val lowered = lower(namespace("a", used, loose, owner))
        assertEquals(
            listOf(
                "6 SCH2106 record 'Loose' has no primary key and is not used by any field; mark key fields with @sql(key) or the record with @sql(key = (...))"
            ),
            messages(lowered).filter { "SCH2106" in it },
        )
        assertEquals(listOf("owner"), lowered.model.schemas.single().tables.map { it.name })
    }

    @Test
    fun `a key field must be a scalar column`() {
        val other = record("a", "Other", field(1, "id", Scalar(Builtin.UUID), annotations = key()))
        val r =
            record(
                "a",
                "R",
                field(1, "other", Ref(qn("a", "Other")), line = 11, annotations = key()),
                line = 10,
            )
        val lowered = lower(namespace("a", other, r))
        assertEquals(
            listOf("11 SCH2107 record 'R': key field 'other' must be a scalar column"),
            messages(lowered).filter { "SCH2107" in it },
        )
    }

    @Test
    fun `a record that declares a bad key still counts as keyed`() {
        val bad =
            record(
                "a",
                "Bad",
                field(1, "x", Scalar(Builtin.BOOL)),
                line = 8,
                annotations = sql("key" to AnnotationValue.Names(listOf("nope"))),
            )
        val lowered = lower(namespace("a", bad))
        assertEquals(
            listOf(
                "8 SCH2107 record 'Bad': @sql(key) names 'nope', which is not a field of the record"
            ),
            messages(lowered),
        )
        assertEquals(emptyList(), table(lowered, "bad").primaryKey)
    }

    @Test
    fun `a keyless record is embedded with prefixed columns and constraints`() {
        val address =
            record(
                "a",
                "Address",
                field(
                    1,
                    "street",
                    Scalar(Builtin.STRING, Refinements(max = big(200))),
                    doc = "Street.",
                ),
                field(
                    2,
                    "zip",
                    Scalar(Builtin.STRING, Refinements(min = big(5), max = big(5))),
                    annotations = sql("index" to AnnotationValue.Flag),
                ),
                field(3, "floor", Scalar(Builtin.INT32), nullable = true, default = IntValue(0)),
            )
        val geo =
            record(
                "a",
                "Geo",
                field(1, "lat", Scalar(Builtin.FLOAT64)),
                field(2, "home", Ref(qn("a", "Address"))),
            )
        val site =
            record(
                "a",
                "Site",
                field(1, "id", Scalar(Builtin.UUID), annotations = key()),
                field(2, "office", Ref(qn("a", "Address"))),
                field(3, "where", Ref(qn("a", "Geo")), nullable = true),
            )
        val lowered = lower(namespace("a", address, geo, site))
        assertEquals(emptyList(), messages(lowered))
        val t = table(lowered, "site")
        assertEquals(
            listOf(
                "id" to false,
                "office_street" to false,
                "office_zip" to false,
                "office_floor" to true,
                "where_lat" to true,
                "where_home_street" to true,
                "where_home_zip" to true,
                "where_home_floor" to true,
            ),
            t.columns.map { it.name to it.nullable },
        )
        assertEquals(ColumnType.VARCHAR(200), t.columns[1].type)
        assertEquals("0", t.columns[3].default)
        assertEquals("Street.", t.columns[1].doc)
        assertEquals(
            listOf(
                Check("ck_site_office_zip_min", "char_length(\"office_zip\") >= 5"),
                Check("ck_site_office_zip_max", "char_length(\"office_zip\") <= 5"),
                Check("ck_site_where_home_zip_min", "char_length(\"where_home_zip\") >= 5"),
                Check("ck_site_where_home_zip_max", "char_length(\"where_home_zip\") <= 5"),
                Check(
                    "ck_site_where_present",
                    "((\"where_lat\" IS NULL AND \"where_home_street\" IS NULL AND \"where_home_zip\" IS NULL) OR (\"where_lat\" IS NOT NULL AND \"where_home_street\" IS NOT NULL AND \"where_home_zip\" IS NOT NULL))",
                ),
            ),
            t.checks,
        )
        assertEquals(
            listOf(
                Index("ix_site_office_zip", listOf("office_zip")),
                Index("ix_site_where_home_zip", listOf("where_home_zip")),
            ),
            t.indexes,
        )
        assertEquals(listOf("site"), lowered.model.schemas.single().tables.map { it.name })
    }

    @Test
    fun `embedding a record inside itself is an error`() {
        val node =
            record(
                "a",
                "Node",
                field(1, "label", Scalar(Builtin.STRING)),
                field(2, "next", Ref(qn("a", "Node")), nullable = true, line = 12),
                line = 10,
            )
        val tree =
            record(
                "a",
                "Tree",
                field(1, "id", Scalar(Builtin.UUID), annotations = key()),
                field(2, "root", Ref(qn("a", "Node")), line = 22),
                line = 20,
            )
        val lowered = lower(namespace("a", node, tree))
        assertEquals(
            listOf(
                "12 SCH2108 field 'Node.next': embedding 'Node' here would recurse (Node → Node); use strategy = json or give 'Node' a key"
            ),
            messages(lowered),
        )
    }
}
