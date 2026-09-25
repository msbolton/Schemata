package io.schemata.target.sql

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
import io.schemata.core.ir.Ref
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

    private fun union(ns: String, name: String, vararg members: Type, line: Int = 40) =
        UnionType(
            qn(ns, name),
            name,
            members.mapIndexed { i, t -> UnionMember(i + 1, t, null, at(line + 1 + i)) },
            emptyList(),
            null,
            at(line),
            at(line),
        )

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

    @Test
    fun `a list of records becomes a child table keyed by the parent and position`() {
        val line =
            record(
                "a",
                "Line",
                field(1, "sku", Scalar(Builtin.STRING, Refinements(max = big(64)))),
                field(2, "qty", Scalar(Builtin.INT32, Refinements(min = big(1)))),
                doc = "One item.",
            )
        val item = record("a", "Item", field(1, "id", Scalar(Builtin.UUID), annotations = key()))
        val order =
            record(
                "a",
                "Order",
                field(1, "id", Scalar(Builtin.UUID), annotations = key()),
                field(2, "lines", ListOf(Ref(qn("a", "Line")), false, Refinements(min = big(1)))),
                field(3, "items", ListOf(Ref(qn("a", "Item")), false)),
            )
        val lowered = lower(namespace("a", line, item, order))
        assertEquals(
            listOf(
                "12 SCH2105 field 'Order.lines': refinements on list<Line>(min = 1) are not enforced by Postgres"
            ),
            messages(lowered),
        )
        val schema = lowered.model.schemas.single()
        assertEquals(
            listOf("item", "order", "order_lines", "order_items"),
            schema.tables.map { it.name },
        )
        val lines = schema.tables[2]
        assertEquals(
            listOf(
                "order_id" to ColumnType.UUID,
                "position" to ColumnType.INTEGER,
                "sku" to ColumnType.VARCHAR(64),
                "qty" to ColumnType.INTEGER,
            ),
            lines.columns.map { it.name to it.type },
        )
        assertTrue(lines.columns.all { !it.nullable })
        assertEquals(
            listOf("order_id", "position") to "pk_order_lines",
            lines.primaryKey to lines.primaryKeyName,
        )
        assertEquals(listOf(Check("ck_order_lines_qty_min", "\"qty\" >= 1")), lines.checks)
        assertEquals("One item.", lines.doc)
        val items = schema.tables[3]
        assertEquals(listOf("order_id", "position", "value_id"), items.columns.map { it.name })
        assertEquals(
            listOf(
                ForeignKey(
                    "fk_order_lines_order",
                    "a",
                    "order_lines",
                    listOf("order_id"),
                    "a",
                    "order",
                    listOf("id"),
                    cascade = true,
                ),
                ForeignKey(
                    "fk_order_items_order",
                    "a",
                    "order_items",
                    listOf("order_id"),
                    "a",
                    "order",
                    listOf("id"),
                    cascade = true,
                ),
                ForeignKey(
                    "fk_order_items_value",
                    "a",
                    "order_items",
                    listOf("value_id"),
                    "a",
                    "item",
                    listOf("id"),
                    cascade = false,
                ),
            ),
            schema.foreignKeys,
        )
        assertEquals(listOf("id"), table(lowered, "order").columns.map { it.name })
    }

    @Test
    fun `scalar lists are arrays and maps are jsonb`() {
        val color = enum("a", "Color", "red")
        val r =
            record(
                "a",
                "R",
                field(1, "id", Scalar(Builtin.UUID), annotations = key()),
                field(2, "tags", ListOf(Scalar(Builtin.STRING), false)),
                field(
                    3,
                    "codes",
                    ListOf(
                        Scalar(Builtin.STRING, Refinements(max = big(3))),
                        false,
                        Refinements(max = big(5)),
                    ),
                    nullable = true,
                ),
                field(4, "colors", ListOf(Ref(qn("a", "Color")), false)),
                field(5, "meta", MapOf(Scalar(Builtin.STRING), Scalar(Builtin.INT32), false)),
                field(
                    6,
                    "extra",
                    MapOf(Scalar(Builtin.STRING), Ref(qn("a", "Color")), true),
                    nullable = true,
                ),
            )
        val lowered = lower(namespace("a", color, r))
        assertEquals(
            listOf(
                "13 SCH2105 field 'R.codes': refinements on list<string(max = 3)>(max = 5)? are not enforced by Postgres",
                "15 SCH2105 field 'R.meta': map contents are not typed by Postgres; lowered to jsonb",
                "16 SCH2105 field 'R.extra': map contents are not typed by Postgres; lowered to jsonb",
            ),
            messages(lowered),
        )
        val t = table(lowered, "r")
        assertEquals(
            listOf(
                ColumnType.UUID,
                ColumnType.ARRAY(ColumnType.TEXT),
                ColumnType.ARRAY(ColumnType.TEXT),
                ColumnType.ARRAY(ColumnType.TEXT),
                ColumnType.JSONB,
                ColumnType.JSONB,
            ),
            t.columns.map { it.type },
        )
        assertEquals(listOf(false, false, true, false, false, true), t.columns.map { it.nullable })
        assertEquals(
            listOf(
                emptyList(),
                emptyList(),
                listOf("list<string(max = 3)>(max = 5)?"),
                emptyList(),
                listOf("map<string, int32>"),
                listOf("map<string, Color?>?"),
            ),
            t.columns.map { it.notes },
        )
        assertEquals(emptyList(), t.checks) // enum arrays carry no IN check
    }

    @Test
    fun `a list inside a child record nests child tables and a recursive list is an error`() {
        val note = record("a", "Note", field(1, "text", Scalar(Builtin.STRING)))
        val line =
            record(
                "a",
                "Line",
                field(1, "sku", Scalar(Builtin.STRING)),
                field(2, "notes", ListOf(Ref(qn("a", "Note")), false)),
            )
        val order =
            record(
                "a",
                "Order",
                field(1, "id", Scalar(Builtin.UUID), annotations = key()),
                field(2, "lines", ListOf(Ref(qn("a", "Line")), false)),
            )
        val lowered = lower(namespace("a", note, line, order))
        assertEquals(emptyList(), messages(lowered))
        val schema = lowered.model.schemas.single()
        assertEquals(
            listOf("order", "order_lines", "order_lines_notes"),
            schema.tables.map { it.name },
        )
        val notes = schema.tables[2]
        assertEquals(
            listOf("order_lines_order_id", "order_lines_position", "position", "text"),
            notes.columns.map { it.name },
        )
        assertEquals(
            listOf("order_lines_order_id", "order_lines_position", "position"),
            notes.primaryKey,
        )
        assertEquals(
            ForeignKey(
                "fk_order_lines_notes_order_lines",
                "a",
                "order_lines_notes",
                listOf("order_lines_order_id", "order_lines_position"),
                "a",
                "order_lines",
                listOf("order_id", "position"),
                true,
            ),
            schema.foreignKeys[1],
        )

        val folder =
            record(
                "a",
                "Folder",
                field(1, "name", Scalar(Builtin.STRING)),
                field(2, "children", ListOf(Ref(qn("a", "Folder")), false), line = 32),
                line = 30,
            )
        val root =
            record(
                "a",
                "Root",
                field(1, "id", Scalar(Builtin.UUID), annotations = key()),
                field(2, "folders", ListOf(Ref(qn("a", "Folder")), false)),
                line = 40,
            )
        assertEquals(
            listOf(
                "32 SCH2108 field 'Folder.children': embedding 'Folder' here would recurse (Folder → Folder); use strategy = json or give 'Folder' a key"
            ),
            messages(lower(namespace("a", folder, root))),
        )
    }

    @Test
    fun `a union becomes a discriminator and nullable variant columns with per-variant checks`() {
        val card =
            record(
                "a",
                "Card",
                field(1, "last4", Scalar(Builtin.STRING, Refinements(max = big(4)))),
                field(2, "brand", Scalar(Builtin.STRING), nullable = true),
            )
        val transfer =
            record(
                "a",
                "BankTransfer",
                field(1, "iban", Scalar(Builtin.STRING, Refinements(min = big(15), max = big(34)))),
            )
        val cash = record("a", "Cash")
        val account =
            record("a", "Account", field(1, "id", Scalar(Builtin.UUID), annotations = key()))
        val payment =
            union(
                "a",
                "Payment",
                Ref(qn("a", "Card")),
                Ref(qn("a", "BankTransfer")),
                Ref(qn("a", "Cash")),
                Scalar(Builtin.UUID),
                Ref(qn("a", "Account")),
            )
        val order =
            record(
                "a",
                "Order",
                field(1, "id", Scalar(Builtin.UUID), annotations = key()),
                field(2, "payment", Ref(qn("a", "Payment"))),
                field(3, "refund", Ref(qn("a", "Payment")), nullable = true),
            )
        val lowered = lower(namespace("a", card, transfer, cash, account, payment, order))
        assertEquals(emptyList(), messages(lowered))
        val t = table(lowered, "order")
        assertEquals(
            listOf(
                "id" to false,
                "payment_kind" to false,
                "payment_card_last4" to true,
                "payment_card_brand" to true,
                "payment_bank_transfer_iban" to true,
                "payment_uuid" to true,
                "payment_account_id" to true,
                "refund_kind" to true,
                "refund_card_last4" to true,
                "refund_card_brand" to true,
                "refund_bank_transfer_iban" to true,
                "refund_uuid" to true,
                "refund_account_id" to true,
            ),
            t.columns.map { it.name to it.nullable },
        )
        assertEquals(ColumnType.VARCHAR(4), t.columns[2].type)
        assertEquals(
            listOf(
                Check(
                    "ck_order_payment_kind",
                    "\"payment_kind\" IN ('card', 'bank_transfer', 'cash', 'uuid', 'account')",
                ),
                Check(
                    "ck_order_payment_card",
                    "(\"payment_kind\" <> 'card') OR (\"payment_card_last4\" IS NOT NULL)",
                ),
                Check(
                    "ck_order_payment_bank_transfer_iban_min",
                    "char_length(\"payment_bank_transfer_iban\") >= 15",
                ),
                Check(
                    "ck_order_payment_bank_transfer_iban_max",
                    "char_length(\"payment_bank_transfer_iban\") <= 34",
                ),
                Check(
                    "ck_order_payment_bank_transfer",
                    "(\"payment_kind\" <> 'bank_transfer') OR (\"payment_bank_transfer_iban\" IS NOT NULL)",
                ),
                Check(
                    "ck_order_payment_uuid",
                    "(\"payment_kind\" <> 'uuid') OR (\"payment_uuid\" IS NOT NULL)",
                ),
                Check(
                    "ck_order_payment_account",
                    "(\"payment_kind\" <> 'account') OR (\"payment_account_id\" IS NOT NULL)",
                ),
                Check(
                    "ck_order_refund_kind",
                    "\"refund_kind\" IN ('card', 'bank_transfer', 'cash', 'uuid', 'account')",
                ),
                Check(
                    "ck_order_refund_card",
                    "(\"refund_kind\" <> 'card') OR (\"refund_card_last4\" IS NOT NULL)",
                ),
                Check(
                    "ck_order_refund_bank_transfer_iban_min",
                    "char_length(\"refund_bank_transfer_iban\") >= 15",
                ),
                Check(
                    "ck_order_refund_bank_transfer_iban_max",
                    "char_length(\"refund_bank_transfer_iban\") <= 34",
                ),
                Check(
                    "ck_order_refund_bank_transfer",
                    "(\"refund_kind\" <> 'bank_transfer') OR (\"refund_bank_transfer_iban\" IS NOT NULL)",
                ),
                Check(
                    "ck_order_refund_uuid",
                    "(\"refund_kind\" <> 'uuid') OR (\"refund_uuid\" IS NOT NULL)",
                ),
                Check(
                    "ck_order_refund_account",
                    "(\"refund_kind\" <> 'account') OR (\"refund_account_id\" IS NOT NULL)",
                ),
            ),
            t.checks,
        )
        assertEquals(
            listOf(
                ForeignKey(
                    "fk_order_payment_account",
                    "a",
                    "order",
                    listOf("payment_account_id"),
                    "a",
                    "account",
                    listOf("id"),
                    false,
                ),
                ForeignKey(
                    "fk_order_refund_account",
                    "a",
                    "order",
                    listOf("refund_account_id"),
                    "a",
                    "account",
                    listOf("id"),
                    false,
                ),
            ),
            lowered.model.schemas.single().foreignKeys,
        )
    }

    private fun strategy(s: String) = sql("strategy" to AnnotationValue.Name(s))

    @Test
    fun `strategies override the default mapping`() {
        val addr = record("a", "Addr", field(1, "city", Scalar(Builtin.STRING)))
        val cust =
            record(
                "a",
                "Cust",
                field(1, "id", Scalar(Builtin.UUID), annotations = key()),
                field(2, "name", Scalar(Builtin.STRING)),
            )
        val r =
            record(
                "a",
                "R",
                field(1, "id", Scalar(Builtin.UUID), annotations = key()),
                field(2, "home", Ref(qn("a", "Addr")), annotations = strategy("json")),
                field(3, "cust", Ref(qn("a", "Cust")), annotations = strategy("embed")),
                field(
                    4,
                    "tags",
                    ListOf(Scalar(Builtin.STRING, Refinements(max = big(8))), false),
                    annotations = strategy("table"),
                ),
                field(
                    5,
                    "meta",
                    MapOf(Scalar(Builtin.STRING), Ref(qn("a", "Addr")), false),
                    annotations = strategy("table"),
                ),
                field(
                    6,
                    "lines",
                    ListOf(Ref(qn("a", "Addr")), false),
                    annotations = strategy("json"),
                ),
            )
        val lowered = lower(namespace("a", addr, cust, r))
        assertEquals(
            listOf(
                "12 SCH2105 field 'R.home': record contents are not typed by Postgres; lowered to jsonb",
                "16 SCH2105 field 'R.lines': list contents are not typed by Postgres; lowered to jsonb",
            ),
            messages(lowered),
        )
        val schema = lowered.model.schemas.single()
        assertEquals(listOf("cust", "r", "r_tags", "r_meta"), schema.tables.map { it.name })
        val t = table(lowered, "r")
        assertEquals(
            listOf(
                "id" to ColumnType.UUID,
                "home" to ColumnType.JSONB,
                "cust_id" to ColumnType.UUID,
                "cust_name" to ColumnType.TEXT,
                "lines" to ColumnType.JSONB,
            ),
            t.columns.map { it.name to it.type },
        )
        assertEquals(
            emptyList(),
            schema.foreignKeys.filter { it.table == "r" },
        ) // embed of a keyed record: no FK
        val tags = schema.tables[2]
        assertEquals(listOf("r_id", "position", "value"), tags.columns.map { it.name })
        assertEquals(ColumnType.VARCHAR(8), tags.columns[2].type)
        val meta = schema.tables[3]
        assertEquals(listOf("r_id", "key", "value_city"), meta.columns.map { it.name })
        assertEquals(listOf("r_id", "key"), meta.primaryKey)
    }

    @Test
    fun `strategies a shape forbids are errors`() {
        val addr = record("a", "Addr", field(1, "city", Scalar(Builtin.STRING)))
        val r =
            record(
                "a",
                "R",
                field(1, "id", Scalar(Builtin.UUID), annotations = key()),
                field(2, "home", Ref(qn("a", "Addr")), annotations = strategy("table")),
                field(
                    3,
                    "tags",
                    ListOf(Scalar(Builtin.STRING), false),
                    annotations = strategy("embed"),
                ),
                field(
                    4,
                    "meta",
                    MapOf(Scalar(Builtin.STRING), Scalar(Builtin.INT32), false),
                    annotations = strategy("embed"),
                ),
                field(5, "flag", Scalar(Builtin.BOOL), annotations = strategy("json")),
            )
        val lowered = lower(namespace("a", addr, r))
        assertEquals(
            listOf(
                "12 SCH2110 field 'R.home': strategy 'table' is not allowed for a keyless record; use embed or json",
                "13 SCH2110 field 'R.tags': strategy 'embed' is not allowed for a list; use table or json",
                "14 SCH2110 field 'R.meta': strategy 'embed' is not allowed for a map; use table or json",
                "15 SCH2110 field 'R.flag': strategy 'json' is not allowed for a scalar; remove it",
            ),
            messages(lowered),
        )
    }

    @Test
    fun `a union with a union member needs the json strategy`() {
        val a = record("a", "A", field(1, "x", Scalar(Builtin.BOOL)))
        val inner = union("a", "Inner", Ref(qn("a", "A")), Scalar(Builtin.INT32), line = 40)
        val outer = union("a", "Outer", Ref(qn("a", "Inner")), Scalar(Builtin.STRING), line = 50)
        val r =
            record(
                "a",
                "R",
                field(1, "id", Scalar(Builtin.UUID), annotations = key()),
                field(2, "choice", Ref(qn("a", "Outer")), line = 12),
            )
        val lowered = lower(namespace("a", a, inner, outer, r))
        assertEquals(
            listOf(
                "12 SCH2110 field 'R.choice': strategy 'embed' is not allowed for a union whose member is a union; use json"
            ),
            messages(lowered),
        )
    }

    @Test
    fun `lists of unions or collections need the json strategy`() {
        val card = record("a", "Card", field(1, "amt", Scalar(Builtin.INT32)))
        val u = union("a", "U", Ref(qn("a", "Card")), Scalar(Builtin.UUID))
        val r =
            record(
                "a",
                "R",
                field(1, "id", Scalar(Builtin.UUID), annotations = key()),
                field(2, "choices", ListOf(Ref(qn("a", "U")), false)),
                field(3, "grid", ListOf(ListOf(Scalar(Builtin.INT32), false), false)),
                field(4, "ok", ListOf(Ref(qn("a", "U")), false), annotations = strategy("json")),
            )
        val lowered = lower(namespace("a", card, u, r))
        assertEquals(
            listOf(
                "12 SCH2110 field 'R.choices': a list of unions has no relational mapping; use strategy = json",
                "13 SCH2110 field 'R.grid': a list of lists or maps has no relational mapping; use strategy = json",
                "14 SCH2105 field 'R.ok': list contents are not typed by Postgres; lowered to jsonb",
            ),
            messages(lowered),
        )
        val t = table(lowered, "r")
        assertEquals(ColumnType.JSONB, t.columns.single { it.name == "ok" }.type)
    }

    @Test
    fun `union strategies, table is forbidden and json legalizes a union of unions`() {
        val payment = union("a", "Payment", Scalar(Builtin.UUID), Scalar(Builtin.STRING), line = 40)
        val forbidden =
            record(
                "a",
                "R",
                field(1, "id", Scalar(Builtin.UUID), annotations = key()),
                field(2, "choice", Ref(qn("a", "Payment")), annotations = strategy("table")),
            )
        val loweredForbidden = lower(namespace("a", payment, forbidden))
        assertEquals(
            listOf(
                "12 SCH2110 field 'R.choice': strategy 'table' is not allowed for a union; use embed or json"
            ),
            messages(loweredForbidden),
        )

        val a = record("a", "A", field(1, "x", Scalar(Builtin.BOOL)))
        val inner = union("a", "Inner", Ref(qn("a", "A")), Scalar(Builtin.INT32), line = 40)
        val outer = union("a", "Outer", Ref(qn("a", "Inner")), Scalar(Builtin.STRING), line = 50)
        val ok =
            record(
                "a",
                "R",
                field(1, "id", Scalar(Builtin.UUID), annotations = key()),
                field(2, "choice", Ref(qn("a", "Outer")), annotations = strategy("json")),
            )
        val loweredOk = lower(namespace("a", a, inner, outer, ok))
        assertEquals(
            listOf(
                "12 SCH2105 field 'R.choice': union contents are not typed by Postgres; lowered to jsonb"
            ),
            messages(loweredOk),
        )
        assertEquals(
            ColumnType.JSONB,
            table(loweredOk, "r").columns.single { it.name == "choice" }.type,
        )
    }

    @Test
    fun `a nested keyed record is a table and references to it have a target`() {
        val item =
            RecordType(
                qn("a", "Order", "Item"),
                "Item",
                listOf(
                    field(
                        1,
                        "sku",
                        Scalar(Builtin.STRING, Refinements(max = big(8))),
                        line = 21,
                        annotations = key(),
                    )
                ),
                Reserved.NONE,
                false,
                emptyList(),
                null,
                at(20),
                at(20),
                Annotations.NONE,
            )
        val order =
            record(
                "a",
                "Order",
                field(1, "id", Scalar(Builtin.UUID), annotations = key()),
                field(2, "item", Ref(qn("a", "Order", "Item"))),
                nested = listOf(item),
            )
        val lowered = lower(namespace("a", order))
        assertEquals(emptyList(), messages(lowered))
        val schema = lowered.model.schemas.single()
        assertEquals(listOf("order", "item"), schema.tables.map { it.name })
        assertEquals(
            listOf(
                Triple("id", ColumnType.UUID, false),
                Triple("item_sku", ColumnType.VARCHAR(8), false),
            ),
            table(lowered, "order").columns.map { Triple(it.name, it.type, it.nullable) },
        )
        val itemTable = table(lowered, "item")
        assertEquals(listOf("sku"), itemTable.columns.map { it.name })
        assertEquals(listOf("sku") to "pk_item", itemTable.primaryKey to itemTable.primaryKeyName)
        assertEquals(
            listOf(
                ForeignKey(
                    "fk_order_item",
                    "a",
                    "order",
                    listOf("item_sku"),
                    "a",
                    "item",
                    listOf("sku"),
                    cascade = false,
                )
            ),
            schema.foreignKeys,
        )
    }

    @Test
    fun `a table and a child table with one name collide`() {
        val orderLines =
            record(
                "a",
                "OrderLines",
                field(1, "id", Scalar(Builtin.UUID), line = 4, annotations = key()),
                line = 3,
            )
        val line = record("a", "Line", field(1, "sku", Scalar(Builtin.STRING)), line = 20)
        val order =
            record(
                "a",
                "Order",
                field(1, "id", Scalar(Builtin.UUID), annotations = key()),
                field(2, "lines", ListOf(Ref(qn("a", "Line")), false)),
                line = 10,
            )
        val lowered = lower(namespace("a", orderLines, line, order))
        assertEquals(
            listOf(
                "12 SCH2111 relation name 'order_lines' is already used by table 'order_lines' (o.schemata:3)"
            ),
            messages(lowered),
        )
    }

    @Test
    fun `a list of the record's own type references it through value columns`() {
        val employee =
            record(
                "a",
                "Employee",
                field(1, "id", Scalar(Builtin.UUID), annotations = key()),
                field(2, "reports", ListOf(Ref(qn("a", "Employee")), false)),
            )
        val lowered = lower(namespace("a", employee))
        assertEquals(emptyList(), messages(lowered))
        val schema = lowered.model.schemas.single()
        assertEquals(listOf("employee", "employee_reports"), schema.tables.map { it.name })
        val reports = table(lowered, "employee_reports")
        assertEquals(listOf("employee_id", "position", "value_id"), reports.columns.map { it.name })
        assertEquals(listOf("employee_id", "position"), reports.primaryKey)
        assertEquals(
            listOf(
                ForeignKey(
                    "fk_employee_reports_employee",
                    "a",
                    "employee_reports",
                    listOf("employee_id"),
                    "a",
                    "employee",
                    listOf("id"),
                    cascade = true,
                ),
                ForeignKey(
                    "fk_employee_reports_value",
                    "a",
                    "employee_reports",
                    listOf("value_id"),
                    "a",
                    "employee",
                    listOf("id"),
                    cascade = false,
                ),
            ),
            schema.foreignKeys,
        )
    }

    @Test
    fun `child table columns collide with the parent key and position`() {
        val step =
            record(
                "a",
                "Step",
                field(1, "position", Scalar(Builtin.INT32), line = 21),
                field(2, "order_id", Scalar(Builtin.STRING), line = 22),
                line = 20,
            )
        val order =
            record(
                "a",
                "Order",
                field(1, "id", Scalar(Builtin.UUID), annotations = key()),
                field(2, "lines", ListOf(Ref(qn("a", "Step")), false)),
            )
        val lowered = lower(namespace("a", step, order))
        assertEquals(
            listOf(
                "21 SCH2111 field 'Step.position' lowers to column 'position', already used by the child table's position column (o.schemata:12)",
                "22 SCH2111 field 'Step.order_id' lowers to column 'order_id', already used by the child table's parent key column (o.schemata:12)",
            ),
            messages(lowered),
        )
    }

    @Test
    fun `a list of nullable records keeps a keyed element and reports a keyless one`() {
        val item = record("a", "Item", field(1, "id", Scalar(Builtin.UUID), annotations = key()))
        val line = record("a", "Line", field(1, "sku", Scalar(Builtin.STRING)), line = 20)
        val order =
            record(
                "a",
                "Order",
                field(1, "id", Scalar(Builtin.UUID), annotations = key()),
                field(2, "items", ListOf(Ref(qn("a", "Item")), true)),
                field(3, "lines", ListOf(Ref(qn("a", "Line")), true)),
                line = 10,
            )
        val lowered = lower(namespace("a", item, line, order))
        assertEquals(
            listOf(
                "13 SCH2105 field 'Order.lines': nullable elements of list<Line?> are not represented by a child table"
            ),
            messages(lowered),
        )
        assertEquals(
            listOf("order_id" to false, "position" to false, "value_id" to true),
            table(lowered, "order_items").columns.map { it.name to it.nullable },
        )
        val lines = table(lowered, "order_lines")
        assertEquals(
            listOf("order_id" to false, "position" to false, "sku" to false),
            lines.columns.map { it.name to it.nullable },
        )
        assertTrue(lines.columns.all { it.notes.isEmpty() })
    }
}
