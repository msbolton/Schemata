-- migrate/s.sql
BEGIN;

CREATE TABLE "s"."membership" (
  "id" uuid NOT NULL,
  "customer_id" uuid NOT NULL,
  "code" varchar(32) NOT NULL,
  "region" varchar(16) NOT NULL,
  CONSTRAINT "pk_membership" PRIMARY KEY ("id"),
  CONSTRAINT "uq_membership_customer_code" UNIQUE ("customer_id", "code")
);
ALTER TABLE "s"."order" DROP CONSTRAINT IF EXISTS "fk_order_customer";
ALTER TABLE "s"."order" ADD CONSTRAINT "fk_order_customer" FOREIGN KEY ("customer_id") REFERENCES "s"."customer" ("id") ON DELETE SET NULL;
ALTER TABLE "s"."membership" ADD CONSTRAINT "fk_membership_customer" FOREIGN KEY ("customer_id") REFERENCES "s"."customer" ("id") ON DELETE CASCADE;
CREATE INDEX "ix_membership_region_code" ON "s"."membership" ("region", "code");

COMMIT;
