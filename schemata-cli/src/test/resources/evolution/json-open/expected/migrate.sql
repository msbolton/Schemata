-- migrate/s.sql
BEGIN;

-- SCH2701: s.Audit.actor: DROP COLUMN "actor" loses every value the column holds
ALTER TABLE "s"."audit" DROP COLUMN "actor";

COMMIT;
