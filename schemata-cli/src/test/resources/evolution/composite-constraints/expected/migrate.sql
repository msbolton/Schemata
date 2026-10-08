-- migrate/s.sql
BEGIN;

DROP INDEX "s"."ix_membership_region_team";
ALTER TABLE "s"."membership" ADD CONSTRAINT "uq_membership_member_team" UNIQUE ("member", "team");

COMMIT;
