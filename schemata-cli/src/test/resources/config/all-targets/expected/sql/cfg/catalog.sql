CREATE SCHEMA IF NOT EXISTS "cfg_catalog";

CREATE TABLE "cfg_catalog"."product_id" (
  "id" bigint NOT NULL,
  CONSTRAINT "pk_product_id" PRIMARY KEY ("id")
);

CREATE TABLE "cfg_catalog"."product" (
  "id" bigint NOT NULL,
  "name" varchar(100) NOT NULL,
  CONSTRAINT "pk_product" PRIMARY KEY ("id")
);
