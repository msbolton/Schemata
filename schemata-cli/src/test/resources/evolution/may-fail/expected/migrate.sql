-- migrate/s.sql
BEGIN;

ALTER TABLE "s"."customer" DROP CONSTRAINT "ck_customer_age_min";
ALTER TABLE "s"."customer" ALTER COLUMN "email" SET NOT NULL;
ALTER TABLE "s"."customer" ADD CONSTRAINT "uq_customer_code" UNIQUE ("code");
ALTER TABLE "s"."customer" ADD CONSTRAINT "ck_customer_age_min" CHECK ("age" >= 18);

COMMIT;
