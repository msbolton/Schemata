package io.schemata.importer.sql

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val HAND =
    """
CREATE SCHEMA shop;
CREATE TABLE shop.orders (
  id uuid PRIMARY KEY,
  status text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'paid')),
  qty smallint NOT NULL CHECK (qty > 0),
  note varchar(500),
  customer_id uuid NOT NULL REFERENCES shop.customers (id) ON DELETE RESTRICT,
  seq serial,
  n integer GENERATED ALWAYS AS IDENTITY,
  CONSTRAINT uq_orders_note UNIQUE (note)
);
CREATE INDEX ix_orders_status ON shop.orders (status);
CREATE UNIQUE INDEX ON shop.orders (customer_id, status) WHERE status = 'paid';
COMMENT ON TABLE shop.orders IS 'Orders.';
COMMENT ON COLUMN shop.orders.note IS 'A note.';
CREATE VIEW shop.v AS SELECT 1;
"""

private const val DUMP =
    """
SET statement_timeout = 0;
SELECT pg_catalog.set_config('search_path', '', false);
CREATE SCHEMA shop;
ALTER SCHEMA shop OWNER TO app;
CREATE TABLE shop.orders (
    id uuid NOT NULL,
    status text DEFAULT 'pending'::text NOT NULL,
    qty smallint NOT NULL,
    note character varying(500),
    customer_id uuid NOT NULL,
    seq integer NOT NULL,
    n integer NOT NULL,
    CONSTRAINT orders_qty_check CHECK ((qty > 0)),
    CONSTRAINT orders_status_check CHECK ((status = ANY (ARRAY['pending'::text, 'paid'::text])))
);
ALTER TABLE shop.orders OWNER TO app;
CREATE SEQUENCE shop.orders_seq_seq AS integer START WITH 1;
ALTER TABLE ONLY shop.orders ALTER COLUMN seq SET DEFAULT nextval('shop.orders_seq_seq'::regclass);
ALTER TABLE ONLY shop.orders ADD CONSTRAINT orders_pkey PRIMARY KEY (id);
ALTER TABLE ONLY shop.orders ADD CONSTRAINT uq_orders_note UNIQUE (note);
ALTER TABLE ONLY shop.orders ADD CONSTRAINT orders_customer_id_fkey FOREIGN KEY (customer_id) REFERENCES shop.customers(id) ON DELETE RESTRICT;
CREATE INDEX ix_orders_status ON shop.orders USING btree (status);
COMMENT ON TABLE shop.orders IS 'Orders.';
"""

class SqlReaderTest {
    private fun table(sql: String): SqlTable {
        val f = SqlReader.read("t.sql", sql)
        assertEquals(emptyList(), f.errors)
        return (f.statements.single() as SqlStatement.CreateTable).table
    }

    @Test
    fun `a hand written file`() {
        val f = SqlReader.read("hand.sql", HAND)
        assertEquals(emptyList(), f.errors)
        val table = (f.statements[1] as SqlStatement.CreateTable).table
        assertEquals("shop" to "orders", table.schema to table.name)
        assertEquals(
            listOf("uuid", "text", "smallint", "varchar(500)", "uuid", "serial", "integer"),
            table.columns.map { it.type },
        )
        assertTrue(table.columns[6].identity)
        assertEquals(SqlExpr.Str("pending"), table.columns[1].default)
        assertEquals(
            listOf("PrimaryKey", "Check", "Check", "ForeignKey", "Unique"),
            table.constraints.map { it::class.simpleName },
        )
        val fk = table.constraints[3] as SqlConstraint.ForeignKey
        assertEquals("RESTRICT", fk.onDelete)
        assertEquals("customers", fk.refTable)
        val index = f.statements[2] as SqlStatement.CreateIndex
        assertEquals(listOf("status"), index.columns)
        assertFalse(index.unique)
        assertTrue((f.statements[3] as SqlStatement.CreateIndex).filtered)
        assertEquals("Orders.", (f.statements[4] as SqlStatement.CommentOn).text)
        assertEquals("CREATE VIEW", (f.statements[6] as SqlStatement.Dropped).kind)
    }

