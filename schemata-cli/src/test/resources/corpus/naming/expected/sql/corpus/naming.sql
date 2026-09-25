CREATE SCHEMA IF NOT EXISTS "naming_v1";

CREATE TABLE "naming_v1"."http_status_codes" (
  "code" integer NOT NULL,
  "reason_phrase" text NOT NULL,
  CONSTRAINT "pk_http_status_codes" PRIMARY KEY ("code")
);

CREATE TABLE "naming_v1"."user" (
  "id" uuid NOT NULL,
  "order" integer NOT NULL,
  "a_very_long_field_name_that_goes_well_beyond_the_sixty__b7ff5eb" boolean NOT NULL,
  CONSTRAINT "pk_user" PRIMARY KEY ("id")
);

CREATE TABLE "naming_v1"."io_error" (
  "id" uuid NOT NULL,
  CONSTRAINT "pk_io_error" PRIMARY KEY ("id")
);
