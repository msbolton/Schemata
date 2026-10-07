-- migrate/s.sql
BEGIN;

ALTER TABLE "s"."item" DROP CONSTRAINT "ck_item_size_enum";
UPDATE "s"."item" SET "size" = CASE "size" WHEN 'small' THEN 'large' WHEN 'large' THEN 'small' END WHERE "size" IN ('small', 'large');
ALTER TABLE "s"."item" ADD CONSTRAINT "ck_item_size_enum" CHECK ("size" IN ('large', 'small', 'medium'));

COMMIT;
