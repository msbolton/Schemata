CREATE TABLE s.orders (
  id uuid PRIMARY KEY,
  code text NOT NULL CHECK (code ~* '^[a-z]+$')
);
