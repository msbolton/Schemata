CREATE SCHEMA IF NOT EXISTS "shop";

CREATE TABLE "shop"."order" (
  "id" uuid NOT NULL,
  "customer_id" uuid NOT NULL,
  "status" text NOT NULL DEFAULT 'pending',
  "total" numeric(19, 4) NOT NULL,
  "payment_kind" text NOT NULL,
  "payment_card_last4" varchar(4),
  "payment_card_brand" varchar(32),
  "payment_bank_transfer_iban" varchar(34),
  "shipping_street" varchar(200) NOT NULL,
  "shipping_city" varchar(100) NOT NULL,
  "shipping_country" text NOT NULL,
  "placed_at" timestamptz NOT NULL,
  "note" varchar(500),
  "created" timestamptz,
  CONSTRAINT "pk_order" PRIMARY KEY ("id"),
  CONSTRAINT "ck_order_status_enum" CHECK ("status" IN ('pending', 'paid', 'shipped', 'cancelled')),
  CONSTRAINT "ck_order_payment_kind" CHECK ("payment_kind" IN ('card', 'bank_transfer', 'cash')),
  CONSTRAINT "ck_order_payment_card" CHECK (("payment_kind" <> 'card') OR ("payment_card_last4" IS NOT NULL AND "payment_card_brand" IS NOT NULL)),
  CONSTRAINT "ck_order_payment_bank_transfer" CHECK (("payment_kind" <> 'bank_transfer') OR ("payment_bank_transfer_iban" IS NOT NULL)),
  CONSTRAINT "ck_order_shipping_country_min" CHECK (char_length("shipping_country") >= 2),
  CONSTRAINT "ck_order_shipping_country_max" CHECK (char_length("shipping_country") <= 2)
);

CREATE TABLE "shop"."order_lines" (
  "order_id" uuid NOT NULL,
  "position" integer NOT NULL,
  "sku" varchar(64) NOT NULL,
  "quantity" integer NOT NULL,
  "price" numeric(19, 4) NOT NULL,
  CONSTRAINT "pk_order_lines" PRIMARY KEY ("order_id", "position"),
  CONSTRAINT "ck_order_lines_quantity_min" CHECK ("quantity" >= 1)
);

ALTER TABLE "shop"."order" ADD CONSTRAINT "fk_order_customer" FOREIGN KEY ("customer_id") REFERENCES "customers"."customer" ("id");
ALTER TABLE "shop"."order_lines" ADD CONSTRAINT "fk_order_lines_order" FOREIGN KEY ("order_id") REFERENCES "shop"."order" ("id") ON DELETE CASCADE;

COMMENT ON TABLE "shop"."order" IS 'A customer''s order. One row per checkout.';
COMMENT ON TABLE "shop"."order_lines" IS 'One purchasable item.';
