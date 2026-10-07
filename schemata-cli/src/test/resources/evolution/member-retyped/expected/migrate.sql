-- migrate/s.sql
BEGIN;

ALTER TABLE "s"."order" ADD COLUMN "payment_voucher_code" varchar(16);
ALTER TABLE "s"."order" DROP CONSTRAINT "ck_order_payment_kind";
ALTER TABLE "s"."order" DROP CONSTRAINT "ck_order_payment_card";
ALTER TABLE "s"."order" ADD CONSTRAINT "ck_order_payment_kind" CHECK ("payment_kind" IN ('voucher', 'string'));
ALTER TABLE "s"."order" ADD CONSTRAINT "ck_order_payment_voucher" CHECK (("payment_kind" <> 'voucher') OR ("payment_voucher_code" IS NOT NULL));
-- SCH2701: s.Order.payment.#1.last4: DROP COLUMN "payment_card_last4" loses every value the column holds
ALTER TABLE "s"."order" DROP COLUMN "payment_card_last4";

COMMIT;
