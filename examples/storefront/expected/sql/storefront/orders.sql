CREATE SCHEMA IF NOT EXISTS "orders";

CREATE TABLE "orders"."order" (
  "id" uuid NOT NULL,
  "number" varchar(20) NOT NULL,
  "customer_id" uuid NOT NULL,
  "status" text NOT NULL DEFAULT 'pending',
  "shipping_id" uuid NOT NULL,
  "shipping_kind" text NOT NULL,
  "shipping_name" varchar(100) NOT NULL,
  "shipping_line1" varchar(200) NOT NULL,
  "shipping_line2" varchar(200),
  "shipping_city" varchar(100) NOT NULL,
  "shipping_region" varchar(100),
  "shipping_postal_code" varchar(20) NOT NULL,
  "shipping_country" text NOT NULL,
  "shipping_is_default" boolean NOT NULL DEFAULT false,
  "subtotal_amount" numeric(19, 4) NOT NULL,
  "subtotal_currency" text NOT NULL,
  "shipping_cost_amount" numeric(19, 4) NOT NULL,
  "shipping_cost_currency" text NOT NULL,
  "tax_amount" numeric(19, 4) NOT NULL,
  "tax_currency" text NOT NULL,
  "total_amount" numeric(19, 4) NOT NULL,
  "total_currency" text NOT NULL,
  "placed_at" timestamptz NOT NULL,
  CONSTRAINT "pk_order" PRIMARY KEY ("id"),
  CONSTRAINT "uq_order_number" UNIQUE ("number"),
  CONSTRAINT "ck_order_status_enum" CHECK ("status" IN ('pending', 'paid', 'shipped', 'delivered', 'cancelled', 'refunded')),
  CONSTRAINT "ck_order_shipping_kind_enum" CHECK ("shipping_kind" IN ('shipping', 'billing')),
  CONSTRAINT "ck_order_shipping_country_min" CHECK (char_length("shipping_country") >= 2),
  CONSTRAINT "ck_order_shipping_country_max" CHECK (char_length("shipping_country") <= 2),
  CONSTRAINT "ck_order_shipping_country_pattern" CHECK ("shipping_country" ~ '^[A-Z]{2}$'),
  CONSTRAINT "ck_order_subtotal_currency_min" CHECK (char_length("subtotal_currency") >= 3),
  CONSTRAINT "ck_order_subtotal_currency_max" CHECK (char_length("subtotal_currency") <= 3),
  CONSTRAINT "ck_order_subtotal_currency_pattern" CHECK ("subtotal_currency" ~ '^[A-Z]{3}$'),
  CONSTRAINT "ck_order_shipping_cost_currency_min" CHECK (char_length("shipping_cost_currency") >= 3),
  CONSTRAINT "ck_order_shipping_cost_currency_max" CHECK (char_length("shipping_cost_currency") <= 3),
  CONSTRAINT "ck_order_shipping_cost_currency_pattern" CHECK ("shipping_cost_currency" ~ '^[A-Z]{3}$'),
  CONSTRAINT "ck_order_tax_currency_min" CHECK (char_length("tax_currency") >= 3),
  CONSTRAINT "ck_order_tax_currency_max" CHECK (char_length("tax_currency") <= 3),
  CONSTRAINT "ck_order_tax_currency_pattern" CHECK ("tax_currency" ~ '^[A-Z]{3}$'),
  CONSTRAINT "ck_order_total_currency_min" CHECK (char_length("total_currency") >= 3),
  CONSTRAINT "ck_order_total_currency_max" CHECK (char_length("total_currency") <= 3),
  CONSTRAINT "ck_order_total_currency_pattern" CHECK ("total_currency" ~ '^[A-Z]{3}$')
);

CREATE TABLE "orders"."order_lines" (
  "order_id" uuid NOT NULL,
  "position" integer NOT NULL,
  "product_id" uuid NOT NULL,
  "sku" varchar(64) NOT NULL,
  "name" varchar(200) NOT NULL,
  "quantity" integer NOT NULL,
  "unit_price_amount" numeric(19, 4) NOT NULL,
  "unit_price_currency" text NOT NULL,
  CONSTRAINT "pk_order_lines" PRIMARY KEY ("order_id", "position"),
  CONSTRAINT "ck_order_lines_quantity_min" CHECK ("quantity" >= 1),
  CONSTRAINT "ck_order_lines_unit_price_currency_min" CHECK (char_length("unit_price_currency") >= 3),
  CONSTRAINT "ck_order_lines_unit_price_currency_max" CHECK (char_length("unit_price_currency") <= 3),
  CONSTRAINT "ck_order_lines_unit_price_currency_pattern" CHECK ("unit_price_currency" ~ '^[A-Z]{3}$')
);

CREATE TABLE "orders"."checkout" (
  "idempotency_key" uuid NOT NULL,
  "cart_id" uuid NOT NULL,
  "shipping_address_id" uuid NOT NULL,
  "note" varchar(500),
  CONSTRAINT "pk_checkout" PRIMARY KEY ("idempotency_key")
);

