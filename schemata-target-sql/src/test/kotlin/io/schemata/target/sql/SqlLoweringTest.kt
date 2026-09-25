package io.schemata.target.sql

import io.schemata.core.annotations.Element
import io.schemata.core.annotations.ValueKind
import io.schemata.core.ir.AnnotationValue
import io.schemata.core.ir.Annotations
import io.schemata.core.ir.BoolValue
import io.schemata.core.ir.Builtin
import io.schemata.core.ir.EnumRef
import io.schemata.core.ir.EnumType
import io.schemata.core.ir.EnumValue
import io.schemata.core.ir.Field
import io.schemata.core.ir.IntValue
import io.schemata.core.ir.ListOf
import io.schemata.core.ir.MapOf
import io.schemata.core.ir.Namespace
import io.schemata.core.ir.QualifiedName
import io.schemata.core.ir.RealValue
import io.schemata.core.ir.RecordType
import io.schemata.core.ir.Ref
import io.schemata.core.ir.Refinements
import io.schemata.core.ir.Reserved
import io.schemata.core.ir.Scalar
import io.schemata.core.ir.Schema
import io.schemata.core.ir.StringValue
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

class SqlLoweringTest {
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

    @Test
    fun `emits one relational schema per namespace with path and last-segment name`() {
        val schemas = lower(namespace("shop.customers"), namespace("shop.orders")).model.schemas
        assertEquals(
            listOf("shop/customers.sql" to "customers", "shop/orders.sql" to "orders"),
            schemas.map { it.path to it.schemaName },
        )
    }

    @Test
    fun `maps every builtin to its column type`() {
        val r =
            record(
                "a",
                "R",
                field(
                    1,
                    "a",
                    Scalar(Builtin.BOOL),
                    annotations = sql("key" to AnnotationValue.Flag),
                ),
                field(2, "b", Scalar(Builtin.INT32)),
                field(3, "c", Scalar(Builtin.INT64)),
                field(4, "d", Scalar(Builtin.FLOAT32)),
                field(5, "e", Scalar(Builtin.FLOAT64)),
                field(6, "f", Scalar(Builtin.DECIMAL, Refinements(precision = 19, scale = 4))),
                field(7, "g", Scalar(Builtin.STRING)),
                field(8, "h", Scalar(Builtin.BYTES)),
                field(9, "i", Scalar(Builtin.UUID)),
                field(10, "j", Scalar(Builtin.DATE)),
                field(11, "k", Scalar(Builtin.TIME)),
                field(12, "l", Scalar(Builtin.INSTANT)),
                field(13, "m", Scalar(Builtin.DURATION)),
                field(14, "n", Scalar(Builtin.STRING), nullable = true),
            )
        val lowered = lower(namespace("a", r))
        assertEquals(emptyList(), messages(lowered))
        val columns = table(lowered, "r").columns
        assertEquals(
            listOf(
                ColumnType.BOOLEAN,
                ColumnType.INTEGER,
                ColumnType.BIGINT,
                ColumnType.REAL,
                ColumnType.DOUBLE,
                ColumnType.NUMERIC(19, 4),
                ColumnType.TEXT,
                ColumnType.BYTEA,
                ColumnType.UUID,
                ColumnType.DATE,
                ColumnType.TIME,
                ColumnType.TIMESTAMPTZ,
                ColumnType.INTERVAL,
                ColumnType.TEXT,
            ),
            columns.map { it.type },
        )
        assertEquals(listOf(false) * 13 + listOf(true), columns.map { it.nullable })
        assertTrue(columns.all { it.default == null && it.notes.isEmpty() })
        assertEquals(emptyList(), table(lowered, "r").checks)
    }

