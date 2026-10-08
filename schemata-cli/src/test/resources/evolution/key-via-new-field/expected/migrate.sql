-- migrate/s.sql
BEGIN;

CREATE TABLE "s"."customer" (
  "id" uuid NOT NULL,
  "name" varchar(100) NOT NULL,
  "code" varchar(16) NOT NULL,
  CONSTRAINT "pk_customer" PRIMARY KEY ("code")
);
ALTER TABLE "s"."order" ADD COLUMN "customer_code" varchar(16);
ALTER TABLE "s"."order" ADD CONSTRAINT "fk_order_customer" FOREIGN KEY ("customer_code") REFERENCES "s"."customer" ("code");
-- SCH2701: s.Order.customer.id: DROP COLUMN "customer_id" loses every value the column holds
ALTER TABLE "s"."order" DROP COLUMN "customer_id";
-- SCH2701: s.Order.customer.name: DROP COLUMN "customer_name" loses every value the column holds
ALTER TABLE "s"."order" DROP COLUMN "customer_name";
ALTER TABLE "s"."order" ALTER COLUMN "customer_code" SET NOT NULL;

COMMIT;
