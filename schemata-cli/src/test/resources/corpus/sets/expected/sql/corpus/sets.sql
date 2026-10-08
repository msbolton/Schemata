CREATE SCHEMA IF NOT EXISTS "sets";

CREATE TABLE "sets"."tag" (
  "id" uuid NOT NULL,
  "label" varchar(40) NOT NULL,
  CONSTRAINT "pk_tag" PRIMARY KEY ("id")
);

CREATE TABLE "sets"."post" (
  "id" uuid NOT NULL,
  CONSTRAINT "pk_post" PRIMARY KEY ("id")
);

CREATE TABLE "sets"."post_tags" (
  "post_id" uuid NOT NULL,
  "position" integer NOT NULL,
  "value_id" uuid NOT NULL,
  CONSTRAINT "pk_post_tags" PRIMARY KEY ("post_id", "position"),
  CONSTRAINT "uq_post_tags_value" UNIQUE ("post_id", "value_id")
);

CREATE TABLE "sets"."post_seen" (
  "post_id" uuid NOT NULL,
  "position" integer NOT NULL,
  "value_id" uuid NOT NULL,
  CONSTRAINT "pk_post_seen" PRIMARY KEY ("post_id", "position")
);

ALTER TABLE "sets"."post_tags" ADD CONSTRAINT "fk_post_tags_post" FOREIGN KEY ("post_id") REFERENCES "sets"."post" ("id") ON DELETE CASCADE;
ALTER TABLE "sets"."post_tags" ADD CONSTRAINT "fk_post_tags_value" FOREIGN KEY ("value_id") REFERENCES "sets"."tag" ("id");
ALTER TABLE "sets"."post_seen" ADD CONSTRAINT "fk_post_seen_post" FOREIGN KEY ("post_id") REFERENCES "sets"."post" ("id") ON DELETE CASCADE;
ALTER TABLE "sets"."post_seen" ADD CONSTRAINT "fk_post_seen_value" FOREIGN KEY ("value_id") REFERENCES "sets"."tag" ("id");
