-- migrate/s.sql
BEGIN;

-- SCH2701: s.Customer.orders: DROP TABLE "customer_orders" loses every row of the table
DROP TABLE "s"."customer_orders" CASCADE;

COMMIT;
