package io.schemata.importer.sql

import io.schemata.importer.ImportInput
import io.schemata.importer.ImportResult
import kotlin.test.Test
import kotlin.test.assertEquals

private const val HAND =
    """
CREATE SCHEMA shop;
CREATE TABLE shop.customers (
  id uuid PRIMARY KEY,
  name text NOT NULL
);
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
CREATE TABLE shop.customers (
    id uuid NOT NULL,
    name text NOT NULL
);
ALTER TABLE shop.customers OWNER TO app;
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
ALTER TABLE shop.orders ALTER COLUMN n ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME shop.orders_n_seq
    START WITH 1
);
CREATE SEQUENCE shop.orders_seq_seq AS integer START WITH 1;
ALTER TABLE ONLY shop.orders ALTER COLUMN seq SET DEFAULT nextval('shop.orders_seq_seq'::regclass);
ALTER TABLE ONLY shop.customers ADD CONSTRAINT customers_pkey PRIMARY KEY (id);
ALTER TABLE ONLY shop.orders ADD CONSTRAINT orders_pkey PRIMARY KEY (id);
ALTER TABLE ONLY shop.orders ADD CONSTRAINT uq_orders_note UNIQUE (note);
ALTER TABLE ONLY shop.orders ADD CONSTRAINT orders_customer_id_fkey FOREIGN KEY (customer_id) REFERENCES shop.customers(id) ON DELETE RESTRICT;
CREATE INDEX ix_orders_status ON shop.orders USING btree (status);
COMMENT ON TABLE shop.orders IS 'Orders.';
COMMENT ON COLUMN shop.orders.note IS 'A note.';
"""

class SqlImportTest {
    private fun importText(vararg files: Pair<String, String>): ImportResult =
        SqlImporter.import(
            files.map { ImportInput(it.first, it.second.trimIndent(), relative = it.first) }
        )

    private fun importLone(vararg files: Pair<String, String>): ImportResult =
        SqlImporter.import(files.map { ImportInput(it.first, it.second.trimIndent()) })

    private fun text(result: ImportResult, path: String): String =
        result.files.single { it.path == path }.content

    private fun messages(result: ImportResult): List<String> =
        result.diagnostics.map { "${it.code.id} ${it.message}" }

    private fun schemata(text: String) = text.trimIndent() + "\n"

    @Test
    fun `scalars types defaults and refinements`() {
        val r =
            importText(
                "t.sql" to
                    """
                    CREATE TABLE t.t (
                      id uuid PRIMARY KEY,
                      flag boolean NOT NULL DEFAULT true,
                      n integer NOT NULL DEFAULT 3 CHECK (n >= 0 AND n <= 100),
                      big bigint CHECK (big > 0),
                      ratio real NOT NULL,
                      exact double precision NOT NULL CHECK (exact <= 1.5),
                      price numeric(10, 2) NOT NULL DEFAULT 1.50,
                      amount numeric,
                      small smallint NOT NULL,
                      seq serial,
                      name varchar(64) NOT NULL DEFAULT 'x',
                      label text NOT NULL CHECK (char_length(label) >= 2 AND char_length(label) <= 8),
                      blob bytea CHECK (octet_length(blob) <= 1024),
                      code text CHECK (code ~* '^[a-z]+$'),
                      email text NOT NULL CHECK (email ~ '^[^@]+@[^@]+$'),
                      score integer NOT NULL CHECK (score BETWEEN 1 AND 5),
                      below integer NOT NULL CHECK (below < 10),
                      day date,
                      tod time,
                      at timestamptz,
                      took interval,
                      cc char(3),
                      stamp timestamp,
                      meta jsonb,
                      nick citext,
                      "when" timestamptz NOT NULL DEFAULT now()
                    );
                    """
            )
        assertEquals(
            schemata(
                """
                namespace t

                record T {
                  @sql(key) id:     uuid
                  flag:   bool = true
                  n:      int32(min = 0, max = 100) = 3
                  big:    int64(min = 1)?
                  ratio:  float32
                  exact:  float64(max = 1.5)
                  price:  decimal(10, 2) = 1.50
                  amount: decimal(38, 9)?
                  @sql(type = "smallint") small:  int32
                  @sql(type = "serial") seq:    int32
                  name:   string(max = 64) = "x"
                  label:  string(min = 2, max = 8)
                  blob:   bytes(max = 1024)?
                  code:   string(pattern = "^[a-z]+$")?
                  email:  string(pattern = "^[^@]+@[^@]+$")
                  score:  int32(min = 1, max = 5)
                  below:  int32(max = 9)
                  day:    date?
                  tod:    time?
                  at:     instant?
                  took:   duration?
                  @sql(type = "char(3)") cc:     string(min = 3, max = 3)?
                  @sql(type = "timestamp") stamp:  instant?
                  @sql(type = "jsonb") meta:   string?
                  @sql(type = "citext") nick:   string?
                  when:   instant
                }
                """
            ),
            text(r, "t.schemata"),
        )
        assertEquals(
            listOf(
                "SCH2403 column 'T.amount': numeric without precision imported as decimal(38, 9)",
                "SCH2404 column 'T.small': smallint imported as int32 with @sql(type)",
                "SCH2404 column 'T.seq': serial imported as int32 with @sql(type)",
                "SCH2403 column 'T.seq': serial generation dropped",
                "SCH2404 column 'T.code': ~* imported as pattern; case-insensitivity dropped",
                "SCH2404 column 'T.cc': char(3) imported as string with @sql(type)",
                "SCH2404 column 'T.stamp': timestamp imported as instant with @sql(type)",
                "SCH2404 column 'T.meta': jsonb imported as string with @sql(type)",
                "SCH2404 column 'T.nick': citext imported as string with @sql(type)",
                "SCH2403 column 'T.when': default now() dropped",
            ),
            messages(r),
        )
    }

