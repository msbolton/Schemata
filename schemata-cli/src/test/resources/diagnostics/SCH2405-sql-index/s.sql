CREATE TABLE s.customer (
  tenant_id uuid NOT NULL,
  code text NOT NULL,
  PRIMARY KEY (tenant_id, code)
);
CREATE TABLE s.orders (
  id uuid PRIMARY KEY,
  a integer NOT NULL,
  b integer NOT NULL,
  customer_tenant_id uuid NOT NULL,
  customer_code text NOT NULL,
  FOREIGN KEY (customer_tenant_id, customer_code) REFERENCES s.customer (tenant_id, code)
);
CREATE INDEX ON s.orders (a) WHERE a > 0;
CREATE INDEX ON s.orders USING gin (b);
CREATE INDEX ON s.orders (customer_code);
CREATE UNIQUE INDEX ON s.orders (b, customer_tenant_id);
