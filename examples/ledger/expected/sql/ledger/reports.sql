CREATE SCHEMA IF NOT EXISTS "ledger_reports";

CREATE TABLE "ledger_reports"."close" (
  "id" uuid NOT NULL,
  "period" text NOT NULL,
  "account_tenant_id" bigint NOT NULL,
  "account_code" varchar(16) NOT NULL,
  "last_id" uuid,
  "totals_debits" numeric(19, 4) NOT NULL,
  "totals_credits" numeric(19, 4) NOT NULL,
  CONSTRAINT "pk_close" PRIMARY KEY ("id"),
  CONSTRAINT "ck_close_period_max" CHECK (char_length("period") <= 7),
  CONSTRAINT "ck_close_period_pattern" CHECK ("period" ~ '^[0-9]{4}-[0-9]{2}$')
);

ALTER TABLE "ledger_reports"."close" ADD CONSTRAINT "fk_close_account" FOREIGN KEY ("account_tenant_id", "account_code") REFERENCES "ledger"."account" ("tenant_id", "code");
ALTER TABLE "ledger_reports"."close" ADD CONSTRAINT "fk_close_last" FOREIGN KEY ("last_id") REFERENCES "ledger_journal"."entry" ("id");

COMMENT ON TABLE "ledger_reports"."close" IS 'A close of one account for one period.';
