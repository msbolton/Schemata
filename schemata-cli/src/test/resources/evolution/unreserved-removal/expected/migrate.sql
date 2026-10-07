-- migrate/s.sql
BEGIN;

-- SCH2701: s.Customer.note: DROP COLUMN "note" loses every value the column holds
ALTER TABLE "s"."customer" DROP COLUMN "note";

COMMIT;