    @Test
    fun `a hand written file in detail`() {
        val f = SqlReader.read("hand.sql", HAND)
        val table = (f.statements[1] as SqlStatement.CreateTable).table
        assertEquals(
            listOf(true, true, true, false, true, true, true),
            table.columns.map { it.notNull },
        )
        assertEquals(
            listOf(
                SqlConstraint.PrimaryKey(null, listOf("id")),
                SqlConstraint.Check(
                    null,
                    SqlExpr.In(
                        SqlExpr.Col("status"),
                        listOf(SqlExpr.Str("pending"), SqlExpr.Str("paid")),
                    ),
                    "status in ('pending', 'paid')",
                ),
                SqlConstraint.Check(
                    null,
                    SqlExpr.Bin(">", SqlExpr.Col("qty"), SqlExpr.Num("0")),
                    "qty > 0",
                ),
                SqlConstraint.ForeignKey(
                    null,
                    listOf("customer_id"),
                    "shop",
                    "customers",
                    listOf("id"),
                    "RESTRICT",
                    null,
                    emptyList(),
                ),
                SqlConstraint.Unique("uq_orders_note", listOf("note")),
            ),
            table.constraints,
        )
        assertEquals(
            SqlStatement.CreateIndex(
                null,
                true,
                "shop",
                "orders",
                listOf("customer_id", "status"),
                null,
                true,
                SqlPos(14, 1),
            ),
            f.statements[3],
        )
        assertEquals(
            SqlStatement.CommentOn("COLUMN", "shop", "orders", "note", "A note.", SqlPos(16, 1)),
            f.statements[5],
        )
        assertEquals(SqlStatement.CreateSchema("shop", SqlPos(2, 1)), f.statements[0])
    }

    @Test
    fun `a dump reads to the same table`() {
        val f = SqlReader.read("dump.sql", DUMP)
        assertEquals(emptyList(), f.errors)
        assertEquals(
            listOf(
                "Ignored",
                "Ignored",
                "CreateSchema",
                "Ignored",
                "CreateTable",
                "Ignored",
                "Ignored",
                "Ignored",
                "AlterAdd",
                "AlterAdd",
                "AlterAdd",
                "CreateIndex",
                "CommentOn",
            ),
            f.statements.map { it::class.simpleName },
        )
        val table = (f.statements[4] as SqlStatement.CreateTable).table
        assertEquals("varchar(500)", table.columns[3].type)
        val check = table.constraints.filterIsInstance<SqlConstraint.Check>().last()
        assertEquals(
            SqlExpr.In(SqlExpr.Col("status"), listOf(SqlExpr.Str("pending"), SqlExpr.Str("paid"))),
            check.expr,
        )
        assertEquals("btree", (f.statements[11] as SqlStatement.CreateIndex).using)
    }

    @Test
    fun `a dump in detail`() {
        val f = SqlReader.read("dump.sql", DUMP)
        assertEquals(
            listOf(
                SqlStatement.Ignored("SET"),
                SqlStatement.Ignored("SELECT"),
                SqlStatement.Ignored("ALTER SCHEMA"),
                SqlStatement.Ignored("ALTER TABLE"),
                SqlStatement.Ignored("CREATE SEQUENCE"),
                SqlStatement.Ignored("ALTER COLUMN"),
            ),
            f.statements.filterIsInstance<SqlStatement.Ignored>(),
        )
        assertEquals(
            SqlStatement.AlterAdd(
                "shop",
                "orders",
                SqlConstraint.ForeignKey(
                    "orders_customer_id_fkey",
                    listOf("customer_id"),
                    "shop",
                    "customers",
                    listOf("id"),
                    "RESTRICT",
                    null,
                    emptyList(),
                ),
                SqlPos(22, 1),
            ),
            f.statements[10],
        )
        val table = (f.statements[4] as SqlStatement.CreateTable).table
        assertEquals(SqlExpr.Str("pending"), table.columns[1].default)
        assertEquals(
            SqlConstraint.Check(
                "orders_qty_check",
                SqlExpr.Bin(">", SqlExpr.Col("qty"), SqlExpr.Num("0")),
                "(qty > 0)",
            ),
            table.constraints.first(),
        )
    }

    @Test
    fun `a bad statement is reported and the rest is read`() {
        val f = SqlReader.read("x.sql", "CREATE TABLE a (;\nCREATE TABLE b (id uuid PRIMARY KEY);")
        assertEquals(listOf(SqlParseError(SqlPos(1, 17), "expected a column name")), f.errors)
        assertEquals(1, f.statements.filterIsInstance<SqlStatement.CreateTable>().size)
    }

