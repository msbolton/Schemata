-- migrate/s.sql
BEGIN;

-- SCH2701: s.Customer.visits: ALTER COLUMN "visits" TYPE integer loses values that do not fit integer
ALTER TABLE "s"."customer" ALTER COLUMN "visits" TYPE integer USING "visits"::integer;

COMMIT;
