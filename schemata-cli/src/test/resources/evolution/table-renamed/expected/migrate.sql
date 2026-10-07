-- migrate/s.sql
BEGIN;

ALTER TABLE "s"."order" RENAME TO "purchase";
ALTER TABLE "s"."order_lines" RENAME TO "purchase_lines";
ALTER TABLE "s"."purchase" RENAME CONSTRAINT "pk_order" TO "pk_purchase";
ALTER TABLE "s"."purchase_lines" RENAME CONSTRAINT "pk_order_lines" TO "pk_purchase_lines";
ALTER TABLE "s"."purchase_lines" RENAME COLUMN "order_id" TO "purchase_id";
ALTER TABLE "s"."purchase_lines" RENAME CONSTRAINT "fk_order_lines_order" TO "fk_purchase_lines_purchase";
ALTER TABLE "s"."purchase_lines" RENAME CONSTRAINT "ck_order_lines_qty_min" TO "ck_purchase_lines_qty_min";

COMMIT;
