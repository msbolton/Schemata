CREATE SCHEMA IF NOT EXISTS "catalog";

CREATE TABLE "catalog"."category" (
  "id" uuid NOT NULL,
  "slug" text NOT NULL,
  "name" varchar(100) NOT NULL,
  "parent_id" uuid,
  CONSTRAINT "pk_category" PRIMARY KEY ("id"),
  CONSTRAINT "uq_category_slug" UNIQUE ("slug"),
  CONSTRAINT "ck_category_slug_min" CHECK (char_length("slug") >= 1),
  CONSTRAINT "ck_category_slug_max" CHECK (char_length("slug") <= 100),
  CONSTRAINT "ck_category_slug_pattern" CHECK ("slug" ~ '^[a-z0-9]+(-[a-z0-9]+)*$')
);

CREATE TABLE "catalog"."category_list" (
  "etag" varchar(64) NOT NULL,
  CONSTRAINT "pk_category_list" PRIMARY KEY ("etag")
);

CREATE TABLE "catalog"."category_list_items" (
  "category_list_etag" varchar(64) NOT NULL,
  "position" integer NOT NULL,
  "id" uuid NOT NULL,
  "slug" text NOT NULL,
  "name" varchar(100) NOT NULL,
  "parent_id" uuid,
  CONSTRAINT "pk_category_list_items" PRIMARY KEY ("category_list_etag", "position"),
  CONSTRAINT "uq_category_list_items_slug" UNIQUE ("slug"),
  CONSTRAINT "ck_category_list_items_slug_min" CHECK (char_length("slug") >= 1),
  CONSTRAINT "ck_category_list_items_slug_max" CHECK (char_length("slug") <= 100),
  CONSTRAINT "ck_category_list_items_slug_pattern" CHECK ("slug" ~ '^[a-z0-9]+(-[a-z0-9]+)*$')
);

