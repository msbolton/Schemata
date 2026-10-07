-- migrate/s.sql
BEGIN;

ALTER TABLE "s"."order" ADD COLUMN "payment_wallet_provider" varchar(32);
ALTER TABLE "s"."order" ADD COLUMN "payment_voucher_code" varchar(16);
ALTER TABLE "s"."order" DROP CONSTRAINT "ck_order_payment_kind";
ALTER TABLE "s"."order" DROP CONSTRAINT "ck_order_payment_bank_transfer";
ALTER TABLE "s"."order" ADD CONSTRAINT "ck_order_payment_kind" CHECK ("payment_kind" IN ('card', 'wallet', 'voucher'));
ALTER TABLE "s"."order" ADD CONSTRAINT "ck_order_payment_wallet" CHECK (("payment_kind" <> 'wallet') OR ("payment_wallet_provider" IS NOT NULL));
ALTER TABLE "s"."order" ADD CONSTRAINT "ck_order_payment_voucher" CHECK (("payment_kind" <> 'voucher') OR ("payment_voucher_code" IS NOT NULL));
-- SCH2701: s.Order.payment.#2.iban: DROP COLUMN "payment_bank_transfer_iban" loses every value the column holds
ALTER TABLE "s"."order" DROP COLUMN "payment_bank_transfer_iban";

COMMIT;
