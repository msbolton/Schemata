CREATE SCHEMA IF NOT EXISTS "types";

CREATE TABLE "types"."everything" (
  "id" uuid NOT NULL,
  "flag" boolean NOT NULL DEFAULT true,
  "small" integer NOT NULL DEFAULT 3,
  "big" bigint NOT NULL,
  "ratio" real NOT NULL,
  "exact" double precision NOT NULL,
  "money" numeric(19, 4) NOT NULL DEFAULT 1.25,
  "name" text NOT NULL,
  "email" text NOT NULL,
  "code" varchar(4),
  "note" text NOT NULL DEFAULT 'n/a',
  "blob" bytea NOT NULL,
  "day" date NOT NULL,
  "tod" time NOT NULL,
  "at" timestamptz NOT NULL,
  "took" interval NOT NULL,
  "status" text NOT NULL DEFAULT 'pending',
  "legacy" varchar(36),  -- schemata: uuid?
  CONSTRAINT "pk_everything" PRIMARY KEY ("id"),
  CONSTRAINT "ck_everything_small_min" CHECK ("small" >= 0),
  CONSTRAINT "ck_everything_small_max" CHECK ("small" <= 100),
  CONSTRAINT "ck_everything_big_min" CHECK ("big" >= 1),
  CONSTRAINT "ck_everything_exact_max" CHECK ("exact" <= 1.5),
  CONSTRAINT "ck_everything_name_min" CHECK (char_length("name") >= 2),
  CONSTRAINT "ck_everything_name_max" CHECK (char_length("name") <= 8),
  CONSTRAINT "ck_everything_email_pattern" CHECK ("email" ~ '^[^@]+@[^@]+$'),
  CONSTRAINT "ck_everything_blob_max" CHECK (octet_length("blob") <= 1024),
  CONSTRAINT "ck_everything_status_enum" CHECK ("status" IN ('pending', 'paid'))
);
