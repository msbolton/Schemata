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
                field(1, "a", Scalar(Builtin.BOOL)),
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
                field(1, "small", Scalar(Builtin.INT32, Refinements(min = big(0), max = big(100)))),
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
                field(1, "flag", Scalar(Builtin.BOOL), default = BoolValue(true)),
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
                    annotations = sql("type" to str("citext")),
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
                field(1, "sku", Scalar(Builtin.STRING), doc = "Stock keeping unit."),
                doc = "A product.",
            )
        val t = table(lower(namespace("a", r)), "product")
        assertEquals("A product." to "Stock keeping unit.", t.doc to t.columns.single().doc)
    }

    @Test
    fun `structural shapes are still reported at the boundary`() {
        val leaf = record("a", "Leaf", field(1, "x", Scalar(Builtin.BOOL)), line = 20)
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
                "11 SCH2103 field 'R.ref': target 'sql' cannot lower record references yet (SCH-28)",
                "12 SCH2103 field 'R.many': target 'sql' cannot lower lists yet (SCH-28)",
                "13 SCH2103 field 'R.map': target 'sql' cannot lower maps yet (SCH-28)",
                "14 SCH2103 field 'R.choice': target 'sql' cannot lower unions yet (SCH-28)",
                "15 SCH2103 field 'R.flat': target 'sql' cannot lower mapping strategies yet (SCH-28)",
                "25 SCH2103 target 'sql' cannot lower nested declarations yet (SCH-28)",
            ),
            messages(lowered),
        )
        assertEquals(emptyList(), table(lowered, "r").columns)
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
}

private operator fun <T> List<T>.times(n: Int): List<T> = List(n) { this }.flatten()
