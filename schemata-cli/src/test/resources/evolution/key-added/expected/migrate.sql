-- migrate/s.sql
BEGIN;

-- SCH2701: s.Order.customer.id: DROP COLUMN "customer_id" loses every value the column holds
ALTER TABLE "s"."order" DROP COLUMN "customer_id";
CREATE TABLE "s"."customer" (
  "id" uuid NOT NULL,
  "name" varchar(100) NOT NULL,
  CONSTRAINT "pk_customer" PRIMARY KEY ("id")
);
ALTER TABLE "s"."order" ADD COLUMN "customer_id" uuid;
ALTER TABLE "s"."order" ADD CONSTRAINT "fk_order_customer" FOREIGN KEY ("customer_id") REFERENCES "s"."customer" ("id");
-- SCH2701: s.Order.customer.name: DROP COLUMN "customer_name" loses every value the column holds
ALTER TABLE "s"."order" DROP COLUMN "customer_name";
ALTER TABLE "s"."order" ALTER COLUMN "customer_id" SET NOT NULL;

COMMIT;
