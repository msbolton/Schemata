-- migrate/s.sql
BEGIN;

ALTER TABLE "s"."job" ALTER COLUMN "retries" DROP DEFAULT;
-- SCH2701: s.Job.retries: ALTER COLUMN "retries" TYPE integer loses values that do not fit integer (the cast fails or truncates)
ALTER TABLE "s"."job" ALTER COLUMN "retries" TYPE integer USING "retries"::integer;
ALTER TABLE "s"."job" ALTER COLUMN "retries" SET DEFAULT 3;

COMMIT;
