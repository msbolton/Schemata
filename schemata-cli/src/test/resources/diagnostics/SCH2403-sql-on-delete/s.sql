CREATE TABLE s.customer (id uuid PRIMARY KEY);
CREATE TABLE s.orders (
  id uuid PRIMARY KEY,
  customer_id uuid NOT NULL REFERENCES s.customer (id) ON DELETE CASCADE ON UPDATE CASCADE DEFERRABLE
);