    @Test
    fun `refinements become checks and varchar`() {
        val r =
            record(
                "a",
                "R",
                field(
                    1,
                    "small",
                    Scalar(Builtin.INT32, Refinements(min = big(0), max = big(100))),
                    annotations = sql("key" to AnnotationValue.Flag),
                ),
                field(2, "exact", Scalar(Builtin.FLOAT64, Refinements(max = BigDecimal("1.5")))),
                field(3, "name", Scalar(Builtin.STRING, Refinements(min = big(2), max = big(8)))),
                field(4, "email", Scalar(Builtin.STRING, Refinements(pattern = "^[^@]+@[^@]+$"))),
                field(5, "code", Scalar(Builtin.STRING, Refinements(max = big(4)))),
                field(
                    6,
                    "mixed",
                    Scalar(Builtin.STRING, Refinements(max = big(4), pattern = "^a")),
                ),
                field(7, "blob", Scalar(Builtin.BYTES, Refinements(min = big(1), max = big(1024)))),
                field(
                    8,
                    "money",
                    Scalar(Builtin.DECIMAL, Refinements(min = big(0), precision = 5, scale = 1)),
                ),
                field(9, "quote", Scalar(Builtin.STRING, Refinements(pattern = "it's"))),
            )
        val lowered = lower(namespace("a", r))
        assertEquals(emptyList(), messages(lowered))
        val t = table(lowered, "r")
        assertEquals(
            listOf(
                ColumnType.INTEGER,
                ColumnType.DOUBLE,
                ColumnType.TEXT,
                ColumnType.TEXT,
                ColumnType.VARCHAR(4),
                ColumnType.TEXT,
                ColumnType.BYTEA,
                ColumnType.NUMERIC(5, 1),
                ColumnType.TEXT,
            ),
            t.columns.map { it.type },
        )
        assertEquals(
            listOf(
                Check("ck_r_small_min", "\"small\" >= 0"),
                Check("ck_r_small_max", "\"small\" <= 100"),
                Check("ck_r_exact_max", "\"exact\" <= 1.5"),
                Check("ck_r_name_min", "char_length(\"name\") >= 2"),
                Check("ck_r_name_max", "char_length(\"name\") <= 8"),
                Check("ck_r_email_pattern", "\"email\" ~ '^[^@]+@[^@]+$'"),
                Check("ck_r_mixed_max", "char_length(\"mixed\") <= 4"),
                Check("ck_r_mixed_pattern", "\"mixed\" ~ '^a'"),
                Check("ck_r_blob_min", "octet_length(\"blob\") >= 1"),
                Check("ck_r_blob_max", "octet_length(\"blob\") <= 1024"),
                Check("ck_r_money_min", "\"money\" >= 0"),
                Check("ck_r_quote_pattern", "\"quote\" ~ 'it''s'"),
            ),
            t.checks,
        )
    }

    @Test
    fun `defaults are spelled as literals and enums as constrained text`() {
        val status = enum("a", "Status", "pending", "paid")
        val r =
            record(
                "a",
                "R",
                field(
                    1,
                    "flag",
                    Scalar(Builtin.BOOL),
                    default = BoolValue(true),
                    annotations = sql("key" to AnnotationValue.Flag),
                ),
                field(2, "n", Scalar(Builtin.INT32), default = IntValue(3)),
                field(
                    3,
                    "money",
                    Scalar(Builtin.DECIMAL, Refinements(precision = 19, scale = 4)),
                    default = RealValue(BigDecimal("1.25")),
                ),
                field(4, "note", Scalar(Builtin.STRING), default = StringValue("it's")),
                field(
                    5,
                    "status",
                    Ref(qn("a", "Status")),
                    default = EnumRef(qn("a", "Status"), "pending"),
                ),
                field(6, "maybe", Ref(qn("a", "Status")), nullable = true),
            )
        val lowered = lower(namespace("a", status, r))
        assertEquals(emptyList(), messages(lowered))
        val t = table(lowered, "r")
        assertEquals(
            listOf("true", "3", "1.25", "'it''s'", "'pending'", null),
            t.columns.map { it.default },
        )
        assertEquals(ColumnType.TEXT to ColumnType.TEXT, t.columns[4].type to t.columns[5].type)
        assertEquals(
            listOf(
                Check("ck_r_status_enum", "\"status\" IN ('pending', 'paid')"),
                Check("ck_r_maybe_enum", "\"maybe\" IN ('pending', 'paid')"),
            ),
            t.checks,
        )
        assertEquals(1, lowered.model.schemas.single().tables.size) // the enum produces no object
    }

