-- migrate/s.sql
BEGIN;

ALTER TABLE "s"."customer" ADD COLUMN "age" integer;
ALTER TABLE "s"."customer" ALTER COLUMN "age" SET NOT NULL;

COMMIT;
