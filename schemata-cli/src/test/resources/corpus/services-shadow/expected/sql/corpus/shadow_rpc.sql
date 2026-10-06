CREATE SCHEMA IF NOT EXISTS "shadow_rpc";

CREATE TABLE "shadow_rpc"."place_order" (
  "customer_id" bigint NOT NULL,
  CONSTRAINT "pk_place_order" PRIMARY KEY ("customer_id")
);

CREATE TABLE "shadow_rpc"."order" (
  "id" bigint NOT NULL,
  "note" text NOT NULL,
  CONSTRAINT "pk_order" PRIMARY KEY ("id")
);

CREATE TABLE "shadow_rpc"."line" (
  "sku" text NOT NULL,
  "quantity" integer NOT NULL,
  CONSTRAINT "pk_line" PRIMARY KEY ("sku")
);

CREATE TABLE "shadow_rpc"."order_id" (
  "id" bigint NOT NULL,
  CONSTRAINT "pk_order_id" PRIMARY KEY ("id")
);

CREATE TABLE "shadow_rpc"."receipt" (
  "count" bigint NOT NULL,
  CONSTRAINT "pk_receipt" PRIMARY KEY ("count")
);
