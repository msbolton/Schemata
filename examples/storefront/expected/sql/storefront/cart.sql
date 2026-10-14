CREATE SCHEMA IF NOT EXISTS "cart";

CREATE TABLE "cart"."cart" (
  "id" uuid NOT NULL,
  "subtotal_amount" numeric(19, 4) NOT NULL,
  "subtotal_currency" text NOT NULL,
  "updated_at" timestamptz NOT NULL,
  CONSTRAINT "pk_cart" PRIMARY KEY ("id"),
  CONSTRAINT "ck_cart_subtotal_currency_min" CHECK (char_length("subtotal_currency") >= 3),
  CONSTRAINT "ck_cart_subtotal_currency_max" CHECK (char_length("subtotal_currency") <= 3),
  CONSTRAINT "ck_cart_subtotal_currency_pattern" CHECK ("subtotal_currency" ~ '^[A-Z]{3}$')
);

CREATE TABLE "cart"."cart_lines" (
  "cart_id" uuid NOT NULL,
  "position" integer NOT NULL,
  "product_id" uuid NOT NULL,
  "sku" varchar(64) NOT NULL,
  "quantity" integer NOT NULL,
  "unit_price_amount" numeric(19, 4) NOT NULL,
  "unit_price_currency" text NOT NULL,
  CONSTRAINT "pk_cart_lines" PRIMARY KEY ("cart_id", "position"),
  CONSTRAINT "ck_cart_lines_quantity_min" CHECK ("quantity" >= 1),
  CONSTRAINT "ck_cart_lines_quantity_max" CHECK ("quantity" <= 99),
  CONSTRAINT "ck_cart_lines_unit_price_currency_min" CHECK (char_length("unit_price_currency") >= 3),
  CONSTRAINT "ck_cart_lines_unit_price_currency_max" CHECK (char_length("unit_price_currency") <= 3),
  CONSTRAINT "ck_cart_lines_unit_price_currency_pattern" CHECK ("unit_price_currency" ~ '^[A-Z]{3}$')
);

CREATE TABLE "cart"."add_item" (
  "sku" varchar(64) NOT NULL,
  "product_id" uuid NOT NULL,
  "quantity" integer NOT NULL DEFAULT 1,
  CONSTRAINT "pk_add_item" PRIMARY KEY ("sku"),
  CONSTRAINT "ck_add_item_quantity_min" CHECK ("quantity" >= 1),
  CONSTRAINT "ck_add_item_quantity_max" CHECK ("quantity" <= 99)
);

CREATE TABLE "cart"."set_quantity" (
  "sku" varchar(64) NOT NULL,
  "quantity" integer NOT NULL,
  CONSTRAINT "pk_set_quantity" PRIMARY KEY ("sku"),
  CONSTRAINT "ck_set_quantity_quantity_min" CHECK ("quantity" >= 1),
  CONSTRAINT "ck_set_quantity_quantity_max" CHECK ("quantity" <= 99)
);

CREATE TABLE "cart"."item_sku" (
  "sku" varchar(64) NOT NULL,
  CONSTRAINT "pk_item_sku" PRIMARY KEY ("sku")
);

ALTER TABLE "cart"."cart_lines" ADD CONSTRAINT "fk_cart_lines_cart" FOREIGN KEY ("cart_id") REFERENCES "cart"."cart" ("id") ON DELETE CASCADE;

COMMENT ON TABLE "cart"."cart" IS 'What the customer means to buy.';
COMMENT ON TABLE "cart"."cart_lines" IS 'A variant of a product, and how many of it.';
COMMENT ON TABLE "cart"."add_item" IS 'A variant to put in the cart; one already there gains the quantity.';
COMMENT ON TABLE "cart"."set_quantity" IS 'A new quantity for a variant in the cart.';