    @Test
    fun `an unknown statement and a lexer error are errors`() {
        val f = SqlReader.read("x.sql", "FROB the table;\nCREATE SCHEMA s;")
        assertEquals(listOf(SqlParseError(SqlPos(1, 1), "unknown statement 'frob the'")), f.errors)
        assertEquals(
            listOf<SqlStatement>(SqlStatement.CreateSchema("s", SqlPos(2, 1))),
            f.statements,
        )
        val g = SqlReader.read("y.sql", "CREATE SCHEMA s;\nCREATE TABLE t (a text DEFAULT 'x);")
        assertEquals(listOf(SqlParseError(SqlPos(2, 32), "unterminated string")), g.errors)
        assertEquals(emptyList(), g.statements)
    }

    @Test
    fun `notes and column comments attach`() {
        val f =
            SqlReader.read(
                "n.sql",
                "CREATE TABLE t (\n  \"legacy\" varchar(36),  -- schemata: uuid?\n  \"tags\" text[] NOT NULL,  -- schemata: list<string(max = 16)>\n  \"m\" jsonb NOT NULL  -- schemata: map<string, string>\n);",
            )
        val cols = (f.statements.single() as SqlStatement.CreateTable).table.columns
        assertEquals(
            listOf("uuid?", "list<string(max = 16)>", "map<string, string>"),
            cols.map { it.note },
        )
    }

    @Test
    fun `a note on the next line attaches and other comments do not`() {
        val cols =
            table(
                    """
                    CREATE TABLE t (
                      a text,
                      -- schemata: string(max = 3)
                      b text,  -- just a remark
                      c text,
                      d text -- schemata: uuid
                      , e text
                    );
                    """
                        .trimIndent()
                )
                .columns
        assertEquals(listOf("string(max = 3)", null, null, "uuid", null), cols.map { it.note })
        assertTrue(cols.all { it.doc == null })
    }

    @Test
    fun `types are canonicalised`() {
        val cols =
            table(
                    """
                    CREATE TABLE t (
                      a character varying(4), b character varying, c timestamp with time zone,
                      d timestamp without time zone, e time without time zone, f time with time zone,
                      g numeric(19,4), h int, i int4, j int8, k int2, l float4, m float8, n bool,
                      o bigserial, p integer[], q character(3), r double precision, s pg_catalog.int4,
                      t public.citext, u timestamp(3) with time zone, v "Status", w int ARRAY,
                      x varchar(10)[][], y decimal(10, 2), z interval
                    );
                    """
                        .trimIndent()
                )
                .columns
        assertEquals(
            listOf(
                "varchar(4)",
                "varchar",
                "timestamptz",
                "timestamp",
                "time",
                "timetz",
                "numeric(19, 4)",
                "integer",
                "integer",
                "bigint",
                "smallint",
                "real",
                "double precision",
                "boolean",
                "bigserial",
                "integer[]",
                "char(3)",
                "double precision",
                "integer",
                "public.citext",
                "timestamptz(3)",
                "\"Status\"",
                "integer[]",
                "varchar(10)[][]",
                "numeric(10, 2)",
                "interval",
            ),
            cols.map { it.type },
        )
    }

