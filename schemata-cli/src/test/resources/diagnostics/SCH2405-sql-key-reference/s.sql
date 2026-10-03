CREATE TABLE s.customer (id uuid PRIMARY KEY);
CREATE TABLE s.profile (
  customer_id uuid PRIMARY KEY REFERENCES s.customer (id)
);
