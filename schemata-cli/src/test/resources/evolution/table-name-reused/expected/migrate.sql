-- migrate/s.sql
BEGIN;

ALTER TABLE "s"."order" RENAME TO "purchase";
ALTER TABLE "s"."purchase" RENAME CONSTRAINT "pk_order" TO "pk_purchase";
ALTER TABLE "s"."purchase" RENAME CONSTRAINT "uq_order_number" TO "uq_purchase_number";
CREATE TABLE "s"."order" (
  "id" uuid NOT NULL,
  "number" varchar(16) NOT NULL,
  CONSTRAINT "pk_order" PRIMARY KEY ("id"),
  CONSTRAINT "uq_order_number" UNIQUE ("number")
);

COMMIT;
