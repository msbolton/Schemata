CREATE SCHEMA IF NOT EXISTS "embedding";

CREATE TABLE "embedding"."site" (
  "id" uuid NOT NULL,
  "office_street" varchar(200) NOT NULL,
  "office_zip" text NOT NULL,
  "home_street" varchar(200),
  "home_zip" text,
  "legacy" jsonb NOT NULL,  -- schemata: Address
  CONSTRAINT "pk_site" PRIMARY KEY ("id"),
  CONSTRAINT "ck_site_office_zip_min" CHECK (char_length("office_zip") >= 5),
  CONSTRAINT "ck_site_office_zip_max" CHECK (char_length("office_zip") <= 5),
  CONSTRAINT "ck_site_home_zip_min" CHECK (char_length("home_zip") >= 5),
  CONSTRAINT "ck_site_home_zip_max" CHECK (char_length("home_zip") <= 5),
  CONSTRAINT "ck_site_home_present" CHECK ((("home_street" IS NULL AND "home_zip" IS NULL) OR ("home_street" IS NOT NULL AND "home_zip" IS NOT NULL)))
);
