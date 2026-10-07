-- migrate/s.sql
BEGIN;

ALTER TABLE "s"."customer" ALTER COLUMN "visits" TYPE bigint USING "visits"::bigint;

COMMIT;
