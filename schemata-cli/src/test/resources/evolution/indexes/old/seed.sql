INSERT INTO "s"."customer" ("id", "email", "code", "city") VALUES
  (gen_random_uuid(), 'ada@example.com', 'ADA', 'London'),
  (gen_random_uuid(), 'alan@example.com', 'ALAN', NULL);
