-- migrate/b.sql
BEGIN;

CREATE SCHEMA IF NOT EXISTS "crm";
ALTER TABLE "b"."customer" SET SCHEMA "crm";
DROP SCHEMA "b";

COMMIT;
