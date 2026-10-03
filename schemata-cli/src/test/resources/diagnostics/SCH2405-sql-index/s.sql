CREATE TABLE s.orders (
  id uuid PRIMARY KEY,
  a integer NOT NULL,
  b integer NOT NULL
);
CREATE INDEX ON s.orders (a) WHERE a > 0;
CREATE INDEX ON s.orders USING gin (b);
CREATE INDEX ON s.orders (a, b);
CREATE UNIQUE INDEX ON s.orders (b, a);