    @Test
    fun `type overrides are verbatim, noted, and keep their checks`() {
        val r =
            record(
                "a",
                "R",
                field(
                    1,
                    "legacy",
                    Scalar(Builtin.UUID),
                    nullable = true,
                    annotations = sql("type" to str("varchar(36)")),
                ),
                field(
                    2,
                    "code",
                    Scalar(Builtin.STRING, Refinements(max = big(4))),
                    annotations = sql("type" to str("citext"), "key" to AnnotationValue.Flag),
                ),
            )
        val lowered = lower(namespace("a", r))
        assertEquals(emptyList(), messages(lowered))
        val t = table(lowered, "r")
        assertEquals(
            listOf(ColumnType.RAW("varchar(36)"), ColumnType.RAW("citext")),
            t.columns.map { it.type },
        )
        assertEquals(listOf(listOf("uuid?"), listOf("string(max = 4)")), t.columns.map { it.notes })
        assertEquals(listOf(Check("ck_r_code_max", "char_length(\"code\") <= 4")), t.checks)
    }

    @Test
    fun `docs are carried to tables and columns`() {
        val r =
            record(
                "a",
                "Product",
                field(
                    1,
                    "sku",
                    Scalar(Builtin.STRING),
                    doc = "Stock keeping unit.",
                    annotations = sql("key" to AnnotationValue.Flag),
                ),
                doc = "A product.",
            )
        val t = table(lower(namespace("a", r)), "product")
        assertEquals("A product." to "Stock keeping unit.", t.doc to t.columns.single().doc)
    }

    @Test
    fun `structural shapes are still reported at the boundary`() {
        val leaf =
            record(
                "a",
                "Leaf",
                field(
                    1,
                    "x",
                    Scalar(Builtin.BOOL),
                    annotations = sql("key" to AnnotationValue.Flag),
                ),
                line = 20,
            )
        val u =
            UnionType(
                qn("a", "U"),
                "U",
                listOf(UnionMember(1, Ref(qn("a", "Leaf")), null, at(41))),
                emptyList(),
                null,
                at(40),
                at(40),
            )
        val r =
            record(
                "a",
                "R",
                field(
                    0,
                    "id",
                    Scalar(Builtin.UUID),
                    annotations = sql("key" to AnnotationValue.Flag),
                ),
                field(1, "ref", Ref(qn("a", "Leaf"))),
                field(2, "many", ListOf(Scalar(Builtin.STRING), false)),
                field(3, "map", MapOf(Scalar(Builtin.STRING), Scalar(Builtin.INT32), false)),
                field(4, "choice", Ref(qn("a", "U"))),
                field(
                    5,
                    "flat",
                    Scalar(Builtin.BOOL),
                    annotations = sql("strategy" to AnnotationValue.Name("json")),
                ),
                nested = listOf(record("a", "N", field(1, "y", Scalar(Builtin.BOOL)), line = 25)),
            )
        val lowered = lower(namespace("a", leaf, u, r))
        assertEquals(
            listOf(
                "12 SCH2103 field 'R.many': target 'sql' cannot lower lists yet (SCH-28)",
                "13 SCH2103 field 'R.map': target 'sql' cannot lower maps yet (SCH-28)",
                "14 SCH2103 field 'R.choice': target 'sql' cannot lower unions yet (SCH-28)",
                "15 SCH2103 field 'R.flat': target 'sql' cannot lower mapping strategies yet (SCH-28)",
                "25 SCH2106 record 'N' has no primary key and is not used by any field; mark key fields with @sql(key) or the record with @sql(key = (...))",
            ),
            messages(lowered),
        )
        assertEquals(listOf("id", "ref_x"), table(lowered, "r").columns.map { it.name })
        assertEquals(listOf("leaf", "r"), lowered.model.schemas.single().tables.map { it.name })
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
    }

    @Test
    fun `field keys form the primary key in declaration order, unique and index cover their column`() {
        val r =
            record(
                "a",
                "Account",
                field(
                    1,
                    "tenant",
                    Scalar(Builtin.UUID),
                    annotations = sql("key" to AnnotationValue.Flag),
                ),
                field(
                    2,
                    "email",
                    Scalar(Builtin.STRING),
                    annotations = sql("unique" to AnnotationValue.Flag),
                ),
                field(
                    3,
                    "id",
                    Scalar(Builtin.UUID),
                    annotations = sql("key" to AnnotationValue.Flag),
                ),
                field(
                    4,
                    "created",
                    Scalar(Builtin.INSTANT),
                    annotations = sql("index" to AnnotationValue.Flag),
                ),
            )
        val lowered = lower(namespace("a", r))
        assertEquals(emptyList(), messages(lowered))
        val t = table(lowered, "account")
        assertEquals(listOf("tenant", "id"), t.primaryKey)
        assertEquals(listOf(Unique("uq_account_email", listOf("email"))), t.uniques)
        assertEquals(listOf(Index("ix_account_created", listOf("created"))), t.indexes)
    }