CREATE TABLE "orders"."placed" (
  "location" varchar(2048) NOT NULL,
  "order_id" uuid NOT NULL,
  "number" varchar(20) NOT NULL,
  "total_amount" numeric(19, 4) NOT NULL,
  "total_currency" text NOT NULL,
  CONSTRAINT "pk_placed" PRIMARY KEY ("location"),
  CONSTRAINT "ck_placed_total_currency_min" CHECK (char_length("total_currency") >= 3),
  CONSTRAINT "ck_placed_total_currency_max" CHECK (char_length("total_currency") <= 3),
  CONSTRAINT "ck_placed_total_currency_pattern" CHECK ("total_currency" ~ '^[A-Z]{3}$')
);

CREATE TABLE "orders"."order_page" (
  "etag" varchar(64) NOT NULL,
  "page_next_cursor" varchar(200),
  "page_total" bigint NOT NULL,
  "query_status" text,
  "query_cursor" varchar(200),
  "query_limit" integer NOT NULL DEFAULT 20,
  CONSTRAINT "pk_order_page" PRIMARY KEY ("etag"),
  CONSTRAINT "ck_order_page_page_total_min" CHECK ("page_total" >= 0),
  CONSTRAINT "ck_order_page_query_status_enum" CHECK ("query_status" IN ('pending', 'paid', 'shipped', 'delivered', 'cancelled', 'refunded')),
  CONSTRAINT "ck_order_page_query_limit_min" CHECK ("query_limit" >= 1),
  CONSTRAINT "ck_order_page_query_limit_max" CHECK ("query_limit" <= 100)
);

CREATE TABLE "orders"."order_page_items" (
  "order_page_etag" varchar(64) NOT NULL,
  "position" integer NOT NULL,
  "id" uuid NOT NULL,
  "number" varchar(20) NOT NULL,
  "customer_id" uuid NOT NULL,
  "status" text NOT NULL DEFAULT 'pending',
  "shipping_id" uuid NOT NULL,
  "shipping_kind" text NOT NULL,
  "shipping_name" varchar(100) NOT NULL,
  "shipping_line1" varchar(200) NOT NULL,
  "shipping_line2" varchar(200),
  "shipping_city" varchar(100) NOT NULL,
  "shipping_region" varchar(100),
  "shipping_postal_code" varchar(20) NOT NULL,
  "shipping_country" text NOT NULL,
  "shipping_is_default" boolean NOT NULL DEFAULT false,
  "subtotal_amount" numeric(19, 4) NOT NULL,
  "subtotal_currency" text NOT NULL,
  "shipping_cost_amount" numeric(19, 4) NOT NULL,
  "shipping_cost_currency" text NOT NULL,
  "tax_amount" numeric(19, 4) NOT NULL,
  "tax_currency" text NOT NULL,
  "total_amount" numeric(19, 4) NOT NULL,
  "total_currency" text NOT NULL,
  "placed_at" timestamptz NOT NULL,
  CONSTRAINT "pk_order_page_items" PRIMARY KEY ("order_page_etag", "position"),
  CONSTRAINT "uq_order_page_items_number" UNIQUE ("number"),
  CONSTRAINT "ck_order_page_items_status_enum" CHECK ("status" IN ('pending', 'paid', 'shipped', 'delivered', 'cancelled', 'refunded')),
  CONSTRAINT "ck_order_page_items_shipping_kind_enum" CHECK ("shipping_kind" IN ('shipping', 'billing')),
  CONSTRAINT "ck_order_page_items_shipping_country_min" CHECK (char_length("shipping_country") >= 2),
  CONSTRAINT "ck_order_page_items_shipping_country_max" CHECK (char_length("shipping_country") <= 2),
  CONSTRAINT "ck_order_page_items_shipping_country_pattern" CHECK ("shipping_country" ~ '^[A-Z]{2}$'),
  CONSTRAINT "ck_order_page_items_subtotal_currency_min" CHECK (char_length("subtotal_currency") >= 3),
  CONSTRAINT "ck_order_page_items_subtotal_currency_max" CHECK (char_length("subtotal_currency") <= 3),
  CONSTRAINT "ck_order_page_items_subtotal_currency_pattern" CHECK ("subtotal_currency" ~ '^[A-Z]{3}$'),
  CONSTRAINT "ck_order_page_items_shipping_cost_currency_min" CHECK (char_length("shipping_cost_currency") >= 3),
  CONSTRAINT "ck_order_page_items_shipping_cost_currency_max" CHECK (char_length("shipping_cost_currency") <= 3),
  CONSTRAINT "ck_order_page_items_shipping_cost_currency_pattern" CHECK ("shipping_cost_currency" ~ '^[A-Z]{3}$'),
  CONSTRAINT "ck_order_page_items_tax_currency_min" CHECK (char_length("tax_currency") >= 3),
  CONSTRAINT "ck_order_page_items_tax_currency_max" CHECK (char_length("tax_currency") <= 3),
  CONSTRAINT "ck_order_page_items_tax_currency_pattern" CHECK ("tax_currency" ~ '^[A-Z]{3}$'),
  CONSTRAINT "ck_order_page_items_total_currency_min" CHECK (char_length("total_currency") >= 3),
  CONSTRAINT "ck_order_page_items_total_currency_max" CHECK (char_length("total_currency") <= 3),
  CONSTRAINT "ck_order_page_items_total_currency_pattern" CHECK ("total_currency" ~ '^[A-Z]{3}$')
);

