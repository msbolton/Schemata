CREATE SCHEMA IF NOT EXISTS "kitchen";

CREATE TABLE "kitchen"."product" (
  "id" uuid NOT NULL,
  "sku" varchar(64) NOT NULL,
  "status" text NOT NULL DEFAULT 'pending',
  "price" numeric(19, 4) NOT NULL,
  "stock" integer NOT NULL DEFAULT 0,
  "ratio" double precision,
  "tags" text[],
  "meta" jsonb,
  "created" timestamptz NOT NULL,
  "legacy" varchar(36),  -- schemata: uuid?
  CONSTRAINT "pk_product" PRIMARY KEY ("id"),
  CONSTRAINT "uq_product_sku" UNIQUE ("sku"),
  CONSTRAINT "ck_product_status_enum" CHECK ("status" IN ('pending', 'paid')),
  CONSTRAINT "ck_product_stock_min" CHECK ("stock" >= 0)
);

CREATE TABLE "kitchen"."order_line" (
  "order_id" uuid NOT NULL,
  "position" integer NOT NULL,
  "sku" text NOT NULL,
  CONSTRAINT "pk_order_line" PRIMARY KEY ("order_id", "position")
);

CREATE TABLE "kitchen"."empty" (
);

CREATE INDEX "ix_product_status" ON "kitchen"."product" ("status");

ALTER TABLE "kitchen"."order_line" ADD CONSTRAINT "fk_order_line_order_id" FOREIGN KEY ("order_id") REFERENCES "kitchen"."product" ("id") ON DELETE CASCADE;

COMMENT ON TABLE "kitchen"."product" IS 'A product for sale.';
COMMENT ON COLUMN "kitchen"."product"."sku" IS 'Stock keeping unit.';
