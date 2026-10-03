CREATE TABLE s.orders (
  id uuid PRIMARY KEY,
  a integer NOT NULL,
  b integer NOT NULL,
  UNIQUE (a, b)
);
