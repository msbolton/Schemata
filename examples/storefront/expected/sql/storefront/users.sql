CREATE SCHEMA IF NOT EXISTS "users";

CREATE TABLE "users"."profile" (
  "id" uuid NOT NULL,
  "email" text NOT NULL,
  "name" varchar(100) NOT NULL,
  "phone" varchar(32),
  "marketing" boolean NOT NULL DEFAULT false,
  "created_at" timestamptz NOT NULL,
  "etag" varchar(64) NOT NULL,
  CONSTRAINT "pk_profile" PRIMARY KEY ("id"),
  CONSTRAINT "uq_profile_email" UNIQUE ("email"),
  CONSTRAINT "ck_profile_email_max" CHECK (char_length("email") <= 254),
  CONSTRAINT "ck_profile_email_pattern" CHECK ("email" ~ '^[^@]+@[^@]+$')
);

CREATE TABLE "users"."update_profile" (
  "if_match" varchar(64) NOT NULL,
  "name" varchar(100),
  "phone" varchar(32),
  "marketing" boolean,
  CONSTRAINT "pk_update_profile" PRIMARY KEY ("if_match")
);

CREATE TABLE "users"."address" (
  "id" uuid NOT NULL,
  "kind" text NOT NULL,
  "name" varchar(100) NOT NULL,
  "line1" varchar(200) NOT NULL,
  "line2" varchar(200),
  "city" varchar(100) NOT NULL,
  "region" varchar(100),
  "postal_code" varchar(20) NOT NULL,
  "country" text NOT NULL,
  "is_default" boolean NOT NULL DEFAULT false,
  CONSTRAINT "pk_address" PRIMARY KEY ("id"),
  CONSTRAINT "ck_address_kind_enum" CHECK ("kind" IN ('shipping', 'billing')),
  CONSTRAINT "ck_address_country_min" CHECK (char_length("country") >= 2),
  CONSTRAINT "ck_address_country_max" CHECK (char_length("country") <= 2),
  CONSTRAINT "ck_address_country_pattern" CHECK ("country" ~ '^[A-Z]{2}$')
);

CREATE TABLE "users"."address_id" (
  "id" uuid NOT NULL,
  CONSTRAINT "pk_address_id" PRIMARY KEY ("id")
);

CREATE TABLE "users"."address_book" (
  "etag" varchar(64) NOT NULL,
  CONSTRAINT "pk_address_book" PRIMARY KEY ("etag")
);

CREATE TABLE "users"."address_book_items" (
  "address_book_etag" varchar(64) NOT NULL,
  "position" integer NOT NULL,
  "id" uuid NOT NULL,
  "kind" text NOT NULL,
  "name" varchar(100) NOT NULL,
  "line1" varchar(200) NOT NULL,
  "line2" varchar(200),
  "city" varchar(100) NOT NULL,
  "region" varchar(100),
  "postal_code" varchar(20) NOT NULL,
  "country" text NOT NULL,
  "is_default" boolean NOT NULL DEFAULT false,
  CONSTRAINT "pk_address_book_items" PRIMARY KEY ("address_book_etag", "position"),
  CONSTRAINT "ck_address_book_items_kind_enum" CHECK ("kind" IN ('shipping', 'billing')),
  CONSTRAINT "ck_address_book_items_country_min" CHECK (char_length("country") >= 2),
  CONSTRAINT "ck_address_book_items_country_max" CHECK (char_length("country") <= 2),
  CONSTRAINT "ck_address_book_items_country_pattern" CHECK ("country" ~ '^[A-Z]{2}$')
);

ALTER TABLE "users"."address_book_items" ADD CONSTRAINT "fk_address_book_items_address_book" FOREIGN KEY ("address_book_etag") REFERENCES "users"."address_book" ("etag") ON DELETE CASCADE;
ALTER TABLE "orders"."order" ADD CONSTRAINT "fk_order_customer" FOREIGN KEY ("customer_id") REFERENCES "users"."profile" ("id");
ALTER TABLE "orders"."checkout" ADD CONSTRAINT "fk_checkout_shipping_address" FOREIGN KEY ("shipping_address_id") REFERENCES "users"."address" ("id");
ALTER TABLE "orders"."order_page_items" ADD CONSTRAINT "fk_order_page_items_customer" FOREIGN KEY ("customer_id") REFERENCES "users"."profile" ("id");
ALTER TABLE "reviews"."review" ADD CONSTRAINT "fk_review_author" FOREIGN KEY ("author_id") REFERENCES "users"."profile" ("id");
ALTER TABLE "reviews"."review_page_items" ADD CONSTRAINT "fk_review_page_items_author" FOREIGN KEY ("author_id") REFERENCES "users"."profile" ("id");

COMMENT ON TABLE "users"."profile" IS 'The signed-in customer.';
COMMENT ON COLUMN "users"."profile"."etag" IS 'The version of the profile, for a later conditional update.';
COMMENT ON TABLE "users"."update_profile" IS 'The parts of the profile to change; a field left out keeps its value.';
COMMENT ON COLUMN "users"."update_profile"."if_match" IS 'The `ETag` the profile was read with; the update fails with 412 when it changed since.';
COMMENT ON TABLE "users"."address" IS 'A postal address in the address book.';
COMMENT ON COLUMN "users"."address"."country" IS 'A two-letter ISO 3166-1 country code.';
COMMENT ON TABLE "users"."address_book" IS 'Every address of the customer, default addresses first.';
COMMENT ON TABLE "users"."address_book_items" IS 'A postal address in the address book.';
COMMENT ON COLUMN "users"."address_book_items"."country" IS 'A two-letter ISO 3166-1 country code.';
