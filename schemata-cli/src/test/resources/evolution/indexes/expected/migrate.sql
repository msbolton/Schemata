-- migrate/s.sql
BEGIN;

ALTER TABLE "s"."customer" RENAME CONSTRAINT "uq_customer_code" TO "uq_customer_ref";
ALTER TABLE "s"."customer" RENAME COLUMN "code" TO "ref";
DROP INDEX "s"."ix_customer_email";
CREATE INDEX "ix_customer_city" ON "s"."customer" ("city");

COMMIT;
