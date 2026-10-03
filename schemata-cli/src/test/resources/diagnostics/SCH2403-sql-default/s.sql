CREATE TABLE s.orders (
  id uuid PRIMARY KEY,
  placed timestamptz NOT NULL DEFAULT now()
);
