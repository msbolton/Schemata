-- migrate/s.sql
BEGIN;

COMMENT ON TABLE "s"."customer" IS 'A person or company that buys.';
COMMENT ON COLUMN "s"."customer"."name" IS NULL;
COMMENT ON COLUMN "s"."customer"."note" IS 'Free text from support.';

COMMIT;
