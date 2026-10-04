CREATE TABLE s.customer (id uuid PRIMARY KEY);
CREATE TABLE s.orders (
  id uuid PRIMARY KEY,
  buyer uuid NOT NULL REFERENCES s.customer (id)
);
