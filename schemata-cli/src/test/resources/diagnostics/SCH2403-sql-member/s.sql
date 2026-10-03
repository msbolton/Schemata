CREATE TABLE s.orders (
  id uuid PRIMARY KEY,
  pay_kind text NOT NULL CHECK (pay_kind IN ('cash')),
  pay_cash_note text,
  refund_kind text CHECK (refund_kind IN ('cash')),
  refund_cash_tip integer
);
