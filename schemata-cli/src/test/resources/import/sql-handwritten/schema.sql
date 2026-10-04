-- A shop schema as a person writes it: inline constraints, serial and identity
-- columns, a smallint, an unnamed check, a composite foreign key, a child
-- table, a union, and a view.

CREATE SCHEMA shop;

CREATE TABLE shop.customers (
  tenant_id uuid NOT NULL,
  code varchar(16) NOT NULL,
  name text NOT NULL CHECK (char_length(name) >= 1),
  PRIMARY KEY (tenant_id, code)
);

CREATE TABLE shop.orders (
  id uuid PRIMARY KEY,
  status text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'paid')),
  qty smallint NOT NULL CHECK (qty > 0),
  note varchar(500),
  customer_tenant_id uuid NOT NULL,
  customer_code varchar(16) NOT NULL,
  seq serial,
  n integer GENERATED ALWAYS AS IDENTITY,
  payment_kind text NOT NULL CHECK (payment_kind IN ('card', 'cash')),
  payment_card_last4 varchar(4),
  payment_card_brand varchar(32),
  CHECK ((payment_kind <> 'card') OR (payment_card_last4 IS NOT NULL AND payment_card_brand IS NOT NULL)),
  CONSTRAINT uq_orders_note UNIQUE (note),
  FOREIGN KEY (customer_tenant_id, customer_code)
    REFERENCES shop.customers (tenant_id, code) ON DELETE RESTRICT
);

CREATE TABLE shop.orders_lines (
  orders_id uuid NOT NULL REFERENCES shop.orders (id) ON DELETE CASCADE,
  position integer NOT NULL,
  sku varchar(64) NOT NULL,
  quantity integer NOT NULL CHECK (quantity >= 1),
  PRIMARY KEY (orders_id, position)
);

CREATE INDEX ix_orders_status ON shop.orders (status);

COMMENT ON TABLE shop.orders IS 'An order, one row per checkout.';
COMMENT ON COLUMN shop.orders.note IS 'A note for the courier.';
COMMENT ON COLUMN shop.orders.payment_kind IS 'How the order was paid.';
COMMENT ON TABLE shop.orders_lines IS 'One purchasable item.';

CREATE VIEW shop.paid_orders AS
  SELECT id, status FROM shop.orders WHERE status = 'paid';
