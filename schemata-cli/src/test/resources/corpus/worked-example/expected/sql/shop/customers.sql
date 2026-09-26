CREATE SCHEMA IF NOT EXISTS "customers";

CREATE TABLE "customers"."customer" (
  "id" uuid NOT NULL,
  "name" varchar(100) NOT NULL,
  CONSTRAINT "pk_customer" PRIMARY KEY ("id")
);