    @Test
    fun `an identity column keeps its type and drops its generation`() {
        val r =
            importText(
                "t.sql" to
                    """
                    CREATE TABLE t.t (id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY);
                    """
            )
        assertEquals(
            schemata("namespace t\n\nrecord T { @sql(key) id: int64 }"),
            text(r, "t.schemata"),
        )
        assertEquals(listOf("SCH2403 column 'T.id': identity generation dropped"), messages(r))
    }

    @Test
    fun `an enum from an IN check`() {
        val r =
            importText(
                "shop.sql" to
                    """
                    CREATE TABLE shop."order" (
                      id uuid PRIMARY KEY,
                      status text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'Paid-Out'))
                    );
                    """
            )
        assertEquals(
            schemata(
                """
                namespace shop

                record Order {
                  @sql(key) id:     uuid
                  status: Status = pending

                  enum Status { pending, paid_out }
                }
                """
            ),
            text(r, "shop.schemata"),
        )
        assertEquals(
            listOf(
                "SCH2403 column 'Order.status': enum value 'Paid-Out' imported as 'paid_out'; the regenerated check uses it"
            ),
            messages(r),
        )
    }

    @Test
    fun `arrays and json with notes`() {
        val r =
            importText(
                "t.sql" to
                    """
                    CREATE TABLE t.t (
                      id uuid PRIMARY KEY,
                      tags text[] NOT NULL,  -- schemata: list<string(max = 16)>
                      plain integer[],
                      attributes jsonb NOT NULL,  -- schemata: map<string, string>
                      legacy jsonb NOT NULL,  -- schemata: Address
                      blob jsonb
                    );
                    """
            )
        assertEquals(
            schemata(
                """
                namespace t

                record T {
                  @sql(key) id:         uuid
                  tags:       list<string(max = 16)>
                  plain:      list<int32>?
                  attributes: map<string, string>
                  @sql(strategy = json) legacy:     Address
                  @sql(type = "jsonb") blob:       string?

                  record Address {}
                }
                """
            ),
            text(r, "t.schemata"),
        )
        assertEquals(
            listOf(
                "SCH2404 column 'T.blob': jsonb imported as string with @sql(type)",
                "SCH2403 column 'T.legacy': 'Address' is stored as json; its fields are not in the DDL",
            ),
            messages(r),
        )
    }

    @Test
    fun `a typed column with a note`() {
        val r =
            importText(
                "t.sql" to
                    """
                    CREATE TABLE t.t (
                      id uuid PRIMARY KEY,
                      "legacy" varchar(36),  -- schemata: uuid?
                      small smallint NOT NULL  -- schemata: int32
                    );
                    """
            )
        assertEquals(
            schemata(
                """
                namespace t

                record T {
                  @sql(key) id:     uuid
                  @sql(type = "varchar(36)") legacy: uuid?
                  @sql(type = "smallint") small:  int32
                }
                """
            ),
            text(r, "t.schemata"),
        )
        assertEquals(emptyList(), messages(r))
    }

