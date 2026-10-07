INSERT INTO "b"."customer" ("id", "name") VALUES
  ('8a3f8e2c-5d1b-4f0e-9c7a-1b2c3d4e5f60', 'Ada'),
  ('0b9e7d6c-4a3b-4c2d-8e1f-a0b1c2d3e4f5', 'Alan');
INSERT INTO "a"."order" ("id", "buyer_id") VALUES
  (gen_random_uuid(), '8a3f8e2c-5d1b-4f0e-9c7a-1b2c3d4e5f60'),
  (gen_random_uuid(), '0b9e7d6c-4a3b-4c2d-8e1f-a0b1c2d3e4f5');