    @Test
    fun `column clauses in any order`() {
        val t =
            table(
                """
                CREATE TABLE IF NOT EXISTS "S"."T" (
                  a integer CONSTRAINT a_pos CHECK (a > 0) NOT NULL DEFAULT -1 COLLATE "C",
                  b uuid CONSTRAINT b_fk REFERENCES other ON UPDATE CASCADE ON DELETE SET NULL DEFERRABLE INITIALLY DEFERRED,
                  c integer GENERATED BY DEFAULT AS IDENTITY (START WITH 10) UNIQUE,
                  d numeric GENERATED ALWAYS AS (a * 2) STORED,
                  e timestamptz DEFAULT now() NULL,
                  f text DEFAULT 'a' || 'b',
                  PRIMARY KEY (a, c),
                  FOREIGN KEY (a) REFERENCES s2.p (x) MATCH FULL,
                  EXCLUDE USING gist (a WITH =),
                  LIKE other
                ) INHERITS (base) TABLESPACE fast;
                """
                    .trimIndent()
            )
        assertEquals("S" to "T", t.schema to t.name)
        assertEquals(listOf(true, false, true, false, false, false), t.columns.map { it.notNull })
        assertEquals(
            listOf(
                SqlExpr.Num("-1"),
                null,
                null,
                null,
                SqlExpr.Call("now", emptyList()),
                SqlExpr.Raw("'a' || 'b'"),
            ),
            t.columns.map { it.default },
        )
        assertEquals(listOf(false, false, true, false, false, false), t.columns.map { it.identity })
        assertEquals(
            listOf(
                SqlConstraint.Check(
                    "a_pos",
                    SqlExpr.Bin(">", SqlExpr.Col("a"), SqlExpr.Num("0")),
                    "a > 0",
                ),
                SqlConstraint.ForeignKey(
                    "b_fk",
                    listOf("b"),
                    null,
                    "other",
                    emptyList(),
                    "SET NULL",
                    "CASCADE",
                    listOf("DEFERRABLE", "INITIALLY DEFERRED"),
                ),
                SqlConstraint.Unique(null, listOf("c")),
                SqlConstraint.PrimaryKey(null, listOf("a", "c")),
                SqlConstraint.ForeignKey(
                    null,
                    listOf("a"),
                    "s2",
                    "p",
                    listOf("x"),
                    null,
                    null,
                    listOf("MATCH FULL"),
                ),
            ),
            t.constraints,
        )
        assertEquals(
            listOf(
                "GENERATED … STORED on column 'd'",
                "EXCLUDE constraint",
                "LIKE",
                "INHERITS",
                "TABLESPACE",
            ),
            t.dropped,
        )
    }

    @Test
    fun `alter table forms`() {
        val f =
            SqlReader.read(
                "a.sql",
                """
                ALTER TABLE IF EXISTS ONLY s.t ADD CONSTRAINT k PRIMARY KEY (id) , ADD UNIQUE (x) NOT VALID;
                ALTER TABLE t ADD CHECK (x <> 'y');
                ALTER TABLE t ADD COLUMN z text;
                ALTER TABLE t ADD CONSTRAINT ex EXCLUDE USING gist (a WITH &&);
                ALTER TABLE t ALTER COLUMN z DROP NOT NULL;
                ALTER SEQUENCE s OWNED BY t.id;
                """
                    .trimIndent(),
            )
        assertEquals(emptyList(), f.errors)
        assertEquals(
            listOf(
                SqlStatement.AlterAdd(
                    "s",
                    "t",
                    SqlConstraint.PrimaryKey("k", listOf("id")),
                    SqlPos(1, 1),
                ),
                SqlStatement.AlterAdd(
                    "s",
                    "t",
                    SqlConstraint.Unique(null, listOf("x")),
                    SqlPos(1, 1),
                ),
                SqlStatement.AlterAdd(
                    null,
                    "t",
                    SqlConstraint.Check(
                        null,
                        SqlExpr.Bin("<>", SqlExpr.Col("x"), SqlExpr.Str("y")),
                        "x <> 'y'",
                    ),
                    SqlPos(2, 1),
                ),
                SqlStatement.Dropped("ALTER TABLE ADD COLUMN", SqlPos(3, 1)),
                SqlStatement.Dropped("EXCLUDE constraint", SqlPos(4, 1)),
                SqlStatement.Ignored("ALTER COLUMN"),
                SqlStatement.Ignored("ALTER SEQUENCE"),
            ),
            f.statements,
        )
    }

