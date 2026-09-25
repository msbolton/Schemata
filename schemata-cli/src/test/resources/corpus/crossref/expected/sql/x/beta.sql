CREATE SCHEMA IF NOT EXISTS "beta";

CREATE TABLE "beta"."b" (
  "id" uuid NOT NULL,
  CONSTRAINT "pk_b" PRIMARY KEY ("id")
);

ALTER TABLE "alpha"."a" ADD CONSTRAINT "fk_a_b" FOREIGN KEY ("b_id") REFERENCES "beta"."b" ("id");