    @Test
    fun `a record key names columns in its own order and must name fields`() {
        val plan =
            record(
                "a",
                "Plan",
                field(1, "tenant_id", Scalar(Builtin.UUID)),
                field(
                    2,
                    "code",
                    Scalar(Builtin.STRING),
                    annotations = sql("column" to str("plan_code")),
                ),
                annotations = sql("key" to AnnotationValue.Names(listOf("code", "tenant_id"))),
            )
        val bad =
            record(
                "a",
                "Bad",
                field(1, "x", Scalar(Builtin.BOOL)),
                line = 8,
                annotations = sql("key" to AnnotationValue.Names(listOf("x", "nope"))),
            )
        val both =
            record(
                "a",
                "Both",
                field(
                    1,
                    "x",
                    Scalar(Builtin.BOOL),
                    line = 12,
                    annotations = sql("key" to AnnotationValue.Flag),
                ),
                line = 11,
                annotations = sql("key" to AnnotationValue.Names(listOf("x"))),
            )
        val lowered = lower(namespace("a", plan, bad, both))
        assertEquals(listOf("plan_code", "tenant_id"), table(lowered, "plan").primaryKey)
        assertEquals(
            listOf(
                "8 SCH2107 record 'Bad': @sql(key) names 'nope', which is not a field of the record",
                "11 SCH2107 record 'Both' declares @sql(key) on both the record and its fields",
            ),
            messages(lowered),
        )
    }

    @Test
    fun `a top-level record without a key is an error`() {
        val lowered =
            lower(namespace("a", record("a", "Loose", field(1, "x", Scalar(Builtin.BOOL)))))
        assertEquals(
            listOf(
                "3 SCH2106 record 'Loose' has no primary key and is not used by any field; mark key fields with @sql(key) or the record with @sql(key = (...))"
            ),
            messages(lowered),
        )
        assertEquals(emptyList(), lowered.model.schemas.single().tables)
    }

    @Test
    fun `identifiers over 63 characters are truncated and reported once`() {
        val long =
            "a_very_long_field_name_that_goes_well_beyond_the_sixty_three_character_limit_of_postgres"
        val r =
            record(
                "a",
                "User",
                field(
                    1,
                    "id",
                    Scalar(Builtin.UUID),
                    annotations = sql("key" to AnnotationValue.Flag),
                ),
                field(
                    2,
                    long,
                    Scalar(Builtin.INT32, Refinements(min = big(0))),
                    annotations = sql("unique" to AnnotationValue.Flag),
                ),
            )
        val lowered = lower(namespace("a", r))
        val t = table(lowered, "user")
        assertEquals("pk_user", t.primaryKeyName)
        val column = "a_very_long_field_name_that_goes_well_beyond_the_sixty__b7ff5eb"
        assertEquals(column, t.columns[1].name)
        assertEquals(63, t.checks.single().name.length)
        assertTrue(t.checks.single().name.startsWith("ck_user_a_very_long"))
        assertTrue(t.checks.single().expression.startsWith("\"$column\" >= 0"))
        assertEquals(63, t.uniques.single().name.length)
        assertEquals(
            listOf(
                "12 SCH2109 identifier '$long' exceeds 63 bytes; truncated to '$column'",
                "12 SCH2109 identifier 'ck_user_${long}_min' exceeds 63 bytes; truncated to '${t.checks.single().name}'",
                "12 SCH2109 identifier 'uq_user_$long' exceeds 63 bytes; truncated to '${t.uniques.single().name}'",
            ),
            messages(lowered),
        )
    }