    @Test
    fun `indexes comments and other statements`() {
        val f =
            SqlReader.read(
                "i.sql",
                """
                CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS ix ON ONLY t USING btree (a DESC NULLS LAST, "B" text_pattern_ops) INCLUDE (c) WITH (fillfactor = 70);
                CREATE INDEX ix2 ON t (lower(a));
                COMMENT ON COLUMN t.a IS 'A.';
                COMMENT ON SCHEMA s IS 'S.';
                COMMENT ON TABLE t IS NULL;
                CREATE OR REPLACE FUNCTION f() RETURNS int LANGUAGE sql AS ${'$'}${'$'}SELECT 1;${'$'}${'$'};
                CREATE FUNCTION g() RETURNS int LANGUAGE sql BEGIN ATOMIC SELECT 1; SELECT CASE WHEN true THEN 1 END; END;
                CREATE RULE r AS ON INSERT TO t DO ALSO (NOTIFY a; NOTIFY b);
                CREATE MATERIALIZED VIEW mv AS SELECT 1;
                CREATE TYPE mood AS ENUM ('a', 'b');
                CREATE EXTENSION IF NOT EXISTS citext;
                GRANT ALL ON TABLE t TO app;
                CREATE TABLE p PARTITION OF q FOR VALUES IN (1);
                """
                    .trimIndent(),
            )
        assertEquals(emptyList(), f.errors)
        assertEquals(
            listOf(
                SqlStatement.CreateIndex(
                    "ix",
                    true,
                    null,
                    "t",
                    listOf("a", "B"),
                    "btree",
                    false,
                    SqlPos(1, 1),
                ),
                SqlStatement.Dropped("CREATE INDEX on an expression", SqlPos(2, 1)),
                SqlStatement.CommentOn("COLUMN", null, "t", "a", "A.", SqlPos(3, 1)),
                SqlStatement.Ignored("COMMENT ON SCHEMA"),
                SqlStatement.Ignored("COMMENT ON TABLE"),
                SqlStatement.Dropped("CREATE FUNCTION", SqlPos(6, 1)),
                SqlStatement.Dropped("CREATE FUNCTION", SqlPos(7, 1)),
                SqlStatement.Dropped("CREATE RULE", SqlPos(8, 1)),
                SqlStatement.Dropped("CREATE MATERIALIZED VIEW", SqlPos(9, 1)),
                SqlStatement.Dropped("CREATE TYPE", SqlPos(10, 1)),
                SqlStatement.Ignored("CREATE EXTENSION"),
                SqlStatement.Ignored("GRANT"),
                SqlStatement.Dropped("CREATE TABLE PARTITION OF", SqlPos(13, 1)),
            ),
            f.statements,
        )
    }

    @Test
    fun `a full pg_dump preamble and its other statements`() {
        val f =
            SqlReader.read(
                "d.sql",
                """
                --
                -- PostgreSQL database dump
                --
                \restrict abc123
                SET default_table_access_method = heap;
                CREATE TYPE public.mood AS ENUM (
                    'sad',
                    'ok'
                );
                CREATE DOMAIN public.pos AS integer CONSTRAINT pos_check CHECK ((VALUE > 0));
                CREATE FUNCTION public.touch() RETURNS trigger
                    LANGUAGE plpgsql
                    AS ${'$'}_${'$'}BEGIN NEW.at := now(); RETURN NEW; END;${'$'}_${'$'};
                CREATE TABLE public.t (
                    id bigint NOT NULL,
                    m public.mood,
                    at timestamp with time zone DEFAULT now() NOT NULL
                );
                ALTER TABLE public.t ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
                    SEQUENCE NAME public.t_id_seq
                    START WITH 1
                    CACHE 1
                );
                COPY public.t (id, m, at) FROM stdin;
                1	sad	2020-01-01 00:00:00+00
                \.
                CREATE TRIGGER t_touch BEFORE UPDATE ON public.t FOR EACH ROW EXECUTE FUNCTION public.touch();
                \unrestrict abc123
                """
                    .trimIndent(),
            )
        assertEquals(emptyList(), f.errors)
        assertEquals(
            listOf(
                "Ignored",
                "Dropped",
                "Dropped",
                "Dropped",
                "CreateTable",
                "Ignored",
                "Ignored",
                "Dropped",
            ),
            f.statements.map { it::class.simpleName },
        )
        assertEquals(
            listOf("bigint", "public.mood", "timestamptz"),
            (f.statements[4] as SqlStatement.CreateTable).table.columns.map { it.type },
        )
    }

    @Test
    fun `the reader never throws on a truncated file`() {
        val text = HAND + DUMP
        for (n in text.indices) SqlReader.read("x.sql", text.substring(0, n))
    }

    @Test
    fun `an empty table and a missing semicolon at the end`() {
        val t = table("CREATE TABLE t ()")
        assertEquals(emptyList(), t.columns)
        assertNull(t.schema)
        val f = SqlReader.read("x.sql", "CREATE SCHEMA a CREATE SCHEMA b;")
        assertEquals(listOf(SqlParseError(SqlPos(1, 17), "expected ';'")), f.errors)
    }
}
