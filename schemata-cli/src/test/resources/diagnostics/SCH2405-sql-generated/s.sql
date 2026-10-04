CREATE TABLE s.orders (
  id uuid PRIMARY KEY,
  qty integer NOT NULL,
  double_qty integer GENERATED ALWAYS AS (qty * 2) STORED
);
