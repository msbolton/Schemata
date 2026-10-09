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
                schema t

                model T {
                  id     uuid            { id }
                  flag   bool            = true
                  n      int32           { min 0, max 100 } = 3
                  big    int64?          { min 1 }
                  ratio  float32
                  exact  float64         { max 1.5 }
                  price  decimal(10, 2)  = 1.50
                  amount decimal(38, 9)?
                  small  int32           @sql(type: "smallint")
                  seq    int32           @sql(type: "serial")
                  name   string          { max 64 } = "x"
                  label  string          { min 2, max 8 }
                  blob   bytes?          { max 1024 }
                  code   string?         { match "^[a-z]+$" }
                  email  string          { match "^[^@]+@[^@]+$" }
                  score  int32           { min 1, max 5 }
                  below  int32           { max 9 }
                  day    date?
                  tod    time?
                  at     instant?
                  took   duration?
                  cc     string?         { min 3, max 3 } @sql(type: "char(3)")
                  stamp  instant?        @sql(type: "timestamp")
                  meta   string?         @sql(type: "jsonb")
                  nick   string?         @sql(type: "citext")
                  when   instant
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
        assertEquals(schemata("schema t\n\nmodel T { id int64 { id } }"), text(r, "t.schemata"))
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
                schema shop

                model Order {
                  id     uuid   { id }
                  status Status = pending

                  enum Status { pending paid_out }
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
                      tags text[] NOT NULL,  -- schemata: string[] { max 16 }
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
                schema t

                model T {
                  id         uuid                { id }
                  tags       string[]            { max 16 }
                  plain      int32[]?
                  attributes map<string, string>
                  legacy     Address             @sql(strategy: json)
                  blob       string?             @sql(type: "jsonb")

                  model Address {}
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
                schema t

                model T {
                  id     uuid  { id }
                  legacy uuid? @sql(type: "varchar(36)")
                  small  int32 @sql(type: "smallint")
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
                schema corp.refs

                import corp.books

                model Account {
                  id     uuid              { id }
                  owner  Person
                  parent Account?
                  backup Person?
                  buyer  Account?          @relation(onDelete: set_null) @sql(column: "buyer")
                  ledger corp.books.Ledger
                }

                model Person { tenant_id uuid { id }  code string { id, max 8 }  name string }
                """
            ),
            text(r, "corp/refs.schemata"),
        )
        assertEquals(
            listOf(
                "SCH2403 column 'Account.buyer': foreign key column is not named after the field and key; kept as the field name"
            ),
            messages(r),
        )
    }

    @Test
    fun `a foreign key to a table not in the inputs is an error and nothing is emitted`() {
        val r =
            importText(
                "refs.sql" to
                    """
                    CREATE TABLE refs.account (
                      id uuid PRIMARY KEY,
                      payee_id uuid REFERENCES other.payee (id)
                    );
                    """
            )
        assertEquals(
            listOf(
                "SCH2401 column 'Account.payee_id': foreign key references 'other.payee', which is not in the inputs"
            ),
            messages(r),
        )
        assertEquals(emptyList(), r.files)
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
                "schema t\n" +
                    "\n" +
                    "model Plan { tenant_id uuid { id }  code string { id, max 8 } }"
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
                schema t

                model Order {
                  id      uuid    { id }
                  /// How it was paid.
                  payment Payment
                  refund  Refund?
                  ref     Ref

                  union Payment = Card | Cash | uuid

                  model Card { last4 string { max 4 }  brand string { max 32 }  nick string? }

                  model Cash {}

                  union Refund = Cash2 | uuid

                  model Cash2 { note string? }

                  union Ref = Source | Customer

                  enum Source { manual imported }
                }

                model Customer { id uuid { id } }
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
                schema t

                model T {
                  id   uuid  { id }
                  pay  Pay
                  back Back?

                  union Pay = Cash

                  model Cash { note string? }

                  union Back = Cash2

                  model Cash2 { tip int32? }
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
                      stops jsonb  -- schemata: other.ns.Address[]
                    );
                    """
            )
        assertEquals(
            schemata(
                """
                schema t

                import other.ns

                model T {
                  id    uuid                { id }
                  home  other.ns.Address    @sql(strategy: json)
                  stops other.ns.Address[]? @sql(strategy: json)
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
                schema t

                model T {
                  id              uuid           { id }
                  fulfilment_kind FulfilmentKind

                  enum FulfilmentKind { pickup delivery }
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
                schema t

                model Site {
                  id            uuid    { id }
                  office_street string  { max 200 }
                  office_zip    string
                  home          Home?
                  away_street   string?
                  away_zip      string?

                  model Home {
                    street string { max 200 }
                    /// Postal code.
                    zip    string { min 5 }
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
                schema t

                model Order {
                  id     uuid                        { id }
                  lines  Line[]
                  tags   string?[]                   { max 16 } @sql(strategy: table)
                  prices map<string, decimal(10, 2)> @sql(strategy: table)
                  items  Item[]
                  stops  map<int32, Stop?>           @sql(strategy: table)

                  /// One purchasable item.
                  model Line {
                    sku      string   { max 64 }
                    quantity int32    { min 1 }
                    notes    string[] @sql(strategy: table)
                  }

                  model Stop { street string  zip string }
                }

                model Item { id uuid { id } }
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
                schema t

                model Order { id uuid { id } }

                model OrderLines { order_id uuid { id }  position int32 { id } }

                model OrderNotes {
                  order_id uuid  { id }
                  position int32 { id }
                  owner    Order @relation(onDelete: cascade)
                }
                """
            ),
            text(r, "t.schemata"),
        )
        assertEquals(
            listOf(
                "SCH2405 table 'order_lines': foreign key over (order_id) dropped; a key field cannot be a reference"
            ),
            messages(r),
        )
    }

    @Test
    fun `on delete reads back as the reference's relation`() {
        val r =
            importText(
                "t.sql" to
                    """
                    CREATE TABLE t.customer (id uuid PRIMARY KEY);
                    CREATE TABLE t.membership (
                      id uuid PRIMARY KEY,
                      owner_id uuid NOT NULL REFERENCES t.customer (id) ON DELETE CASCADE,
                      sponsor_id uuid REFERENCES t.customer (id) ON DELETE SET NULL,
                      payer_id uuid NOT NULL REFERENCES t.customer (id) ON DELETE SET NULL,
                      agent_id uuid NOT NULL REFERENCES t.customer (id) ON DELETE RESTRICT,
                      clerk_id uuid NOT NULL REFERENCES t.customer (id) ON DELETE NO ACTION,
                      code text NOT NULL,
                      UNIQUE (owner_id, code)
                    );
                    CREATE TABLE t.membership_guests (
                      membership_id uuid NOT NULL REFERENCES t.membership (id) ON DELETE CASCADE,
                      position integer NOT NULL,
                      value_id uuid REFERENCES t.customer (id) ON DELETE SET NULL,
                      PRIMARY KEY (membership_id, position)
                    );
                    CREATE INDEX ON t.membership (code, sponsor_id);
                    """
            )
        assertEquals(
            schemata(
                """
                schema t

                model Customer { id uuid { id } }

                model Membership {
                  id      uuid        { id }
                  owner   Customer    @relation(onDelete: cascade)
                  sponsor Customer?   @relation(onDelete: set_null)
                  payer   Customer
                  agent   Customer
                  clerk   Customer
                  code    string
                  guests  Customer?[] @relation(onDelete: set_null)

                  @@unique(owner, code)
                  @@index(code, sponsor)
                }
                """
            ),
            text(r, "t.schemata"),
        )
        assertEquals(
            listOf(
                "SCH2403 column 'Membership.payer_id': ON DELETE SET NULL dropped; the reference cannot be null"
            ),
            messages(r),
        )
    }

    @Test
    fun `a join table keeps its key columns as plain key fields`() {
        val r =
            importText(
                "t.sql" to
                    """
                    CREATE TABLE t.orders (id uuid PRIMARY KEY);
                    CREATE TABLE t.products (id uuid PRIMARY KEY);
                    CREATE TABLE t.order_products (
                      order_id uuid REFERENCES t.orders (id) ON DELETE CASCADE,
                      product_id uuid REFERENCES t.products (id),
                      qty integer NOT NULL,
                      PRIMARY KEY (order_id, product_id)
                    );
                    """
            )
        assertEquals(
            schemata(
                """
                schema t

                model Orders { id uuid { id } }

                model Products { id uuid { id } }

                model OrderProducts { order_id uuid { id }  product_id uuid { id }  qty int32 }
                """
            ),
            text(r, "t.schemata"),
        )
        assertEquals(
            listOf(
                "SCH2405 table 'order_products': foreign key over (order_id) dropped; a key field cannot be a reference",
                "SCH2405 table 'order_products': foreign key over (product_id) dropped; a key field cannot be a reference",
            ),
            messages(r),
        )
    }

    @Test
    fun `a key that is also a reference stays a plain key field`() {
        val r =
            importText(
                "t.sql" to
                    """
                    CREATE TABLE t.customer (id uuid PRIMARY KEY);
                    CREATE TABLE t.profile (
                      customer_id uuid PRIMARY KEY REFERENCES t.customer (id),
                      bio text
                    );
                    CREATE TABLE t.badge (
                      tenant_id uuid NOT NULL,
                      code text NOT NULL,
                      owner_tenant_id uuid NOT NULL,
                      PRIMARY KEY (tenant_id, code)
                    );
                    CREATE TABLE t.award (
                      badge_tenant_id uuid NOT NULL,
                      badge_code text NOT NULL,
                      PRIMARY KEY (badge_code),
                      FOREIGN KEY (badge_tenant_id, badge_code) REFERENCES t.badge (tenant_id, code)
                    );
                    """
            )
        assertEquals(
            schemata(
                """
                schema t

                model Customer { id uuid { id } }

                model Profile { customer_id uuid { id }  bio string? }

                model Badge { tenant_id uuid { id }  code string { id }  owner_tenant_id uuid }

                model Award { badge_tenant_id uuid  badge_code string { id } }
                """
            ),
            text(r, "t.schemata"),
        )
        assertEquals(
            listOf(
                "SCH2405 table 'profile': foreign key over (customer_id) dropped; a key field cannot be a reference",
                "SCH2405 table 'award': foreign key over (badge_tenant_id, badge_code) dropped; a key field cannot be a reference",
            ),
            messages(r),
        )
    }

    @Test
    fun `a key column is never part of an embedded record`() {
        val r =
            importText(
                "t.sql" to
                    """
                    CREATE TABLE t.spot (
                      pos_x integer,
                      pos_y integer,
                      PRIMARY KEY (pos_x),
                      CHECK ((pos_x IS NULL AND pos_y IS NULL) OR (pos_x IS NOT NULL AND pos_y IS NOT NULL))
                    );
                    """
            )
        assertEquals(
            schemata("schema t\n\nmodel Spot { pos_x int32 { id }  pos_y int32? }"),
            text(r, "t.schemata"),
        )
        assertEquals(
            listOf(
                "SCH2405 table 'spot': check constraint dropped: ((pos_x is null and pos_y is null) or (pos_x is not null and pos_y is not null))"
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
                    CREATE INDEX ON t.plan (b, a);
                    CREATE UNIQUE INDEX ON t.plan (created, email);
                    CREATE INDEX ON t.plan (a, pay_kind);
                    CREATE TABLE t.loose (a integer);
                    """
            )
        assertEquals(
            schemata(
                """
                schema t

                model Plan {
                  tenant_id uuid
                  code      string  { max 8 }
                  email     string  { unique }
                  created   instant { index }
                  a         int32
                  b         int32
                  pay       Pay     { index }

                  union Pay = uuid

                  @@id(code, tenant_id)
                  @@unique(a, b)
                  @@unique(created, email)
                  @@index(b, a)
                }

                model Loose { a int32? }
                """
            ),
            text(r, "t.schemata"),
        )
        assertEquals(
            listOf(
                "SCH2405 t.sql: CREATE INDEX on an expression dropped",
                "SCH2405 table 'plan': partial index over (a) dropped",
                "SCH2405 table 'plan': index using gin over (b) dropped",
                "SCH2405 table 'plan': index over (a, pay_kind) dropped; no fields hold exactly its columns",
                "SCH2403 table 'loose': no primary key; add { id } to a field before compiling to SQL",
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
                schema t

                /// An order.
                /// Two lines.
                model Order {
                  /// The id.
                  id    uuid   { id }
                  /// How it is paid.
                  pay   Pay
                  lines Line[]

                  union Pay = uuid

                  /// A line.
                  model Line {
                    /// The SKU.
                    sku string
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
                schema naming @sql(schema: "shop")

                model HTTPStatus {
                  code     int32 { id }
                  order_id uuid  @sql(column: "orderId")

                  @@sql(table: "HTTPStatus")
                }
                """
            ),
            text(r, "naming.schemata"),
        )
        assertEquals(
            schemata(
                """
                schema ledger.journal @sql(schema: "ledger_journal")

                model Entry { id uuid { id } }
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
            schemata("schema inventory\n\nmodel Item { id uuid { id } }"),
            text(r, "inventory.schemata"),
        )
        assertEquals(
            schemata("schema misc @sql(schema: \"public\")\n\nmodel Thing { id uuid { id } }"),
            text(r, "misc.schemata"),
        )
        assertEquals(
            schemata("schema alpha\n\nmodel A { id uuid { id } }"),
            text(r, "alpha.schemata"),
        )
        assertEquals(
            schemata("schema beta\n\nimport alpha\n\nmodel B { id uuid { id }  a alpha.A }"),
            text(r, "beta.schemata"),
        )
        assertEquals(
            listOf("SCH2402 misc.sql: schema name 'misc' was derived from the file name"),
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
                      f uuid REFERENCES x.t (id) ON DELETE SET DEFAULT ON UPDATE CASCADE DEFERRABLE,
                      d integer GENERATED ALWAYS AS (1) STORED
                    ) INHERITS (base);
                    ALTER TABLE x.t OWNER TO app;
                    GRANT SELECT ON x.t TO app;
                    CREATE VIEW x.v AS SELECT 1;
                    CREATE FUNCTION x.f() RETURNS integer AS $$ SELECT 1 $$ LANGUAGE sql;
                    """
            )
        assertEquals(
            schemata(
                """
                schema x

                model T { id uuid { id }  c string @sql(type: "jsonb")  f T? @sql(column: "f")  d int32? }
                """
            ),
            text(r, "x.schemata"),
        )
        assertEquals(
            listOf(
                "SCH2405 x.sql: CREATE TYPE dropped",
                "SCH2405 x.sql: CREATE VIEW dropped",
                "SCH2405 x.sql: CREATE FUNCTION dropped",
                "SCH2405 column 'T.d': GENERATED … STORED dropped; imported as a plain column",
                "SCH2405 table 't': INHERITS dropped",
                "SCH2404 column 'T.c': jsonb imported as string with @sql(type)",
                "SCH2403 column 'T.f': foreign key column is not named after the field and key; kept as the field name",
                "SCH2403 column 'T.f': ON DELETE SET DEFAULT dropped",
                "SCH2403 column 'T.f': ON UPDATE CASCADE dropped",
                "SCH2403 column 'T.f': DEFERRABLE dropped",
                "SCH2405 table 't': check constraint dropped: (\"c\" @> '{}')",
            ),
            messages(r),
        )
    }

    @Test
    fun `a statement that does not parse is an error and nothing is emitted`() {
        val r =
            importText("x.sql" to "CREATE TABLE x.t (id uuid PRIMARY KEY);\nCREATE TABLE broken (;")
        assertEquals(
            listOf("SCH2401 x.sql:2:22: cannot parse: expected a column name"),
            messages(r),
        )
        assertEquals(emptyList(), r.files)
    }

    @Test
    fun `a hand written schema and its dump import alike`() {
        val hand = importText("shop.sql" to HAND)
        val dump = importText("shop.sql" to DUMP)
        assertEquals(
            schemata(
                """
                schema shop

                model Customers { id uuid { id }  name string }

                /// Orders.
                model Orders {
                  id       uuid      { id }
                  status   Status    { index } = pending
                  qty      int32     { min 1 } @sql(type: "smallint")
                  /// A note.
                  note     string?   { unique, max 500 }
                  customer Customers
                  seq      int32     @sql(type: "serial")
                  n        int32

                  enum Status { pending paid }
                }
                """
            ),
            text(hand, "shop.schemata"),
        )
        assertEquals(text(hand, "shop.schemata"), text(dump, "shop.schemata"))
    }

    @Test
    fun `a composite reference listed in another order than the key warns and follows the key`() {
        val r =
            importText(
                "t.sql" to
                    """
                    CREATE TABLE t.person (
                      tenant_id uuid NOT NULL,
                      code varchar(8) NOT NULL,
                      PRIMARY KEY (tenant_id, code)
                    );
                    CREATE TABLE t.account (
                      id uuid PRIMARY KEY,
                      owner_code varchar(8) NOT NULL,
                      owner_tenant_id uuid NOT NULL,
                      FOREIGN KEY (owner_code, owner_tenant_id) REFERENCES t.person (code, tenant_id)
                    );
                    """
            )
        assertEquals(
            schemata(
                """
                schema t

                model Person { tenant_id uuid { id }  code string { id, max 8 } }

                model Account { id uuid { id }  owner Person }
                """
            ),
            text(r, "t.schemata"),
        )
        assertEquals(
            listOf(
                "SCH2403 column 'Account.owner_code': foreign key columns (owner_code, owner_tenant_id) are listed in another order than the key (tenant_id, code) of 'Person'; imported in the key's order"
            ),
            messages(r),
        )
    }

    @Test
    fun `statements naming a table that is not in the inputs are reported`() {
        val r =
            importText(
                "t.sql" to
                    """
                    CREATE TABLE t.a (id uuid PRIMARY KEY);
                    ALTER TABLE t.ghost ADD CONSTRAINT pk_ghost PRIMARY KEY (id);
                    CREATE INDEX ix_ghost ON t.ghost (id);
                    COMMENT ON TABLE t.ghost IS 'Gone.';
                    COMMENT ON COLUMN t.ghost.id IS 'Gone.';
                    """
            )
        assertEquals(schemata("schema t\n\nmodel A { id uuid { id } }"), text(r, "t.schemata"))
        assertEquals(
            listOf(
                "SCH2405 t.sql: ALTER TABLE on 't.ghost' dropped; the table is not in the inputs",
                "SCH2405 t.sql: CREATE INDEX on 't.ghost' dropped; the table is not in the inputs",
                "SCH2405 t.sql: COMMENT ON TABLE on 't.ghost' dropped; the table is not in the inputs",
                "SCH2405 t.sql: COMMENT ON COLUMN on 't.ghost' dropped; the table is not in the inputs",
            ),
            messages(r),
        )
    }

    @Test
    fun `an unqualified name that finds no table is reported with its default schema`() {
        val r =
            importText(
                "t.sql" to
                    """
                    CREATE TABLE t.a (id uuid PRIMARY KEY);
                    ALTER TABLE ghost ADD PRIMARY KEY (id);
                    """
            )
        assertEquals(
            listOf(
                "SCH2405 t.sql: ALTER TABLE on 'public.ghost' dropped; the table is not in the inputs"
            ),
            messages(r),
        )
    }

    @Test
    fun `a default set after the table is created is reported`() {
        val r =
            importText(
                "t.sql" to
                    """
                    CREATE TABLE t.a (id uuid PRIMARY KEY, n integer);
                    ALTER TABLE t.a ALTER COLUMN n SET DEFAULT 5;
                    """
            )
        assertEquals(listOf("SCH2405 t.sql: ALTER COLUMN SET DEFAULT dropped"), messages(r))
    }

    @Test
    fun `a name option with two schemas in one file names the first and derives the rest`() {
        val r =
            SqlImporter.import(
                listOf(
                    ImportInput(
                        "two.sql",
                        """
                        CREATE SCHEMA first_s;
                        CREATE SCHEMA second_s;
                        CREATE TABLE first_s.a (id uuid PRIMARY KEY);
                        CREATE TABLE second_s.b (id uuid PRIMARY KEY);
                        """
                            .trimIndent(),
                    )
                ),
                "chosen",
            )
        assertEquals(
            listOf("chosen.schemata", "second_s.schemata"),
            r.files.map { it.path }.sorted(),
        )
        assertEquals(
            schemata("schema chosen @sql(schema: \"first_s\")\n\nmodel A { id uuid { id } }"),
            text(r, "chosen.schemata"),
        )
        assertEquals(
            schemata("schema second_s\n\nmodel B { id uuid { id } }"),
            text(r, "second_s.schemata"),
        )
        assertEquals(emptyList(), messages(r))
    }

    @Test
    fun `a json note naming the record it sits in refers to that record`() {
        val r =
            importText(
                "t.sql" to
                    """
                    CREATE TABLE t."order" (id uuid PRIMARY KEY);
                    CREATE TABLE t."order_lines" (
                      order_id uuid NOT NULL,
                      position integer NOT NULL,
                      meta jsonb NOT NULL,  -- schemata: Line
                      PRIMARY KEY (order_id, position)
                    );
                    ALTER TABLE t."order_lines" ADD CONSTRAINT fk_lines FOREIGN KEY (order_id) REFERENCES t."order" (id) ON DELETE CASCADE;
                    """
            )
        assertEquals(
            schemata(
                """
                schema t

                model Order {
                  id    uuid   { id }
                  lines Line[]

                  model Line { meta Line @sql(strategy: json) }
                }
                """
            ),
            text(r, "t.schemata"),
        )
        assertEquals(emptyList(), messages(r))
    }

    @Test
    fun `an array or json column in a primary key stays a plain key field`() {
        val r =
            importText(
                "t.sql" to
                    """
                    CREATE TABLE t.k (
                      tags text[] NOT NULL,
                      doc jsonb NOT NULL,
                      PRIMARY KEY (tags, doc)
                    );
                    """
            )
        assertEquals(
            schemata(
                """
                schema t

                model K { tags string { id } @sql(type: "text[]")  doc string { id } @sql(type: "jsonb") }
                """
            ),
            text(r, "t.schemata"),
        )
        assertEquals(
            listOf(
                "SCH2404 column 'K.tags': text[] imported as string with @sql(type)",
                "SCH2404 column 'K.doc': jsonb imported as string with @sql(type)",
            ),
            messages(r),
        )
    }
}
