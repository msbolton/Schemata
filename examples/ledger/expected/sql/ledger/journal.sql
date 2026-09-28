CREATE SCHEMA IF NOT EXISTS "ledger_journal";

CREATE TABLE "ledger_journal"."entry" (
  "id" uuid NOT NULL,
  "posted" date NOT NULL,
  "memo" varchar(500),
  "ref_kind" text NOT NULL,
  "ref_invoice_number" varchar(32),
  "ref_payment_reference" varchar(64),
  "ref_source" text,
  CONSTRAINT "pk_entry" PRIMARY KEY ("id"),
  CONSTRAINT "ck_entry_ref_kind" CHECK ("ref_kind" IN ('invoice', 'payment', 'source')),
  CONSTRAINT "ck_entry_ref_invoice" CHECK (("ref_kind" <> 'invoice') OR ("ref_invoice_number" IS NOT NULL)),
  CONSTRAINT "ck_entry_ref_payment" CHECK (("ref_kind" <> 'payment') OR ("ref_payment_reference" IS NOT NULL)),
  CONSTRAINT "ck_entry_ref_source_enum" CHECK ("ref_source" IN ('manual', 'imported', 'accrual')),
  CONSTRAINT "ck_entry_ref_source" CHECK (("ref_kind" <> 'source') OR ("ref_source" IS NOT NULL))
);

CREATE TABLE "ledger_journal"."entry_lines" (
  "entry_id" uuid NOT NULL,
  "position" integer NOT NULL,
  "account_tenant_id" bigint NOT NULL,
  "account_code" varchar(16) NOT NULL,
  "amount" numeric(19, 4) NOT NULL,
  "side" text NOT NULL,
  CONSTRAINT "pk_entry_lines" PRIMARY KEY ("entry_id", "position"),
  CONSTRAINT "ck_entry_lines_side_enum" CHECK ("side" IN ('debit', 'credit'))
);

ALTER TABLE "ledger_journal"."entry_lines" ADD CONSTRAINT "fk_entry_lines_entry" FOREIGN KEY ("entry_id") REFERENCES "ledger_journal"."entry" ("id") ON DELETE CASCADE;
ALTER TABLE "ledger_journal"."entry_lines" ADD CONSTRAINT "fk_entry_lines_account" FOREIGN KEY ("account_tenant_id", "account_code") REFERENCES "ledger"."account" ("tenant_id", "code");

COMMENT ON TABLE "ledger_journal"."entry" IS 'A balanced entry: at least two lines.';
