-- migrate/s.sql
BEGIN;

ALTER TABLE "s"."alpha" RENAME TO "alpha__schemata_tmp";
ALTER TABLE "s"."beta" RENAME TO "alpha";
ALTER TABLE "s"."alpha__schemata_tmp" RENAME TO "beta";
ALTER TABLE "s"."beta" RENAME CONSTRAINT "pk_alpha" TO "pk_alpha__schemata_tmp";
ALTER TABLE "s"."alpha" RENAME CONSTRAINT "pk_beta" TO "pk_alpha";
ALTER TABLE "s"."beta" RENAME CONSTRAINT "pk_alpha__schemata_tmp" TO "pk_beta";
ALTER TABLE "s"."beta" RENAME CONSTRAINT "uq_alpha_code" TO "uq_alpha_code__schemata_tmp";
ALTER TABLE "s"."alpha" RENAME CONSTRAINT "uq_beta_code" TO "uq_alpha_code";
ALTER TABLE "s"."beta" RENAME CONSTRAINT "uq_alpha_code__schemata_tmp" TO "uq_beta_code";
ALTER INDEX "s"."ix_alpha_label" RENAME TO "ix_alpha_label__schemata_tmp";
ALTER INDEX "s"."ix_beta_label" RENAME TO "ix_alpha_label";
ALTER INDEX "s"."ix_alpha_label__schemata_tmp" RENAME TO "ix_beta_label";

COMMIT;
