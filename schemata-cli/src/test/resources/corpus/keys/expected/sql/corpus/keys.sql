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

CREATE INDEX "ix_account_created" ON "keys"."account" ("created");
