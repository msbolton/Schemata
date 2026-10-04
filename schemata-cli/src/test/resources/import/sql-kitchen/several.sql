-- One file declaring several schemas: each becomes a namespace named after
-- it, and the unqualified table lands in public.

CREATE TABLE audit.event (
  id bigint PRIMARY KEY,
  at timestamptz NOT NULL
);

CREATE TABLE billing.invoice (
  id uuid PRIMARY KEY,
  event_id bigint REFERENCES audit.event (id)
);

CREATE TABLE loose (
  a integer,
  b text
);
