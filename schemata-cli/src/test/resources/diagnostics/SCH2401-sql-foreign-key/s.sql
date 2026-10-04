CREATE TABLE s.orders (
  id uuid PRIMARY KEY,
  customer_id uuid REFERENCES s.customers (id)
);
