CREATE SCHEMA IF NOT EXISTS "payments";

CREATE TABLE "payments"."payment" (
  "id" uuid NOT NULL,
  "order_id" uuid NOT NULL,
  "amount_amount" numeric(19, 4) NOT NULL,
  "amount_currency" text NOT NULL,
  "method_kind" text NOT NULL,
  "method_card_token" varchar(200),
  "method_card_brand" varchar(32),
  "method_card_last4" text,
  "method_wallet_kind" text,
  "method_wallet_token" varchar(200),
  "method_bank_transfer_iban" varchar(34),
  "status" text NOT NULL,
  "failure" varchar(500),
  "created_at" timestamptz NOT NULL,
  CONSTRAINT "pk_payment" PRIMARY KEY ("id"),
  CONSTRAINT "ck_payment_amount_currency_min" CHECK (char_length("amount_currency") >= 3),
  CONSTRAINT "ck_payment_amount_currency_max" CHECK (char_length("amount_currency") <= 3),
  CONSTRAINT "ck_payment_amount_currency_pattern" CHECK ("amount_currency" ~ '^[A-Z]{3}$'),
  CONSTRAINT "ck_payment_method_kind" CHECK ("method_kind" IN ('card', 'wallet', 'bank_transfer')),
  CONSTRAINT "ck_payment_method_card_last4_min" CHECK (char_length("method_card_last4") >= 4),
  CONSTRAINT "ck_payment_method_card_last4_max" CHECK (char_length("method_card_last4") <= 4),
  CONSTRAINT "ck_payment_method_card_last4_pattern" CHECK ("method_card_last4" ~ '^[0-9]{4}$'),
  CONSTRAINT "ck_payment_method_card" CHECK (("method_kind" <> 'card') OR ("method_card_token" IS NOT NULL AND "method_card_brand" IS NOT NULL AND "method_card_last4" IS NOT NULL)),
  CONSTRAINT "ck_payment_method_wallet_kind_enum" CHECK ("method_wallet_kind" IN ('apple_pay', 'google_pay', 'paypal')),
  CONSTRAINT "ck_payment_method_wallet" CHECK (("method_kind" <> 'wallet') OR ("method_wallet_kind" IS NOT NULL AND "method_wallet_token" IS NOT NULL)),
  CONSTRAINT "ck_payment_method_bank_transfer" CHECK (("method_kind" <> 'bank_transfer') OR ("method_bank_transfer_iban" IS NOT NULL)),
  CONSTRAINT "ck_payment_status_enum" CHECK ("status" IN ('pending', 'authorized', 'captured', 'failed', 'refunded'))
);

CREATE TABLE "payments"."pay" (
  "idempotency_key" uuid NOT NULL,
  "order_id" uuid NOT NULL,
  "method_kind" text NOT NULL,
  "method_card_token" varchar(200),
  "method_card_brand" varchar(32),
  "method_card_last4" text,
  "method_wallet_kind" text,
  "method_wallet_token" varchar(200),
  "method_bank_transfer_iban" varchar(34),
  CONSTRAINT "pk_pay" PRIMARY KEY ("idempotency_key"),
  CONSTRAINT "ck_pay_method_kind" CHECK ("method_kind" IN ('card', 'wallet', 'bank_transfer')),
  CONSTRAINT "ck_pay_method_card_last4_min" CHECK (char_length("method_card_last4") >= 4),
  CONSTRAINT "ck_pay_method_card_last4_max" CHECK (char_length("method_card_last4") <= 4),
  CONSTRAINT "ck_pay_method_card_last4_pattern" CHECK ("method_card_last4" ~ '^[0-9]{4}$'),
  CONSTRAINT "ck_pay_method_card" CHECK (("method_kind" <> 'card') OR ("method_card_token" IS NOT NULL AND "method_card_brand" IS NOT NULL AND "method_card_last4" IS NOT NULL)),
  CONSTRAINT "ck_pay_method_wallet_kind_enum" CHECK ("method_wallet_kind" IN ('apple_pay', 'google_pay', 'paypal')),
  CONSTRAINT "ck_pay_method_wallet" CHECK (("method_kind" <> 'wallet') OR ("method_wallet_kind" IS NOT NULL AND "method_wallet_token" IS NOT NULL)),
  CONSTRAINT "ck_pay_method_bank_transfer" CHECK (("method_kind" <> 'bank_transfer') OR ("method_bank_transfer_iban" IS NOT NULL))
);

CREATE TABLE "payments"."payment_id" (
  "id" uuid NOT NULL,
  CONSTRAINT "pk_payment_id" PRIMARY KEY ("id")
);

CREATE TABLE "payments"."refund" (
  "id" uuid NOT NULL,
  "amount_amount" numeric(19, 4),
  "amount_currency" text,
  "reason" varchar(500),
  CONSTRAINT "pk_refund" PRIMARY KEY ("id"),
  CONSTRAINT "ck_refund_amount_currency_min" CHECK (char_length("amount_currency") >= 3),
  CONSTRAINT "ck_refund_amount_currency_max" CHECK (char_length("amount_currency") <= 3),
  CONSTRAINT "ck_refund_amount_currency_pattern" CHECK ("amount_currency" ~ '^[A-Z]{3}$'),
  CONSTRAINT "ck_refund_amount_present" CHECK ((("amount_amount" IS NULL AND "amount_currency" IS NULL) OR ("amount_amount" IS NOT NULL AND "amount_currency" IS NOT NULL)))
);

CREATE TABLE "payments"."notice" (
  "event_id" varchar(100) NOT NULL,
  "kind" text NOT NULL,
  "payment_id" uuid NOT NULL,
  "occurred_at" timestamptz NOT NULL,
  "signature" varchar(512) NOT NULL,
  CONSTRAINT "pk_notice" PRIMARY KEY ("event_id"),
  CONSTRAINT "ck_notice_kind_enum" CHECK ("kind" IN ('authorized', 'captured', 'failed', 'refunded'))
);

ALTER TABLE "payments"."payment" ADD CONSTRAINT "fk_payment_order" FOREIGN KEY ("order_id") REFERENCES "orders"."order" ("id");

COMMENT ON TABLE "payments"."payment" IS 'A payment for one order.';
COMMENT ON COLUMN "payments"."payment"."failure" IS 'Why the provider declined, when it did.';
COMMENT ON TABLE "payments"."pay" IS 'Pays for an order.';
COMMENT ON COLUMN "payments"."pay"."idempotency_key" IS 'A key the client makes up, so a retried payment charges once.';
COMMENT ON TABLE "payments"."refund" IS 'Gives some or all of a captured payment back.';
COMMENT ON TABLE "payments"."notice" IS 'What the provider reports about a payment.';
COMMENT ON COLUMN "payments"."notice"."signature" IS 'The provider''s signature over the body.';