    @Test
    fun `references`() {
        val r =
            importText(
                "corp/refs.sql" to
                    """
                    CREATE SCHEMA refs;
                    CREATE TABLE refs.account (
                      id uuid NOT NULL,
                      owner_tenant_id uuid NOT NULL,
                      owner_code varchar(8) NOT NULL,
                      parent_id uuid,
                      backup_tenant_id uuid,
                      backup_code varchar(8),
                      buyer uuid REFERENCES refs.account (id) ON DELETE SET NULL,
                      payee_id uuid REFERENCES other.payee (id),
                      ledger_id uuid NOT NULL REFERENCES books.ledger (id),
                      CONSTRAINT pk_account PRIMARY KEY (id),
                      CONSTRAINT ck_account_backup_present CHECK ((("backup_tenant_id" IS NULL AND "backup_code" IS NULL) OR ("backup_tenant_id" IS NOT NULL AND "backup_code" IS NOT NULL)))
                    );
                    CREATE TABLE refs.person (
                      tenant_id uuid NOT NULL,
                      code varchar(8) NOT NULL,
                      name text NOT NULL,
                      CONSTRAINT pk_person PRIMARY KEY (tenant_id, code)
                    );
                    ALTER TABLE refs.account ADD CONSTRAINT fk_account_owner FOREIGN KEY (owner_tenant_id, owner_code) REFERENCES refs.person (tenant_id, code);
                    ALTER TABLE refs.account ADD CONSTRAINT fk_account_parent FOREIGN KEY (parent_id) REFERENCES refs.account (id);
                    ALTER TABLE refs.account ADD CONSTRAINT fk_account_backup FOREIGN KEY (backup_tenant_id, backup_code) REFERENCES refs.person (tenant_id, code);
                    """,
                "corp/books.sql" to
                    """
                    CREATE SCHEMA books;
                    CREATE TABLE books.ledger (id uuid PRIMARY KEY);
                    """,
            )
        assertEquals(
            schemata(
                """
                namespace corp.refs

                import corp.books

                record Account {
                  @sql(key) id:       uuid
                  owner:    Person
                  parent:   Account?
                  backup:   Person?
                  @sql(column = "buyer") buyer:    Account?
                  payee_id: uuid?
                  ledger:   corp.books.Ledger
                }

                record Person { @sql(key) tenant_id: uuid @sql(key) code: string(max = 8) name: string }
                """
            ),
            text(r, "corp/refs.schemata"),
        )
        assertEquals(
            listOf(
                "SCH2403 column 'Account.buyer': foreign key column is not named after the field and key; kept as the field name",
                "SCH2403 column 'Account.buyer': ON DELETE SET NULL dropped",
                "SCH2401 column 'Account.payee_id': foreign key references 'other.payee', which is not in the inputs",
            ),
            messages(r),
        )
    }

    @Test
    fun `references in key order are flagged on fields`() {
        val r =
            importText(
                "t.sql" to
                    """
                    CREATE TABLE t.plan (
                      tenant_id uuid NOT NULL,
                      code varchar(8) NOT NULL,
                      PRIMARY KEY (tenant_id, code)
                    );
                    """
            )
        assertEquals(
            schemata(
                "namespace t\n\nrecord Plan { @sql(key) tenant_id: uuid @sql(key) code: string(max = 8) }"
            ),
            text(r, "t.schemata"),
        )
    }