    @Test
    fun `long table names truncate the table and its pk name`() {
        val name = "A" + "b".repeat(69)
        val r =
            record(
                "a",
                name,
                field(
                    1,
                    "id",
                    Scalar(Builtin.UUID),
                    annotations = sql("key" to AnnotationValue.Flag),
                ),
            )
        val lowered = lower(namespace("a", r))
        val t = lowered.model.schemas.single().tables.single()
        val raw = "a" + "b".repeat(69)
        assertEquals(70, raw.length)
        assertEquals(63, t.name.length)
        assertEquals(Naming.identifier(raw), t.name)
        assertEquals(Naming.identifier("pk_${t.name}"), t.primaryKeyName)
        assertEquals(63, t.primaryKeyName!!.length)
        assertTrue(t.primaryKeyName!!.startsWith("pk_a"))
        assertEquals(
            listOf(
                "3 SCH2109 identifier '$raw' exceeds 63 bytes; truncated to '${t.name}'",
                "3 SCH2109 identifier 'pk_${t.name}' exceeds 63 bytes; truncated to '${t.primaryKeyName}'",
            ),
            messages(lowered),
        )
    }

    @Test
    fun `derived relation names collide across tables`() {
        val key = sql("key" to AnnotationValue.Flag)
        val unique = sql("unique" to AnnotationValue.Flag)
        val order =
            record(
                "a",
                "Order",
                field(1, "id", Scalar(Builtin.UUID), annotations = key),
                field(2, "line_id", Scalar(Builtin.UUID), annotations = unique),
                line = 10,
            )
        val orderLine =
            record(
                "a",
                "OrderLine",
                field(1, "order_id", Scalar(Builtin.UUID), line = 21, annotations = key),
                field(2, "id", Scalar(Builtin.UUID), line = 22, annotations = unique),
                line = 20,
            )
        val pkFoo =
            record(
                "a",
                "PkFoo",
                field(1, "id", Scalar(Builtin.UUID), line = 31, annotations = key),
                line = 30,
            )
        val foo =
            record(
                "a",
                "Foo",
                field(1, "id", Scalar(Builtin.UUID), line = 41, annotations = key),
                line = 40,
            )
        val lowered = lower(namespace("a", order, orderLine, pkFoo, foo))
        assertEquals(
            listOf(
                "22 SCH2111 relation name 'uq_order_line_id' is already used by unique 'uq_order_line_id' (o.schemata:12)",
                "40 SCH2111 relation name 'pk_foo' is already used by table 'pk_foo' (o.schemata:30)",
            ),
            messages(lowered),
        )
    }

    @Test
    fun `a unique or index on the key columns is redundant`() {
        val r =
            record(
                "a",
                "K",
                field(
                    1,
                    "id",
                    Scalar(Builtin.UUID),
                    annotations =
                        sql(
                            "key" to AnnotationValue.Flag,
                            "unique" to AnnotationValue.Flag,
                            "index" to AnnotationValue.Flag,
                        ),
                ),
            )
        val lowered = lower(namespace("a", r))
        val t = table(lowered, "k")
        assertEquals(listOf("id"), t.primaryKey)
        assertEquals(emptyList(), t.uniques)
        assertEquals(emptyList(), t.indexes)
        assertEquals(
            listOf(
                "11 SCH2113 field 'K.id': @sql(unique) duplicates the primary key; dropped",
                "11 SCH2113 field 'K.id': @sql(index) duplicates the primary key; dropped",
            ),
            messages(lowered),
        )
    }

    @Test
    fun `long schema names are truncated`() {
        val raw = "s".repeat(70)
        val lowered = lower(namespace("x.$raw"))
        val schemaName = lowered.model.schemas.single().schemaName
        assertEquals(63, schemaName.length)
        assertEquals(Naming.identifier(raw), schemaName)
        assertEquals(
            listOf("1 SCH2109 identifier '$raw' exceeds 63 bytes; truncated to '$schemaName'"),
            messages(lowered),
        )
    }

