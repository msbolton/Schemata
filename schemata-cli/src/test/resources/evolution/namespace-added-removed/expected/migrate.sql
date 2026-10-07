-- migrate/b.sql
BEGIN;

CREATE SCHEMA IF NOT EXISTS "b";
CREATE TABLE "b"."invoice" (
  "id" uuid NOT NULL,
  "customer_id" uuid NOT NULL,
  "total" numeric(19, 4) NOT NULL,
  CONSTRAINT "pk_invoice" PRIMARY KEY ("id")
);
ALTER TABLE "b"."invoice" ADD CONSTRAINT "fk_invoice_customer" FOREIGN KEY ("customer_id") REFERENCES "a"."customer" ("id");

COMMIT;

-- migrate/c.sql
BEGIN;

-- SCH2701: c.Ticket: DROP TABLE "ticket" loses every row of the table
DROP TABLE "c"."ticket" CASCADE;
DROP SCHEMA "c";

COMMIT;
