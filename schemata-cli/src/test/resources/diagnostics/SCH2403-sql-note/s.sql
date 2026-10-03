CREATE TABLE s.orders (
  id uuid PRIMARY KEY,
  qty integer NOT NULL,  -- schemata: int32(
  sku text NOT NULL  -- schemata: list<string>
);
