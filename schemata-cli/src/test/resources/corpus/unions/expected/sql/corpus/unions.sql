CREATE SCHEMA IF NOT EXISTS "unions";

CREATE TABLE "unions"."order" (
  "id" uuid NOT NULL,
  "payment_kind" text NOT NULL,
  "payment_card_last4" varchar(4),
  "payment_uuid" uuid,
  "refund_kind" text,
  "refund_card_last4" varchar(4),
  "refund_uuid" uuid,
  "archived" jsonb NOT NULL,  -- schemata: Payment
  CONSTRAINT "pk_order" PRIMARY KEY ("id"),
  CONSTRAINT "ck_order_payment_kind" CHECK ("payment_kind" IN ('card', 'cash', 'uuid')),
  CONSTRAINT "ck_order_payment_card" CHECK (("payment_kind" <> 'card') OR ("payment_card_last4" IS NOT NULL)),
  CONSTRAINT "ck_order_payment_uuid" CHECK (("payment_kind" <> 'uuid') OR ("payment_uuid" IS NOT NULL)),
  CONSTRAINT "ck_order_refund_kind" CHECK ("refund_kind" IN ('card', 'cash', 'uuid')),
  CONSTRAINT "ck_order_refund_card" CHECK (("refund_kind" <> 'card') OR ("refund_card_last4" IS NOT NULL)),
  CONSTRAINT "ck_order_refund_uuid" CHECK (("refund_kind" <> 'uuid') OR ("refund_uuid" IS NOT NULL))
);
