-- migrate/s.sql
BEGIN;

ALTER TABLE "s"."customer" DROP CONSTRAINT "pk_customer" CASCADE;
ALTER TABLE "s"."customer" ADD CONSTRAINT "pk_customer" PRIMARY KEY ("code");

COMMIT;
