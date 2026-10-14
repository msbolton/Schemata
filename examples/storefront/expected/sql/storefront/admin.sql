CREATE SCHEMA IF NOT EXISTS "admin";

CREATE TABLE "admin"."set_availability" (
  "sku" varchar(64) NOT NULL,
  "availability" text NOT NULL,
  CONSTRAINT "pk_set_availability" PRIMARY KEY ("sku"),
  CONSTRAINT "ck_set_availability_availability_enum" CHECK ("availability" IN ('in_stock', 'backorder', 'out_of_stock'))
);

CREATE TABLE "admin"."product_id" (
  "id" uuid NOT NULL,
  CONSTRAINT "pk_product_id" PRIMARY KEY ("id")
);

CREATE TABLE "admin"."ship" (
  "id" uuid NOT NULL,
  "carrier" varchar(50) NOT NULL,
  "tracking_number" varchar(100) NOT NULL,
  CONSTRAINT "pk_ship" PRIMARY KEY ("id")
);

COMMENT ON TABLE "admin"."set_availability" IS 'Which variant''s stock to change, and to what.';
COMMENT ON TABLE "admin"."ship" IS 'Hands an order to a carrier.';