    @Test
    fun `unions`() {
        val r =
            importText(
                "t.sql" to
                    """
                    CREATE TABLE t."order" (
                      "id" uuid NOT NULL,
                      "payment_kind" text NOT NULL,
                      "payment_card_last4" varchar(4),
                      "payment_card_brand" varchar(32),
                      "payment_card_nick" text,
                      "payment_uuid" uuid,
                      "refund_kind" text,
                      "refund_cash_note" text,
                      "refund_uuid" uuid,
                      "ref_kind" text NOT NULL,
                      "ref_source" text,
                      "ref_customer_id" uuid,
                      CONSTRAINT "pk_order" PRIMARY KEY ("id"),
                      CONSTRAINT "ck_order_payment_kind" CHECK ("payment_kind" IN ('card', 'cash', 'uuid')),
                      CONSTRAINT "ck_order_payment_card" CHECK (("payment_kind" <> 'card') OR ("payment_card_last4" IS NOT NULL AND "payment_card_brand" IS NOT NULL)),
                      CONSTRAINT "ck_order_payment_uuid" CHECK (("payment_kind" <> 'uuid') OR ("payment_uuid" IS NOT NULL)),
                      CONSTRAINT "ck_order_refund_kind" CHECK ("refund_kind" IN ('cash', 'uuid')),
                      CONSTRAINT "ck_order_refund_uuid" CHECK (("refund_kind" <> 'uuid') OR ("refund_uuid" IS NOT NULL)),
                      CONSTRAINT "ck_order_ref_kind" CHECK ("ref_kind" IN ('source', 'customer')),
                      CONSTRAINT "ck_order_ref_source_enum" CHECK ("ref_source" IN ('manual', 'imported')),
                      CONSTRAINT "ck_order_ref_source" CHECK (("ref_kind" <> 'source') OR ("ref_source" IS NOT NULL)),
                      CONSTRAINT "ck_order_ref_customer" CHECK (("ref_kind" <> 'customer') OR ("ref_customer_id" IS NOT NULL))
                    );
                    CREATE TABLE t.customer (id uuid PRIMARY KEY);
                    ALTER TABLE t."order" ADD CONSTRAINT "fk_order_ref_customer" FOREIGN KEY ("ref_customer_id") REFERENCES t.customer ("id");
                    COMMENT ON COLUMN t."order"."payment_kind" IS 'How it was paid.';
                    """
            )
        assertEquals(
            schemata(
                """
                namespace t

                record Order {
                  @sql(key) id:      uuid
                  /// How it was paid.
                  payment: Payment
                  refund:  Refund?
                  ref:     Ref

                  union Payment = Card | Cash | uuid

                  record Card { last4: string(max = 4) brand: string(max = 32) nick: string? }

                  record Cash {}

                  union Refund = Cash2 | uuid

                  record Cash2 { note: string? }

                  union Ref = Source | Customer

                  enum Source { manual, imported }
                }

                record Customer { @sql(key) id: uuid }
                """
            ),
            text(r, "t.schemata"),
        )
        assertEquals(
            listOf(
                "SCH2403 column 'Order.refund_kind': member 'cash' imported as 'Cash2', another " +
                    "declaration here being named 'Cash'; the regenerated literal will be 'cash2'"
            ),
            messages(r),
        )
    }

    @Test
    fun `a member record renamed past a taken name is reported`() {
        val r =
            importText(
                "t.sql" to
                    """
                    CREATE TABLE t.t (
                      id uuid PRIMARY KEY,
                      pay_kind text NOT NULL CHECK (pay_kind IN ('cash')),
                      pay_cash_note text,
                      back_kind text CHECK (back_kind IN ('cash')),
                      back_cash_tip integer
                    );
                    """
            )
        assertEquals(
            schemata(
                """
                namespace t

                record T {
                  @sql(key) id:   uuid
                  pay:  Pay
                  back: Back?

                  union Pay = Cash

                  record Cash { note: string? }

                  union Back = Cash2

                  record Cash2 { tip: int32? }
                }
                """
            ),
            text(r, "t.schemata"),
        )
        assertEquals(
            listOf(
                "SCH2403 column 'T.back_kind': member 'cash' imported as 'Cash2', another " +
                    "declaration here being named 'Cash'; the regenerated literal will be 'cash2'"
            ),
            messages(r),
        )
    }

    @Test
    fun `a json note naming another namespace imports it`() {
        val r =
            importText(
                "t.sql" to
                    """
                    CREATE TABLE t.t (
                      id uuid PRIMARY KEY,
                      home jsonb NOT NULL,  -- schemata: other.ns.Address
                      stops jsonb  -- schemata: list<other.ns.Address>
                    );
                    """
            )
        assertEquals(
            schemata(
                """
                namespace t

                import other.ns

                record T {
                  @sql(key) id:    uuid
                  @sql(strategy = json) home:  other.ns.Address
                  @sql(strategy = json) stops: list<other.ns.Address>?
                }
                """
            ),
            text(r, "t.schemata"),
        )
        assertEquals(emptyList(), messages(r))
    }

