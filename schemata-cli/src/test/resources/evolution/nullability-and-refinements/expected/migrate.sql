-- migrate/s.sql
BEGIN;

ALTER TABLE "s"."account" DROP CONSTRAINT "pk_account" CASCADE;
ALTER TABLE "s"."account" DROP CONSTRAINT "ck_account_score_max";
-- SCH2701: s.Account.code: ALTER COLUMN "code" TYPE varchar(5) loses values that do not fit varchar(5) (the cast fails or truncates)
ALTER TABLE "s"."account" ALTER COLUMN "code" TYPE varchar(5) USING "code"::varchar(5);
ALTER TABLE "s"."account" ALTER COLUMN "tier" SET DEFAULT 1;
ALTER TABLE "s"."account" ALTER COLUMN "nickname" SET NOT NULL;
UPDATE "s"."account" SET "tier" = 1 WHERE "tier" IS NULL;
ALTER TABLE "s"."account" ALTER COLUMN "tier" SET NOT NULL;
ALTER TABLE "s"."account" ADD CONSTRAINT "pk_account" PRIMARY KEY ("code");
ALTER TABLE "s"."account" ADD CONSTRAINT "ck_account_score_max" CHECK ("score" <= 1000);

COMMIT;
