-- migrate/s.sql
BEGIN;

ALTER TABLE "s"."order" DROP CONSTRAINT "ck_order_status_enum";
ALTER TABLE "s"."order" ADD CONSTRAINT "ck_order_status_enum" CHECK ("status" IN ('pending', 'settled', 'refunded'));

COMMIT;