    @Test
    fun `an enum column named like a kind with no member columns stays an enum`() {
        val r =
            importText(
                "t.sql" to
                    """
                    CREATE TABLE t.t (
                      id uuid PRIMARY KEY,
                      fulfilment_kind text NOT NULL CHECK (fulfilment_kind IN ('pickup', 'delivery'))
                    );
                    """
            )
        assertEquals(
            schemata(
                """
                namespace t

                record T {
                  @sql(key) id:              uuid
                  fulfilment_kind: FulfilmentKind

                  enum FulfilmentKind { pickup, delivery }
                }
                """
            ),
            text(r, "t.schemata"),
        )
    }

    @Test
    fun `embedded records`() {
        val r =
            importText(
                "t.sql" to
                    """
                    CREATE TABLE t.site (
                      "id" uuid NOT NULL,
                      "office_street" varchar(200) NOT NULL,
                      "office_zip" text NOT NULL,
                      "home_street" varchar(200),
                      "home_zip" text,
                      "away_street" text,
                      "away_zip" text,
                      CONSTRAINT "pk_site" PRIMARY KEY ("id"),
                      CONSTRAINT "ck_site_home_zip_min" CHECK (char_length("home_zip") >= 5),
                      CONSTRAINT "ck_site_home_present" CHECK ((("home_street" IS NULL AND "home_zip" IS NULL) OR ("home_street" IS NOT NULL AND "home_zip" IS NOT NULL)))
                    );
                    COMMENT ON COLUMN t.site.home_zip IS 'Postal code.';
                    """
            )
        assertEquals(
            schemata(
                """
                namespace t

                record Site {
                  @sql(key) id:            uuid
                  office_street: string(max = 200)
                  office_zip:    string
                  home:          Home?
                  away_street:   string?
                  away_zip:      string?

                  record Home {
                    street: string(max = 200)
                    /// Postal code.
                    zip:    string(min = 5)
                  }
                }
                """
            ),
            text(r, "t.schemata"),
        )
        assertEquals(emptyList(), messages(r))
    }

    @Test
    fun `child tables`() {
        val r =
            importText(
                "t.sql" to
                    """
                    CREATE TABLE t."order" (
                      "id" uuid NOT NULL,
                      CONSTRAINT "pk_order" PRIMARY KEY ("id")
                    );
                    CREATE TABLE t."order_lines" (
                      "order_id" uuid NOT NULL,
                      "position" integer NOT NULL,
                      "sku" varchar(64) NOT NULL,
                      "quantity" integer NOT NULL,
                      CONSTRAINT "pk_order_lines" PRIMARY KEY ("order_id", "position"),
                      CONSTRAINT "ck_order_lines_quantity_min" CHECK ("quantity" >= 1)
                    );
                    CREATE TABLE t."order_lines_notes" (
                      "order_lines_order_id" uuid NOT NULL,
                      "order_lines_position" integer NOT NULL,
                      "position" integer NOT NULL,
                      "value" text NOT NULL,
                      CONSTRAINT "pk_order_lines_notes" PRIMARY KEY ("order_lines_order_id", "order_lines_position", "position")
                    );
                    CREATE TABLE t."order_tags" (
                      "order_id" uuid NOT NULL,
                      "position" integer NOT NULL,
                      "value" varchar(16),
                      CONSTRAINT "pk_order_tags" PRIMARY KEY ("order_id", "position")
                    );
                    CREATE TABLE t."order_prices" (
                      "order_id" uuid NOT NULL,
                      "key" text NOT NULL,
                      "value" numeric(10, 2) NOT NULL,
                      CONSTRAINT "pk_order_prices" PRIMARY KEY ("order_id", "key")
                    );
                    CREATE TABLE t."order_items" (
                      "order_id" uuid NOT NULL,
                      "position" integer NOT NULL,
                      "value_id" uuid NOT NULL,
                      CONSTRAINT "pk_order_items" PRIMARY KEY ("order_id", "position")
                    );
                    CREATE TABLE t."order_stops" (
                      "order_id" uuid NOT NULL,
                      "key" integer NOT NULL,
                      "value_street" text,
                      "value_zip" text,
                      CONSTRAINT "pk_order_stops" PRIMARY KEY ("order_id", "key"),
                      CONSTRAINT "ck_order_stops_value_present" CHECK ((("value_street" IS NULL AND "value_zip" IS NULL) OR ("value_street" IS NOT NULL AND "value_zip" IS NOT NULL)))
                    );
                    CREATE TABLE t."item" ("id" uuid NOT NULL, CONSTRAINT "pk_item" PRIMARY KEY ("id"));
                    ALTER TABLE t."order_lines" ADD CONSTRAINT "fk_order_lines_order" FOREIGN KEY ("order_id") REFERENCES t."order" ("id") ON DELETE CASCADE;
                    ALTER TABLE t."order_lines_notes" ADD CONSTRAINT "fk_order_lines_notes_order_lines" FOREIGN KEY ("order_lines_order_id", "order_lines_position") REFERENCES t."order_lines" ("order_id", "position") ON DELETE CASCADE;
                    ALTER TABLE t."order_tags" ADD CONSTRAINT "fk_order_tags_order" FOREIGN KEY ("order_id") REFERENCES t."order" ("id") ON DELETE CASCADE;
                    ALTER TABLE t."order_prices" ADD CONSTRAINT "fk_order_prices_order" FOREIGN KEY ("order_id") REFERENCES t."order" ("id") ON DELETE CASCADE;
                    ALTER TABLE t."order_items" ADD CONSTRAINT "fk_order_items_order" FOREIGN KEY ("order_id") REFERENCES t."order" ("id") ON DELETE CASCADE;
                    ALTER TABLE t."order_items" ADD CONSTRAINT "fk_order_items_value" FOREIGN KEY ("value_id") REFERENCES t."item" ("id");
                    ALTER TABLE t."order_stops" ADD CONSTRAINT "fk_order_stops_order" FOREIGN KEY ("order_id") REFERENCES t."order" ("id") ON DELETE CASCADE;
                    COMMENT ON TABLE t."order_lines" IS 'One purchasable item.';
                    """
            )
        assertEquals(
            schemata(
                """
                namespace t

                record Order {
                  @sql(key) id:     uuid
                  lines:  list<Line>
                  @sql(strategy = table) tags:   list<string(max = 16)?>
                  @sql(strategy = table) prices: map<string, decimal(10, 2)>
                  items:  list<Item>
                  @sql(strategy = table) stops:  map<int32, Stop?>

                  /// One purchasable item.
                  record Line {
                    sku:      string(max = 64)
                    quantity: int32(min = 1)
                    @sql(strategy = table) notes:    list<string>
                  }

                  record Stop { street: string zip: string }
                }

                record Item { @sql(key) id: uuid }
                """
            ),
            text(r, "t.schemata"),
        )
        assertEquals(emptyList(), messages(r))
    }

