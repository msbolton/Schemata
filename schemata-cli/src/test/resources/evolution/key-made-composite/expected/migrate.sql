-- migrate/s.sql
BEGIN;

ALTER TABLE "s"."order" ADD COLUMN "customer_code" varchar(16);
ALTER TABLE "s"."order" DROP CONSTRAINT IF EXISTS "fk_order_customer";
ALTER TABLE "s"."customer" DROP CONSTRAINT "pk_customer" CASCADE;
ALTER TABLE "s"."order" ALTER COLUMN "customer_code" SET NOT NULL;
ALTER TABLE "s"."customer" ADD CONSTRAINT "pk_customer" PRIMARY KEY ("tenant", "code");
ALTER TABLE "s"."order" ADD CONSTRAINT "fk_order_customer" FOREIGN KEY ("customer_tenant", "customer_code") REFERENCES "s"."customer" ("tenant", "code");

COMMIT;
