-- migrate/s.sql
BEGIN;

ALTER TABLE "s"."customer" RENAME COLUMN "name" TO "name__schemata_tmp";
ALTER TABLE "s"."customer" RENAME COLUMN "age" TO "name";
ALTER TABLE "s"."customer" RENAME COLUMN "name__schemata_tmp" TO "age";
-- SCH2701: s.Customer.age: ALTER COLUMN "age" TYPE integer loses values that do not fit integer
ALTER TABLE "s"."customer" ALTER COLUMN "age" TYPE integer USING "age"::integer;
-- SCH2701: s.Customer.name: ALTER COLUMN "name" TYPE varchar(100) loses values that do not fit varchar(100)
ALTER TABLE "s"."customer" ALTER COLUMN "name" TYPE varchar(100) USING "name"::varchar(100);

COMMIT;