    @Test
    fun `child table look-alikes stay records`() {
        val r =
            importText(
                "t.sql" to
                    """
                    CREATE TABLE t."order" ("id" uuid PRIMARY KEY);
                    CREATE TABLE t."order_lines" (
                      "order_id" uuid NOT NULL REFERENCES t."order" ("id"),
                      "position" integer NOT NULL,
                      PRIMARY KEY ("order_id", "position")
                    );
                    CREATE TABLE t."order_notes" (
                      "order_id" uuid NOT NULL,
                      "position" integer NOT NULL,
                      "owner_id" uuid NOT NULL REFERENCES t."order" ("id") ON DELETE CASCADE,
                      PRIMARY KEY ("order_id", "position")
                    );
                    """
            )
        assertEquals(
            schemata(
                """
                namespace t

                record Order { @sql(key) id: uuid }

                record OrderLines { order: Order position: int32 }

                record OrderNotes { @sql(key) order_id: uuid @sql(key) position: int32 owner: Order }
                """
            ),
            text(r, "t.schemata"),
        )
        assertEquals(
            listOf(
                "SCH2403 table 'order_lines': primary key over a reference column; add @sql(key) by hand",
                "SCH2403 column 'OrderNotes.owner_id': ON DELETE CASCADE dropped",
            ),
            messages(r),
        )
    }

