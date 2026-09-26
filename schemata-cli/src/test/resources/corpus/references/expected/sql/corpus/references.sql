CREATE SCHEMA IF NOT EXISTS "references";

CREATE TABLE "references"."account" (
  "id" uuid NOT NULL,
  "owner_tenant_id" uuid NOT NULL,
  "owner_code" varchar(8) NOT NULL,
  "parent_id" uuid,
  CONSTRAINT "pk_account" PRIMARY KEY ("id")
);

CREATE TABLE "references"."person" (
  "tenant_id" uuid NOT NULL,
  "code" varchar(8) NOT NULL,
  "name" text NOT NULL,
  CONSTRAINT "pk_person" PRIMARY KEY ("tenant_id", "code")
);

ALTER TABLE "references"."account" ADD CONSTRAINT "fk_account_owner" FOREIGN KEY ("owner_tenant_id", "owner_code") REFERENCES "references"."person" ("tenant_id", "code");
ALTER TABLE "references"."account" ADD CONSTRAINT "fk_account_parent" FOREIGN KEY ("parent_id") REFERENCES "references"."account" ("id");
