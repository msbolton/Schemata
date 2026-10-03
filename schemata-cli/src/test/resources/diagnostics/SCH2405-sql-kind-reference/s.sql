CREATE TABLE s.kinds (name text PRIMARY KEY);
CREATE TABLE s.orders (
  id uuid PRIMARY KEY,
  pay_kind text NOT NULL REFERENCES s.kinds (name) CHECK (pay_kind IN ('cash')),
  pay_cash_note text
);
