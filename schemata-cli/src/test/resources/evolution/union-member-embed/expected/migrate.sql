-- migrate/s.sql
BEGIN;

ALTER TABLE "s"."account" DROP CONSTRAINT "ck_account_party_customer";
-- SCH2701: s.Account.party.#1: DROP COLUMN "party_customer_id" loses every value the column holds
ALTER TABLE "s"."account" DROP COLUMN "party_customer_id";
ALTER TABLE "s"."account" ADD COLUMN "party_customer_id" uuid;
ALTER TABLE "s"."account" ADD COLUMN "party_customer_name" varchar(64);
ALTER TABLE "s"."account" DROP CONSTRAINT IF EXISTS "fk_account_party_customer";
ALTER TABLE "s"."account" ADD CONSTRAINT "ck_account_party_customer" CHECK (("party_kind" <> 'customer') OR ("party_customer_id" IS NOT NULL AND "party_customer_name" IS NOT NULL));

COMMIT;
