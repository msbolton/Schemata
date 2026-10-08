CREATE TABLE s.customer (
  tenant_id uuid NOT NULL,
  code text NOT NULL,
  PRIMARY KEY (tenant_id, code)
);
CREATE TABLE s.orders (
  id uuid PRIMARY KEY,
  a integer NOT NULL,
  customer_tenant_id uuid NOT NULL,
  customer_code text NOT NULL,
  FOREIGN KEY (customer_tenant_id, customer_code) REFERENCES s.customer (tenant_id, code),
  UNIQUE (a, customer_code)
);