CREATE TABLE "catalog"."product" (
  "id" uuid NOT NULL,
  "slug" text NOT NULL,
  "name" varchar(200) NOT NULL,
  "description" varchar(5000),
  "category_id" uuid NOT NULL,
  "brand" varchar(100),
  "images" text[] NOT NULL,  -- schemata: string[] { maxItems 20, max 2048 }
  "rating" double precision,
  CONSTRAINT "pk_product" PRIMARY KEY ("id"),
  CONSTRAINT "uq_product_slug" UNIQUE ("slug"),
  CONSTRAINT "ck_product_slug_min" CHECK (char_length("slug") >= 1),
  CONSTRAINT "ck_product_slug_max" CHECK (char_length("slug") <= 100),
  CONSTRAINT "ck_product_slug_pattern" CHECK ("slug" ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
  CONSTRAINT "ck_product_rating_min" CHECK ("rating" >= 1),
  CONSTRAINT "ck_product_rating_max" CHECK ("rating" <= 5)
);

CREATE TABLE "catalog"."product_variants" (
  "product_id" uuid NOT NULL,
  "position" integer NOT NULL,
  "sku" varchar(64) NOT NULL,
  "size" varchar(20),
  "color" varchar(30),
  "price_amount" numeric(19, 4) NOT NULL,
  "price_currency" text NOT NULL,
  "availability" text NOT NULL,
  CONSTRAINT "pk_product_variants" PRIMARY KEY ("product_id", "position"),
  CONSTRAINT "ck_product_variants_price_currency_min" CHECK (char_length("price_currency") >= 3),
  CONSTRAINT "ck_product_variants_price_currency_max" CHECK (char_length("price_currency") <= 3),
  CONSTRAINT "ck_product_variants_price_currency_pattern" CHECK ("price_currency" ~ '^[A-Z]{3}$'),
  CONSTRAINT "ck_product_variants_availability_enum" CHECK ("availability" IN ('in_stock', 'backorder', 'out_of_stock'))
);

CREATE TABLE "catalog"."product_slug" (
  "slug" text NOT NULL,
  CONSTRAINT "pk_product_slug" PRIMARY KEY ("slug"),
  CONSTRAINT "ck_product_slug_slug_min" CHECK (char_length("slug") >= 1),
  CONSTRAINT "ck_product_slug_slug_max" CHECK (char_length("slug") <= 100),
  CONSTRAINT "ck_product_slug_slug_pattern" CHECK ("slug" ~ '^[a-z0-9]+(-[a-z0-9]+)*$')
);

CREATE TABLE "catalog"."product_page" (
  "etag" varchar(64) NOT NULL,
  "page_next_cursor" varchar(200),
  "page_total" bigint NOT NULL,
  "query_q" varchar(200),
  "query_category" text,
  "query_brand" text[],  -- schemata: string[]? { maxItems 10, max 100 }
  "query_min_price" numeric(19, 4),
  "query_max_price" numeric(19, 4),
  "query_availability" text,
  "query_sort" text NOT NULL DEFAULT 'relevance',
  "query_cursor" varchar(200),
  "query_limit" integer NOT NULL DEFAULT 20,
  CONSTRAINT "pk_product_page" PRIMARY KEY ("etag"),
  CONSTRAINT "ck_product_page_page_total_min" CHECK ("page_total" >= 0),
  CONSTRAINT "ck_product_page_query_category_min" CHECK (char_length("query_category") >= 1),
  CONSTRAINT "ck_product_page_query_category_max" CHECK (char_length("query_category") <= 100),
  CONSTRAINT "ck_product_page_query_category_pattern" CHECK ("query_category" ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
  CONSTRAINT "ck_product_page_query_availability_enum" CHECK ("query_availability" IN ('in_stock', 'backorder', 'out_of_stock')),
  CONSTRAINT "ck_product_page_query_sort_enum" CHECK ("query_sort" IN ('relevance', 'price_asc', 'price_desc', 'newest', 'rating')),
  CONSTRAINT "ck_product_page_query_limit_min" CHECK ("query_limit" >= 1),
  CONSTRAINT "ck_product_page_query_limit_max" CHECK ("query_limit" <= 100)
);

CREATE TABLE "catalog"."product_page_items" (
  "product_page_etag" varchar(64) NOT NULL,
  "position" integer NOT NULL,
  "id" uuid NOT NULL,
  "slug" text NOT NULL,
  "name" varchar(200) NOT NULL,
  "description" varchar(5000),
  "category_id" uuid NOT NULL,
  "brand" varchar(100),
  "images" text[] NOT NULL,  -- schemata: string[] { maxItems 20, max 2048 }
  "rating" double precision,
  CONSTRAINT "pk_product_page_items" PRIMARY KEY ("product_page_etag", "position"),
  CONSTRAINT "uq_product_page_items_slug" UNIQUE ("slug"),
  CONSTRAINT "ck_product_page_items_slug_min" CHECK (char_length("slug") >= 1),
  CONSTRAINT "ck_product_page_items_slug_max" CHECK (char_length("slug") <= 100),
  CONSTRAINT "ck_product_page_items_slug_pattern" CHECK ("slug" ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
  CONSTRAINT "ck_product_page_items_rating_min" CHECK ("rating" >= 1),
  CONSTRAINT "ck_product_page_items_rating_max" CHECK ("rating" <= 5)
);

CREATE TABLE "catalog"."product_page_items_variants" (
  "product_page_items_product_page_etag" varchar(64) NOT NULL,
  "product_page_items_position" integer NOT NULL,
  "position" integer NOT NULL,
  "sku" varchar(64) NOT NULL,
  "size" varchar(20),
  "color" varchar(30),
  "price_amount" numeric(19, 4) NOT NULL,
  "price_currency" text NOT NULL,
  "availability" text NOT NULL,
  CONSTRAINT "pk_product_page_items_variants" PRIMARY KEY ("product_page_items_product_page_etag", "product_page_items_position", "position"),
  CONSTRAINT "ck_product_page_items_variants_price_currency_min" CHECK (char_length("price_currency") >= 3),
  CONSTRAINT "ck_product_page_items_variants_price_currency_max" CHECK (char_length("price_currency") <= 3),
  CONSTRAINT "ck_product_page_items_variants_price_currency_pattern" CHECK ("price_currency" ~ '^[A-Z]{3}$'),
  CONSTRAINT "ck_product_page_items_variants_availability_enum" CHECK ("availability" IN ('in_stock', 'backorder', 'out_of_stock'))
);

ALTER TABLE "catalog"."category" ADD CONSTRAINT "fk_category_parent" FOREIGN KEY ("parent_id") REFERENCES "catalog"."category" ("id");
ALTER TABLE "catalog"."category_list_items" ADD CONSTRAINT "fk_category_list_items_category_list" FOREIGN KEY ("category_list_etag") REFERENCES "catalog"."category_list" ("etag") ON DELETE CASCADE;
ALTER TABLE "catalog"."category_list_items" ADD CONSTRAINT "fk_category_list_items_parent" FOREIGN KEY ("parent_id") REFERENCES "catalog"."category" ("id");
ALTER TABLE "catalog"."product" ADD CONSTRAINT "fk_product_category" FOREIGN KEY ("category_id") REFERENCES "catalog"."category" ("id");
ALTER TABLE "catalog"."product_variants" ADD CONSTRAINT "fk_product_variants_product" FOREIGN KEY ("product_id") REFERENCES "catalog"."product" ("id") ON DELETE CASCADE;
ALTER TABLE "catalog"."product_page_items" ADD CONSTRAINT "fk_product_page_items_product_page" FOREIGN KEY ("product_page_etag") REFERENCES "catalog"."product_page" ("etag") ON DELETE CASCADE;
ALTER TABLE "catalog"."product_page_items" ADD CONSTRAINT "fk_product_page_items_category" FOREIGN KEY ("category_id") REFERENCES "catalog"."category" ("id");
ALTER TABLE "catalog"."product_page_items_variants" ADD CONSTRAINT "fk_product_page_items_variants_product_page_items" FOREIGN KEY ("product_page_items_product_page_etag", "product_page_items_position") REFERENCES "catalog"."product_page_items" ("product_page_etag", "position") ON DELETE CASCADE;
ALTER TABLE "cart"."cart_lines" ADD CONSTRAINT "fk_cart_lines_product" FOREIGN KEY ("product_id") REFERENCES "catalog"."product" ("id");
ALTER TABLE "cart"."add_item" ADD CONSTRAINT "fk_add_item_product" FOREIGN KEY ("product_id") REFERENCES "catalog"."product" ("id");

COMMENT ON TABLE "catalog"."category" IS 'A category; categories nest under a parent.';
COMMENT ON TABLE "catalog"."category_list" IS 'Every category, parents before their children.';
COMMENT ON TABLE "catalog"."category_list_items" IS 'A category; categories nest under a parent.';
COMMENT ON TABLE "catalog"."product" IS 'A product and the variants it is sold in.';
COMMENT ON COLUMN "catalog"."product"."images" IS 'Image URLs, the first one the product''s main image.';
COMMENT ON COLUMN "catalog"."product"."rating" IS 'The average review rating, absent until the first review.';
COMMENT ON TABLE "catalog"."product_variants" IS 'One form of a product that can be bought, such as a size and a colour.';
COMMENT ON TABLE "catalog"."product_page" IS 'One page of a search.';
COMMENT ON COLUMN "catalog"."product_page"."page_next_cursor" IS 'The cursor of the next page; absent on the last one.';
COMMENT ON COLUMN "catalog"."product_page"."page_total" IS 'How many items the listing holds in all.';
COMMENT ON COLUMN "catalog"."product_page"."query_q" IS 'Words to look for in a product''s name and description.';
COMMENT ON COLUMN "catalog"."product_page"."query_category" IS 'A category''s slug; its subcategories match too.';
COMMENT ON COLUMN "catalog"."product_page"."query_brand" IS 'Any of these brands.';
COMMENT ON TABLE "catalog"."product_page_items" IS 'A product and the variants it is sold in.';
COMMENT ON COLUMN "catalog"."product_page_items"."images" IS 'Image URLs, the first one the product''s main image.';
COMMENT ON COLUMN "catalog"."product_page_items"."rating" IS 'The average review rating, absent until the first review.';
COMMENT ON TABLE "catalog"."product_page_items_variants" IS 'One form of a product that can be bought, such as a size and a colour.';
