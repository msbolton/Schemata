-- migrate/a.sql
BEGIN;

ALTER TABLE "a"."order" DROP CONSTRAINT IF EXISTS "fk_order_buyer";
ALTER TABLE "a"."order" ALTER COLUMN "buyer_id" TYPE text USING "buyer_id"::text;

COMMIT;

-- migrate/b.sql
BEGIN;

ALTER TABLE "b"."customer" DROP CONSTRAINT "pk_customer" CASCADE;
ALTER TABLE "b"."customer" ALTER COLUMN "id" TYPE text USING "id"::text;
ALTER TABLE "b"."customer" ADD CONSTRAINT "pk_customer" PRIMARY KEY ("id");
ALTER TABLE "a"."order" ADD CONSTRAINT "fk_order_buyer" FOREIGN KEY ("buyer_id") REFERENCES "b"."customer" ("id");

COMMIT;
