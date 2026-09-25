CREATE SCHEMA IF NOT EXISTS "alpha";

CREATE TABLE "alpha"."a" (
  "id" uuid NOT NULL,
  "b_id" uuid NOT NULL,
  CONSTRAINT "pk_a" PRIMARY KEY ("id")
);
