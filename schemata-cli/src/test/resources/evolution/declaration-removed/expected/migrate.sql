-- migrate/s.sql
BEGIN;

-- SCH2701: s.Customer: DROP TABLE "customer" loses every row of the table
DROP TABLE "s"."customer" CASCADE;
-- SCH2701: s.Order.Note: DROP TABLE "note" loses every row of the table
DROP TABLE "s"."note" CASCADE;

COMMIT;
