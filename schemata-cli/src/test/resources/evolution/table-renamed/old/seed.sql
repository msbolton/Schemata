INSERT INTO "s"."order" ("id", "note") VALUES
  ('5f0c6d1e-2a3b-4c5d-8e9f-0a1b2c3d4e5f', 'first'),
  ('6a1d7e2f-3b4c-4d5e-9f0a-1b2c3d4e5f60', NULL);
INSERT INTO "s"."order_lines" ("order_id", "position", "sku", "qty") VALUES
  ('5f0c6d1e-2a3b-4c5d-8e9f-0a1b2c3d4e5f', 0, 'A-1', 2),
  ('5f0c6d1e-2a3b-4c5d-8e9f-0a1b2c3d4e5f', 1, 'B-2', 1),
  ('6a1d7e2f-3b4c-4d5e-9f0a-1b2c3d4e5f60', 0, 'C-3', 5);