CREATE TABLE "orders"."order_page_items_lines" (
  "order_page_items_order_page_etag" varchar(64) NOT NULL,
  "order_page_items_position" integer NOT NULL,
  "position" integer NOT NULL,
  "product_id" uuid NOT NULL,
  "sku" varchar(64) NOT NULL,
  "name" varchar(200) NOT NULL,
  "quantity" integer NOT NULL,
  "unit_price_amount" numeric(19, 4) NOT NULL,
  "unit_price_currency" text NOT NULL,
  CONSTRAINT "pk_order_page_items_lines" PRIMARY KEY ("order_page_items_order_page_etag", "order_page_items_position", "position"),
  CONSTRAINT "ck_order_page_items_lines_quantity_min" CHECK ("quantity" >= 1),
  CONSTRAINT "ck_order_page_items_lines_unit_price_currency_min" CHECK (char_length("unit_price_currency") >= 3),
  CONSTRAINT "ck_order_page_items_lines_unit_price_currency_max" CHECK (char_length("unit_price_currency") <= 3),
  CONSTRAINT "ck_order_page_items_lines_unit_price_currency_pattern" CHECK ("unit_price_currency" ~ '^[A-Z]{3}$')
);

CREATE TABLE "orders"."order_id" (
  "id" uuid NOT NULL,
  CONSTRAINT "pk_order_id" PRIMARY KEY ("id")
);

CREATE TABLE "orders"."cancel_order" (
  "id" uuid NOT NULL,
  "reason" varchar(500),
  CONSTRAINT "pk_cancel_order" PRIMARY KEY ("id")
);

ALTER TABLE "orders"."order_lines" ADD CONSTRAINT "fk_order_lines_order" FOREIGN KEY ("order_id") REFERENCES "orders"."order" ("id") ON DELETE CASCADE;
ALTER TABLE "orders"."order_lines" ADD CONSTRAINT "fk_order_lines_product" FOREIGN KEY ("product_id") REFERENCES "catalog"."product" ("id");
ALTER TABLE "orders"."checkout" ADD CONSTRAINT "fk_checkout_cart" FOREIGN KEY ("cart_id") REFERENCES "cart"."cart" ("id");
ALTER TABLE "orders"."placed" ADD CONSTRAINT "fk_placed_order" FOREIGN KEY ("order_id") REFERENCES "orders"."order" ("id");
ALTER TABLE "orders"."order_page_items" ADD CONSTRAINT "fk_order_page_items_order_page" FOREIGN KEY ("order_page_etag") REFERENCES "orders"."order_page" ("etag") ON DELETE CASCADE;
ALTER TABLE "orders"."order_page_items_lines" ADD CONSTRAINT "fk_order_page_items_lines_order_page_items" FOREIGN KEY ("order_page_items_order_page_etag", "order_page_items_position") REFERENCES "orders"."order_page_items" ("order_page_etag", "position") ON DELETE CASCADE;
ALTER TABLE "orders"."order_page_items_lines" ADD CONSTRAINT "fk_order_page_items_lines_product" FOREIGN KEY ("product_id") REFERENCES "catalog"."product" ("id");

COMMENT ON TABLE "orders"."order" IS 'An order checkout placed.';
COMMENT ON COLUMN "orders"."order"."number" IS 'The number the customer quotes, such as `SF-104223`.';
COMMENT ON COLUMN "orders"."order"."shipping_country" IS 'A two-letter ISO 3166-1 country code.';
COMMENT ON TABLE "orders"."order_lines" IS 'A variant bought, at the price it had at checkout.';
COMMENT ON TABLE "orders"."checkout" IS 'Places an order for everything in the cart.';
COMMENT ON COLUMN "orders"."checkout"."idempotency_key" IS 'A key the client makes up, so a retried checkout places one order.';
COMMENT ON TABLE "orders"."placed" IS 'The order checkout placed, and where to read it from now on.';
COMMENT ON COLUMN "orders"."placed"."location" IS 'The URL of the new order.';
COMMENT ON TABLE "orders"."order_page" IS 'One page of the customer''s orders.';
COMMENT ON COLUMN "orders"."order_page"."page_next_cursor" IS 'The cursor of the next page; absent on the last one.';
COMMENT ON COLUMN "orders"."order_page"."page_total" IS 'How many items the listing holds in all.';
COMMENT ON TABLE "orders"."order_page_items" IS 'An order checkout placed.';
COMMENT ON COLUMN "orders"."order_page_items"."number" IS 'The number the customer quotes, such as `SF-104223`.';
COMMENT ON COLUMN "orders"."order_page_items"."shipping_country" IS 'A two-letter ISO 3166-1 country code.';
COMMENT ON TABLE "orders"."order_page_items_lines" IS 'A variant bought, at the price it had at checkout.';
COMMENT ON TABLE "orders"."cancel_order" IS 'Why the customer cancels.';
