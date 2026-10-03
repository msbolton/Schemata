-- A schema in a subdirectory, referenced from another file.

CREATE SCHEMA geo;

CREATE TABLE geo.region (
  id uuid PRIMARY KEY,
  name text NOT NULL
);
