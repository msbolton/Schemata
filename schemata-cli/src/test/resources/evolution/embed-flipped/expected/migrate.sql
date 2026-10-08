-- migrate/s.sql
BEGIN;

-- SCH2701: s.Order.customer: DROP COLUMN "customer_id" loses every value the column holds
ALTER TABLE "s"."order" DROP COLUMN "customer_id";
-- SCH2701: s.Order.billing.id: DROP COLUMN "billing_id" loses every value the column holds
ALTER TABLE "s"."order" DROP COLUMN "billing_id";
ALTER TABLE "s"."order" ADD COLUMN "customer_id" uuid;
ALTER TABLE "s"."order" ADD COLUMN "customer_name" varchar(64);
ALTER TABLE "s"."order" ADD COLUMN "billing_id" uuid;
ALTER TABLE "s"."order" DROP CONSTRAINT IF EXISTS "fk_order_customer";
ALTER TABLE "s"."order" ADD CONSTRAINT "fk_order_billing" FOREIGN KEY ("billing_id") REFERENCES "s"."customer" ("id");
-- SCH2701: s.Order.billing.name: DROP COLUMN "billing_name" loses every value the column holds
ALTER TABLE "s"."order" DROP COLUMN "billing_name";
ALTER TABLE "s"."order" ALTER COLUMN "customer_id" SET NOT NULL;
ALTER TABLE "s"."order" ALTER COLUMN "customer_name" SET NOT NULL;
ALTER TABLE "s"."order" ALTER COLUMN "billing_id" SET NOT NULL;

COMMIT;
