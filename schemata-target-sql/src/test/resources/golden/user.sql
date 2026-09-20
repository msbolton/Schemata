CREATE SCHEMA IF NOT EXISTS "orders";

CREATE TABLE "orders"."user" (
  "id" uuid NOT NULL,
  "email" text,
  "name" text NOT NULL,
  "age" integer NOT NULL
);
