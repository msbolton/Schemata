CREATE SCHEMA IF NOT EXISTS "ledger";

CREATE TABLE "ledger"."account" (
  "tenant_id" bigint NOT NULL,
  "code" varchar(16) NOT NULL,
  "name" varchar(120) NOT NULL,
  "kind" text NOT NULL,
  "opened" date NOT NULL,
  "closed" date,
  "limits_overdraft" numeric(19, 4) NOT NULL,
  "limits_daily" numeric(19, 4),
  CONSTRAINT "pk_account" PRIMARY KEY ("tenant_id", "code"),
  CONSTRAINT "ck_account_kind_enum" CHECK ("kind" IN ('asset', 'liability', 'equity', 'revenue', 'expense'))
);

CREATE TABLE "ledger"."account_balances" (
  "account_tenant_id" bigint NOT NULL,
  "account_code" varchar(16) NOT NULL,
  "key" text NOT NULL,
  "value" numeric(19, 4) NOT NULL,
  CONSTRAINT "pk_account_balances" PRIMARY KEY ("account_tenant_id", "account_code", "key")
);

ALTER TABLE "ledger"."account_balances" ADD CONSTRAINT "fk_account_balances_account" FOREIGN KEY ("account_tenant_id", "account_code") REFERENCES "ledger"."account" ("tenant_id", "code") ON DELETE CASCADE;

COMMENT ON TABLE "ledger"."account" IS 'An account, keyed by tenant and code.';
