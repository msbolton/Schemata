CREATE SCHEMA IF NOT EXISTS "strings";

CREATE TABLE "strings"."strings" (
  "id" uuid NOT NULL,
  "line_feed" text NOT NULL DEFAULT 'a
b',
  "tab" text NOT NULL DEFAULT 't	t',
  "carriage_return" text NOT NULL DEFAULT 'rr',
  "quote" text NOT NULL DEFAULT 'q"q',
  "emoji" text NOT NULL DEFAULT '😀',
  "code" text NOT NULL,
  CONSTRAINT "pk_strings" PRIMARY KEY ("id"),
  CONSTRAINT "ck_strings_code_pattern" CHECK ("code" ~ '^"[A-Z]{2}\d{4}"$')
);

COMMENT ON TABLE "strings"."strings" IS 'String values that need escapes in every target.';
