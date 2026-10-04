CREATE TABLE s.orders (
  id uuid PRIMARY KEY,
  tags json NOT NULL  -- schemata: list<string>
);
