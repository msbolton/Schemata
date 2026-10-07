-- migrate/s.sql
BEGIN;

ALTER TABLE "s"."customer_notes" ADD COLUMN "customer_code" varchar(16);
ALTER TABLE "s"."order" ADD COLUMN "customer_code" varchar(16);
ALTER TABLE "s"."customer_notes" DROP CONSTRAINT IF EXISTS "fk_customer_notes_customer";
ALTER TABLE "s"."order" DROP CONSTRAINT IF EXISTS "fk_order_customer";
ALTER TABLE "s"."customer" DROP CONSTRAINT "pk_customer" CASCADE;
ALTER TABLE "s"."customer_notes" DROP CONSTRAINT "pk_customer_notes" CASCADE;
ALTER TABLE "s"."customer" ADD CONSTRAINT "pk_customer" PRIMARY KEY ("code");
ALTER TABLE "s"."customer_notes" ADD CONSTRAINT "pk_customer_notes" PRIMARY KEY ("customer_code", "position");
ALTER TABLE "s"."customer_notes" ADD CONSTRAINT "fk_customer_notes_customer" FOREIGN KEY ("customer_code") REFERENCES "s"."customer" ("code") ON DELETE CASCADE;
ALTER TABLE "s"."order" ADD CONSTRAINT "fk_order_customer" FOREIGN KEY ("customer_code") REFERENCES "s"."customer" ("code");
-- SCH2701: s.Customer.notes.parent: DROP COLUMN "customer_id" loses every value the column holds
ALTER TABLE "s"."customer_notes" DROP COLUMN "customer_id";
-- SCH2701: s.Order.customer: DROP COLUMN "customer_id" loses every value the column holds
ALTER TABLE "s"."order" DROP COLUMN "customer_id";
ALTER TABLE "s"."customer_notes" ALTER COLUMN "customer_code" SET NOT NULL;
ALTER TABLE "s"."order" ALTER COLUMN "customer_code" SET NOT NULL;

COMMIT;
