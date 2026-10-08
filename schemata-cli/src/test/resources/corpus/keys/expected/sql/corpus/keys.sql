CREATE SCHEMA IF NOT EXISTS "keys";

CREATE TABLE "keys"."account" (
  "id" uuid NOT NULL,
  "email" varchar(254) NOT NULL,
  "created" timestamptz NOT NULL,
  CONSTRAINT "pk_account" PRIMARY KEY ("id"),
  CONSTRAINT "uq_account_email" UNIQUE ("email")
);

CREATE TABLE "keys"."plan" (
  "tenant_id" uuid NOT NULL,
  "code" varchar(32) NOT NULL,
  "name" text NOT NULL,
  CONSTRAINT "pk_plan" PRIMARY KEY ("tenant_id", "code")
);

CREATE TABLE "keys"."seat" (
  "id" uuid NOT NULL,
  "account_id" uuid NOT NULL,
  "number" integer NOT NULL,
  "holder_id" uuid,
  "row" varchar(4) NOT NULL,
  CONSTRAINT "pk_seat" PRIMARY KEY ("id"),
  CONSTRAINT "uq_seat_account_number" UNIQUE ("account_id", "number")
);

CREATE INDEX "ix_account_created" ON "keys"."account" ("created");
CREATE INDEX "ix_seat_row_number" ON "keys"."seat" ("row", "number");

ALTER TABLE "keys"."seat" ADD CONSTRAINT "fk_seat_account" FOREIGN KEY ("account_id") REFERENCES "keys"."account" ("id") ON DELETE CASCADE;
ALTER TABLE "keys"."seat" ADD CONSTRAINT "fk_seat_holder" FOREIGN KEY ("holder_id") REFERENCES "keys"."account" ("id") ON DELETE SET NULL;
