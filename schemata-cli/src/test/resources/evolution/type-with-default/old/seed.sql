INSERT INTO "s"."job" ("id", "name") VALUES (gen_random_uuid(), 'nightly');
INSERT INTO "s"."job" ("id", "name", "retries") VALUES (gen_random_uuid(), 'hourly', '5');
