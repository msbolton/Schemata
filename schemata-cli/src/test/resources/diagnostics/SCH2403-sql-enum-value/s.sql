CREATE TABLE s.orders (
  id uuid PRIMARY KEY,
  status text NOT NULL CHECK (status IN ('open', 'Paid-Out'))
);
