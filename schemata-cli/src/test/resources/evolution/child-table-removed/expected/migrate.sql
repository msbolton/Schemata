-- migrate/s.sql
BEGIN;

-- SCH2701: s.Order.lines: DROP TABLE "order_lines" loses every row of the table
DROP TABLE "s"."order_lines" CASCADE;

COMMIT;
