INSERT INTO "s"."customer" ("id", "visits") VALUES
  (gen_random_uuid(), 0),
  (gen_random_uuid(), 42),
  (gen_random_uuid(), 2147483647);
