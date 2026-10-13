-- migrate/s.sql
BEGIN;

CREATE TABLE "s"."order_tags" (
  "order_id" uuid NOT NULL,
  "position" integer NOT NULL,
  "value" text NOT NULL,
  CONSTRAINT "pk_order_tags" PRIMARY KEY ("order_id", "position")
);
ALTER TABLE "s"."order_tags" ADD CONSTRAINT "fk_order_tags_order" FOREIGN KEY ("order_id") REFERENCES "s"."order" ("id") ON DELETE CASCADE;
-- SCH2701: s.Order.tags: DROP COLUMN "tags" loses every value the column holds
ALTER TABLE "s"."order" DROP COLUMN "tags";

COMMIT;
