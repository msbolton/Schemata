CREATE SCHEMA IF NOT EXISTS "children";

CREATE TABLE "children"."order" (
  "id" uuid NOT NULL,
  "tags" text[] NOT NULL,  -- schemata: list<string(max = 16)>
  "attributes" jsonb NOT NULL,  -- schemata: map<string, string>
  CONSTRAINT "pk_order" PRIMARY KEY ("id")
);

CREATE TABLE "children"."order_lines" (
  "order_id" uuid NOT NULL,
  "position" integer NOT NULL,
  "sku" varchar(64) NOT NULL,
  "quantity" integer NOT NULL,
  CONSTRAINT "pk_order_lines" PRIMARY KEY ("order_id", "position"),
  CONSTRAINT "ck_order_lines_quantity_min" CHECK ("quantity" >= 1)
);

CREATE TABLE "children"."order_codes" (
  "order_id" uuid NOT NULL,
  "position" integer NOT NULL,
  "value" varchar(8) NOT NULL,
  CONSTRAINT "pk_order_codes" PRIMARY KEY ("order_id", "position")
);

CREATE TABLE "children"."order_prices" (
  "order_id" uuid NOT NULL,
  "key" text NOT NULL,
  "value" numeric(10, 2) NOT NULL,
  CONSTRAINT "pk_order_prices" PRIMARY KEY ("order_id", "key")
);

ALTER TABLE "children"."order_lines" ADD CONSTRAINT "fk_order_lines_order" FOREIGN KEY ("order_id") REFERENCES "children"."order" ("id") ON DELETE CASCADE;
ALTER TABLE "children"."order_codes" ADD CONSTRAINT "fk_order_codes_order" FOREIGN KEY ("order_id") REFERENCES "children"."order" ("id") ON DELETE CASCADE;
ALTER TABLE "children"."order_prices" ADD CONSTRAINT "fk_order_prices_order" FOREIGN KEY ("order_id") REFERENCES "children"."order" ("id") ON DELETE CASCADE;

COMMENT ON TABLE "children"."order_lines" IS 'One purchasable item.';