    @Test
    fun `Postgres type limits are respected`() {
        val r =
            record(
                "a",
                "R",
                field(
                    1,
                    "id",
                    Scalar(Builtin.UUID),
                    annotations = sql("key" to AnnotationValue.Flag),
                ),
                field(2, "s", Scalar(Builtin.STRING, Refinements(max = big(0)))),
                field(3, "t", Scalar(Builtin.STRING, Refinements(max = big(20_000_000)))),
                field(4, "d", Scalar(Builtin.DECIMAL, Refinements(precision = 1001, scale = 0))),
                field(5, "v", Scalar(Builtin.STRING, Refinements(max = big(10_485_760)))),
            )
        val lowered = lower(namespace("a", r))
        assertEquals(
            listOf(
                "14 SCH2112 field 'R.d': decimal precision 1001 exceeds Postgres's limit of 1000"
            ),
            messages(lowered),
        )
        val t = table(lowered, "r")
        assertEquals(listOf("id", "s", "t", "v"), t.columns.map { it.name })
        assertEquals(
            listOf(
                ColumnType.UUID,
                ColumnType.TEXT,
                ColumnType.TEXT,
                ColumnType.VARCHAR(10_485_760),
            ),
            t.columns.map { it.type },
        )
        assertEquals(
            listOf(
                Check("ck_r_s_max", "char_length(\"s\") <= 0"),
                Check("ck_r_t_max", "char_length(\"t\") <= 20000000"),
            ),
            t.checks,
        )
    }

    @Test
    fun `key fields must be distinct and not nullable`() {
        val dup =
            record(
                "a",
                "Dup",
                field(1, "a", Scalar(Builtin.UUID)),
                field(2, "b", Scalar(Builtin.UUID)),
                annotations = sql("key" to AnnotationValue.Names(listOf("a", "b", "a", "b", "a"))),
            )
        val opt =
            record(
                "a",
                "Opt",
                field(
                    1,
                    "x",
                    Scalar(Builtin.UUID),
                    nullable = true,
                    line = 21,
                    annotations = sql("key" to AnnotationValue.Flag),
                ),
                line = 20,
            )
        val optRecord =
            record(
                "a",
                "OptRecord",
                field(1, "y", Scalar(Builtin.UUID), nullable = true, line = 31),
                line = 30,
                annotations = sql("key" to AnnotationValue.Names(listOf("y"))),
            )
        val lowered = lower(namespace("a", dup, opt, optRecord))
        assertEquals(listOf("a", "b"), table(lowered, "dup").primaryKey)
        assertEquals(
            listOf(
                "3 SCH2107 record 'Dup': @sql(key) names 'a' more than once",
                "3 SCH2107 record 'Dup': @sql(key) names 'b' more than once",
                "21 SCH2107 record 'Opt': key field 'x' is nullable; a primary key column cannot be",
                "31 SCH2107 record 'OptRecord': key field 'y' is nullable; a primary key column cannot be",
            ),
            messages(lowered),
        )
    }

    @Test
    fun `final names collide across overrides`() {
        val a =
            record(
                "a",
                "A",
                field(
                    1,
                    "id",
                    Scalar(Builtin.UUID),
                    annotations = sql("key" to AnnotationValue.Flag),
                ),
                line = 3,
                annotations = sql("table" to str("same")),
            )
        val b =
            record(
                "a",
                "B",
                field(
                    1,
                    "id",
                    Scalar(Builtin.UUID),
                    annotations = sql("key" to AnnotationValue.Flag),
                ),
                line = 6,
                annotations = sql("table" to str("same")),
            )
        val c =
            record(
                "a",
                "C",
                field(
                    1,
                    "id",
                    Scalar(Builtin.UUID),
                    line = 10,
                    annotations = sql("key" to AnnotationValue.Flag),
                ),
                field(2, "x", Scalar(Builtin.BOOL), line = 11),
                field(
                    3,
                    "y",
                    Scalar(Builtin.BOOL),
                    line = 12,
                    annotations = sql("column" to str("x")),
                ),
                line = 9,
            )
        val lowered =
            lower(
                namespace("a", a, b, c),
                namespace("b.x", file = "p.schemata", annotations = sql("schema" to str("a"))),
            )
        assertEquals(
            listOf(
                "1 SCH2102 namespaces a and b.x both lower to schema 'a'",
                "3 SCH2101 records A and B both lower to table 'same'",
                "12 SCH2111 field 'C.y' lowers to column 'x', already used by field 'x' (o.schemata:11)",
            ),
            messages(lowered),
        )
    }
}

private operator fun <T> List<T>.times(n: Int): List<T> = List(n) { this }.flatten()
