CREATE SCHEMA IF NOT EXISTS "reviews";

CREATE TABLE "reviews"."review" (
  "id" uuid NOT NULL,
  "product_id" uuid NOT NULL,
  "author_id" uuid NOT NULL,
  "rating" integer NOT NULL,
  "title" varchar(120) NOT NULL,
  "body" varchar(5000),
  "verified" boolean NOT NULL DEFAULT false,
  "created_at" timestamptz NOT NULL,
  CONSTRAINT "pk_review" PRIMARY KEY ("id"),
  CONSTRAINT "ck_review_rating_min" CHECK ("rating" >= 1),
  CONSTRAINT "ck_review_rating_max" CHECK ("rating" <= 5)
);

CREATE TABLE "reviews"."list_reviews" (
  "product_id" uuid NOT NULL,
  "rating" integer,
  "cursor" varchar(200),
  "limit" integer NOT NULL DEFAULT 20,
  CONSTRAINT "pk_list_reviews" PRIMARY KEY ("product_id"),
  CONSTRAINT "ck_list_reviews_rating_min" CHECK ("rating" >= 1),
  CONSTRAINT "ck_list_reviews_rating_max" CHECK ("rating" <= 5),
  CONSTRAINT "ck_list_reviews_limit_min" CHECK ("limit" >= 1),
  CONSTRAINT "ck_list_reviews_limit_max" CHECK ("limit" <= 100)
);

CREATE TABLE "reviews"."review_page" (
  "etag" varchar(64) NOT NULL,
  "page_next_cursor" varchar(200),
  "page_total" bigint NOT NULL,
  CONSTRAINT "pk_review_page" PRIMARY KEY ("etag"),
  CONSTRAINT "ck_review_page_page_total_min" CHECK ("page_total" >= 0)
);

CREATE TABLE "reviews"."review_page_items" (
  "review_page_etag" varchar(64) NOT NULL,
  "position" integer NOT NULL,
  "id" uuid NOT NULL,
  "product_id" uuid NOT NULL,
  "author_id" uuid NOT NULL,
  "rating" integer NOT NULL,
  "title" varchar(120) NOT NULL,
  "body" varchar(5000),
  "verified" boolean NOT NULL DEFAULT false,
  "created_at" timestamptz NOT NULL,
  CONSTRAINT "pk_review_page_items" PRIMARY KEY ("review_page_etag", "position"),
  CONSTRAINT "ck_review_page_items_rating_min" CHECK ("rating" >= 1),
  CONSTRAINT "ck_review_page_items_rating_max" CHECK ("rating" <= 5)
);

CREATE TABLE "reviews"."write_review" (
  "product_id" uuid NOT NULL,
  "rating" integer NOT NULL,
  "title" varchar(120) NOT NULL,
  "body" varchar(5000),
  CONSTRAINT "pk_write_review" PRIMARY KEY ("product_id"),
  CONSTRAINT "ck_write_review_rating_min" CHECK ("rating" >= 1),
  CONSTRAINT "ck_write_review_rating_max" CHECK ("rating" <= 5)
);

CREATE TABLE "reviews"."review_id" (
  "id" uuid NOT NULL,
  CONSTRAINT "pk_review_id" PRIMARY KEY ("id")
);

ALTER TABLE "reviews"."review" ADD CONSTRAINT "fk_review_product" FOREIGN KEY ("product_id") REFERENCES "catalog"."product" ("id");
ALTER TABLE "reviews"."review_page_items" ADD CONSTRAINT "fk_review_page_items_review_page" FOREIGN KEY ("review_page_etag") REFERENCES "reviews"."review_page" ("etag") ON DELETE CASCADE;
ALTER TABLE "reviews"."review_page_items" ADD CONSTRAINT "fk_review_page_items_product" FOREIGN KEY ("product_id") REFERENCES "catalog"."product" ("id");

COMMENT ON TABLE "reviews"."review" IS 'A customer''s review of a product.';
COMMENT ON COLUMN "reviews"."review"."verified" IS 'Whether the author bought the product here.';
COMMENT ON TABLE "reviews"."list_reviews" IS 'Which reviews of a product to list, newest first.';
COMMENT ON TABLE "reviews"."review_page" IS 'One page of a product''s reviews.';
COMMENT ON COLUMN "reviews"."review_page"."page_next_cursor" IS 'The cursor of the next page; absent on the last one.';
COMMENT ON COLUMN "reviews"."review_page"."page_total" IS 'How many items the listing holds in all.';
COMMENT ON TABLE "reviews"."review_page_items" IS 'A customer''s review of a product.';
COMMENT ON COLUMN "reviews"."review_page_items"."verified" IS 'Whether the author bought the product here.';
COMMENT ON TABLE "reviews"."write_review" IS 'A new review of a product.';
