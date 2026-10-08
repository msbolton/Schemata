CREATE SCHEMA IF NOT EXISTS "contacts";

CREATE TABLE "contacts"."contact" (
  "id" bigint NOT NULL,
  "name" varchar(100) NOT NULL,
  "email" text NOT NULL,
  "age" integer,
  "kind" text NOT NULL DEFAULT 'personal',
  "born" date,
  "tags" text[] NOT NULL,  -- schemata: string[] { max 20 }
  CONSTRAINT "pk_contact" PRIMARY KEY ("id"),
  CONSTRAINT "ck_contact_email_max" CHECK (char_length("email") <= 254),
  CONSTRAINT "ck_contact_email_pattern" CHECK ("email" ~ '^[^@]+@[^@]+$'),
  CONSTRAINT "ck_contact_age_min" CHECK ("age" >= 0),
  CONSTRAINT "ck_contact_age_max" CHECK ("age" <= 150),
  CONSTRAINT "ck_contact_kind_enum" CHECK ("kind" IN ('personal', 'work'))
);

COMMENT ON TABLE "contacts"."contact" IS 'One person. Email and age are checked by Postgres; Protobuf carries them unchecked.';
