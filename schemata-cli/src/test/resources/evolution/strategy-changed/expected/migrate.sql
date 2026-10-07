-- migrate/s.sql
BEGIN;

CREATE TABLE "s"."order_tags" (
  "order_id" uuid NOT NULL,
  "position" integer NOT NULL,
  "value" varchar(16) NOT NULL,
  CONSTRAINT "pk_order_tags" PRIMARY KEY ("order_id", "position")
);
ALTER TABLE "s"."order" ADD COLUMN "billing" jsonb;
ALTER TABLE "s"."order" ALTER COLUMN "billing" SET NOT NULL;
ALTER TABLE "s"."order_tags" ADD CONSTRAINT "fk_order_tags_order" FOREIGN KEY ("order_id") REFERENCES "s"."order" ("id") ON DELETE CASCADE;
-- SCH2701: s.Order.tags: DROP COLUMN "tags" loses every value the column holds
ALTER TABLE "s"."order" DROP COLUMN "tags";
-- SCH2701: s.Order.billing.street: DROP COLUMN "billing_street" loses every value the column holds
ALTER TABLE "s"."order" DROP COLUMN "billing_street";
-- SCH2701: s.Order.billing.city: DROP COLUMN "billing_city" loses every value the column holds
ALTER TABLE "s"."order" DROP COLUMN "billing_city";

COMMIT;
