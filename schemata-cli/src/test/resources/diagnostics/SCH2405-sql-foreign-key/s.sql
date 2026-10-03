CREATE TABLE s.customer (id uuid PRIMARY KEY, email text NOT NULL UNIQUE);
CREATE TABLE s.orders (
  id uuid PRIMARY KEY,
  customer_email text NOT NULL REFERENCES s.customer (email)
);