    @Test
    fun `keys unique and index`() {
        val r =
            importText(
                "t.sql" to
                    """
                    CREATE TABLE t.plan (
                      tenant_id uuid NOT NULL,
                      code varchar(8) NOT NULL,
                      email text NOT NULL UNIQUE,
                      created timestamptz NOT NULL,
                      a integer NOT NULL,
                      b integer NOT NULL,
                      pay_kind text NOT NULL CHECK (pay_kind IN ('uuid')),
                      pay_uuid uuid,
                      PRIMARY KEY (code, tenant_id),
                      UNIQUE (a, b),
                      UNIQUE (code, tenant_id),
                      CHECK ((pay_kind <> 'uuid') OR (pay_uuid IS NOT NULL))
                    );
                    CREATE INDEX ix_plan_created ON t.plan (created);
                    CREATE INDEX ix_plan_pay ON t.plan (pay_kind, pay_uuid);
                    CREATE INDEX ON t.plan (a) WHERE a > 0;
                    CREATE INDEX ON t.plan USING gin (b);
                    CREATE INDEX ON t.plan ((lower(email)));
                    CREATE TABLE t.loose (a integer);
                    """
            )
        assertEquals(
            schemata(
                """
                namespace t

                @sql(key = (code, tenant_id))
                record Plan {
                  tenant_id: uuid
                  code:      string(max = 8)
                  @sql(unique) email:     string
                  @sql(index) created:   instant
                  a:         int32
                  b:         int32
                  @sql(index) pay:       Pay

                  union Pay = uuid
                }

                record Loose { a: int32? }
                """
            ),
            text(r, "t.schemata"),
        )
        assertEquals(
            listOf(
                "SCH2405 t.sql: CREATE INDEX on an expression dropped",
                "SCH2405 table 'plan': unique constraint over (a, b) dropped; Schemata keys one field",
                "SCH2405 table 'plan': partial index over (a) dropped",
                "SCH2405 table 'plan': index using gin over (b) dropped",
                "SCH2403 table 'loose': no primary key; add @sql(key) before compiling to SQL",
            ),
            messages(r),
        )
    }

    @Test
    fun `comments`() {
        val r =
            importText(
                "t.sql" to
                    """
                    CREATE TABLE t."order" (
                      "id" uuid NOT NULL,
                      "pay_kind" text NOT NULL,
                      "pay_uuid" uuid,
                      CONSTRAINT "pk_order" PRIMARY KEY ("id"),
                      CONSTRAINT "ck_order_pay_kind" CHECK ("pay_kind" IN ('uuid')),
                      CONSTRAINT "ck_order_pay_uuid" CHECK (("pay_kind" <> 'uuid') OR ("pay_uuid" IS NOT NULL))
                    );
                    CREATE TABLE t."order_lines" (
                      "order_id" uuid NOT NULL,
                      "position" integer NOT NULL,
                      "sku" text NOT NULL,
                      CONSTRAINT "pk_order_lines" PRIMARY KEY ("order_id", "position")
                    );
                    ALTER TABLE t."order_lines" ADD CONSTRAINT "fk_order_lines_order" FOREIGN KEY ("order_id") REFERENCES t."order" ("id") ON DELETE CASCADE;
                    COMMENT ON TABLE t."order" IS 'An order.
                    Two lines.';
                    COMMENT ON COLUMN t."order"."id" IS 'The id.';
                    COMMENT ON COLUMN t."order"."pay_kind" IS 'How it is paid.';
                    COMMENT ON TABLE t."order_lines" IS 'A line.';
                    COMMENT ON COLUMN t."order_lines"."sku" IS 'The SKU.';
                    """
            )
        assertEquals(
            schemata(
                """
                namespace t

                /// An order.
                /// Two lines.
                record Order {
                  /// The id.
                  @sql(key) id:    uuid
                  /// How it is paid.
                  pay:   Pay
                  lines: list<Line>

                  union Pay = uuid

                  /// A line.
                  record Line {
                    /// The SKU.
                    sku: string
                  }
                }
                """
            ),
            text(r, "t.schemata"),
        )
    }

    @Test
    fun `names schema and namespace`() {
        val r =
            importText(
                "naming.sql" to
                    """
                    CREATE SCHEMA shop;
                    CREATE TABLE shop."HTTPStatus" ("code" integer PRIMARY KEY, "orderId" uuid NOT NULL);
                    """,
                "ledger/journal.sql" to
                    """
                    CREATE SCHEMA ledger_journal;
                    CREATE TABLE ledger_journal.entry (id uuid PRIMARY KEY);
                    """,
            )
        assertEquals(
            schemata(
                """
                @sql(schema = "shop")
                namespace naming

                @sql(table = "HTTPStatus")
                record HTTPStatus { @sql(key) code: int32 @sql(column = "orderId") order_id: uuid }
                """
            ),
            text(r, "naming.schemata"),
        )
        assertEquals(
            schemata(
                """
                @sql(schema = "ledger_journal")
                namespace ledger.journal

                record Entry { @sql(key) id: uuid }
                """
            ),
            text(r, "ledger/journal.schemata"),
        )
        assertEquals(emptyList(), messages(r))
    }

