CREATE SCHEMA IF NOT EXISTS "orders";

CREATE TABLE "orders"."order_id" (
  "id" uuid NOT NULL,
  CONSTRAINT "pk_order_id" PRIMARY KEY ("id")
);

CREATE TABLE "orders"."list_orders" (
  "status" text,
  "limit" integer NOT NULL DEFAULT 50,
  CONSTRAINT "pk_list_orders" PRIMARY KEY ("limit"),
  CONSTRAINT "ck_list_orders_status_enum" CHECK ("status" IN ('pending', 'paid', 'shipped', 'cancelled')),
  CONSTRAINT "ck_list_orders_limit_min" CHECK ("limit" >= 1),
  CONSTRAINT "ck_list_orders_limit_max" CHECK ("limit" <= 200)
);

CREATE TABLE "orders"."place_order" (
  "customer_id" uuid NOT NULL,
  CONSTRAINT "pk_place_order" PRIMARY KEY ("customer_id")
);

CREATE TABLE "orders"."place_order_lines" (
  "place_order_customer_id" uuid NOT NULL,
  "position" integer NOT NULL,
  "sku" varchar(64) NOT NULL,
  "quantity" integer NOT NULL,
  CONSTRAINT "pk_place_order_lines" PRIMARY KEY ("place_order_customer_id", "position"),
  CONSTRAINT "ck_place_order_lines_quantity_min" CHECK ("quantity" >= 1)
);

CREATE TABLE "orders"."order" (
  "id" uuid NOT NULL,
  "status" text NOT NULL,
  "total_amount" numeric(19, 4) NOT NULL,
  "total_currency" text NOT NULL,
  CONSTRAINT "pk_order" PRIMARY KEY ("id"),
  CONSTRAINT "ck_order_status_enum" CHECK ("status" IN ('pending', 'paid', 'shipped', 'cancelled')),
  CONSTRAINT "ck_order_total_currency_min" CHECK (char_length("total_currency") >= 3),
  CONSTRAINT "ck_order_total_currency_max" CHECK (char_length("total_currency") <= 3)
);

CREATE TABLE "orders"."order_lines" (
  "order_id" uuid NOT NULL,
  "position" integer NOT NULL,
  "sku" varchar(64) NOT NULL,
  "quantity" integer NOT NULL,
  CONSTRAINT "pk_order_lines" PRIMARY KEY ("order_id", "position"),
  CONSTRAINT "ck_order_lines_quantity_min" CHECK ("quantity" >= 1)
);

CREATE TABLE "orders"."chunk" (
  "bytes" bytea NOT NULL,
  CONSTRAINT "pk_chunk" PRIMARY KEY ("bytes")
);

CREATE TABLE "orders"."receipt" (
  "count" bigint NOT NULL,
  CONSTRAINT "pk_receipt" PRIMARY KEY ("count")
);

ALTER TABLE "orders"."place_order_lines" ADD CONSTRAINT "fk_place_order_lines_place_order" FOREIGN KEY ("place_order_customer_id") REFERENCES "orders"."place_order" ("customer_id") ON DELETE CASCADE;
ALTER TABLE "orders"."order_lines" ADD CONSTRAINT "fk_order_lines_order" FOREIGN KEY ("order_id") REFERENCES "orders"."order" ("id") ON DELETE CASCADE;
