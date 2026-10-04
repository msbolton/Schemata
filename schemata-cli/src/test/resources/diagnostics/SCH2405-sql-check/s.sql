CREATE TABLE s.orders (
  id uuid PRIMARY KEY,
  starts date NOT NULL,
  ends date NOT NULL,
  CHECK (ends >= starts)
);