    @Test
    fun `a lone file takes its schema or its stem`() {
        val r =
            importLone(
                "inventory.sql" to "CREATE TABLE inventory.item (id uuid PRIMARY KEY);",
                "misc.sql" to "CREATE TABLE thing (id uuid PRIMARY KEY);",
                "two.sql" to
                    """
                    CREATE TABLE alpha.a (id uuid PRIMARY KEY);
                    CREATE TABLE beta.b (id uuid PRIMARY KEY, a_id uuid NOT NULL REFERENCES alpha.a (id));
                    """,
            )
        assertEquals(
            schemata("namespace inventory\n\nrecord Item { @sql(key) id: uuid }"),
            text(r, "inventory.schemata"),
        )
        assertEquals(
            schemata(
                "@sql(schema = \"public\")\nnamespace misc\n\nrecord Thing { @sql(key) id: uuid }"
            ),
            text(r, "misc.schemata"),
        )
        assertEquals(
            schemata("namespace alpha\n\nrecord A { @sql(key) id: uuid }"),
            text(r, "alpha.schemata"),
        )
        assertEquals(
            schemata(
                "namespace beta\n\nimport alpha\n\nrecord B { @sql(key) id: uuid a: alpha.A }"
            ),
            text(r, "beta.schemata"),
        )
        assertEquals(
            listOf("SCH2402 misc.sql: namespace 'misc' was derived from the file name"),
            messages(r),
        )
    }

    @Test
    fun `dropped and ignored statements`() {
        val r =
            importText(
                "x.sql" to
                    """
                    SET search_path = x;
                    CREATE TYPE mood AS ENUM ('sad', 'ok');
                    CREATE SEQUENCE s;
                    CREATE TABLE x.t (
                      id uuid PRIMARY KEY,
                      c jsonb NOT NULL CHECK ("c" @> '{}'),
                      f uuid REFERENCES x.t (id) ON DELETE SET NULL ON UPDATE CASCADE DEFERRABLE,
                      d integer GENERATED ALWAYS AS (1) STORED
                    ) INHERITS (base);
                    ALTER TABLE x.t OWNER TO app;
                    GRANT SELECT ON x.t TO app;
                    CREATE VIEW x.v AS SELECT 1;
                    CREATE FUNCTION x.f() RETURNS integer AS $$ SELECT 1 $$ LANGUAGE sql;
                    CREATE TABLE broken (;
                    """
            )
        assertEquals(
            schemata(
                """
                namespace x

                record T { @sql(key) id: uuid @sql(type = "jsonb") c: string @sql(column = "f") f: T? d: int32? }
                """
            ),
            text(r, "x.schemata"),
        )
        assertEquals(
            listOf(
                "SCH2401 x.sql:14:22: cannot parse: expected a column name",
                "SCH2405 x.sql: CREATE TYPE dropped",
                "SCH2405 x.sql: CREATE VIEW dropped",
                "SCH2405 x.sql: CREATE FUNCTION dropped",
                "SCH2405 column 'T.d': GENERATED … STORED dropped; imported as a plain column",
                "SCH2405 table 't': INHERITS dropped",
                "SCH2404 column 'T.c': jsonb imported as string with @sql(type)",
                "SCH2403 column 'T.f': foreign key column is not named after the field and key; kept as the field name",
                "SCH2403 column 'T.f': ON DELETE SET NULL dropped",
                "SCH2403 column 'T.f': ON UPDATE CASCADE dropped",
                "SCH2403 column 'T.f': DEFERRABLE dropped",
                "SCH2405 table 't': check constraint dropped: (\"c\" @> '{}')",
            ),
            messages(r),
        )
    }

    @Test
    fun `the review focus dump`() {
        val hand = importText("shop.sql" to HAND)
        val dump = importText("shop.sql" to DUMP)
        assertEquals(
            schemata(
                """
                namespace shop

                record Customers { @sql(key) id: uuid name: string }

                /// Orders.
                record Orders {
                  @sql(key) id:       uuid
                  @sql(index) status:   Status = pending
                  @sql(type = "smallint") qty:      int32(min = 1)
                  /// A note.
                  @sql(unique) note:     string(max = 500)?
                  customer: Customers
                  @sql(type = "serial") seq:      int32
                  n:        int32

                  enum Status { pending, paid }
                }
                """
            ),
            text(hand, "shop.schemata"),
        )
        assertEquals(text(hand, "shop.schemata"), text(dump, "shop.schemata"))
    }
}
