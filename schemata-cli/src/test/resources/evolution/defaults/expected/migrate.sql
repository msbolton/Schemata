-- migrate/s.sql
BEGIN;

ALTER TABLE "s"."settings" ALTER COLUMN "retries" SET DEFAULT 3;
ALTER TABLE "s"."settings" ALTER COLUMN "timeout" SET DEFAULT 60;
ALTER TABLE "s"."settings" ALTER COLUMN "region" DROP DEFAULT;

COMMIT;
