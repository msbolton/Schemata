CREATE SCHEMA IF NOT EXISTS "common";

CREATE TABLE "common"."problem" (
  "type" varchar(2048) NOT NULL DEFAULT 'about:blank',
  "title" varchar(200) NOT NULL,
  "status" integer NOT NULL,
  "detail" varchar(2000),
  "instance" varchar(2048) NOT NULL,
  CONSTRAINT "pk_problem" PRIMARY KEY ("instance"),
  CONSTRAINT "ck_problem_status_min" CHECK ("status" >= 400),
  CONSTRAINT "ck_problem_status_max" CHECK ("status" <= 599)
);

CREATE TABLE "common"."problem_errors" (
  "problem_instance" varchar(2048) NOT NULL,
  "position" integer NOT NULL,
  "field" varchar(200) NOT NULL,
  "message" varchar(500) NOT NULL,
  CONSTRAINT "pk_problem_errors" PRIMARY KEY ("problem_instance", "position")
);

ALTER TABLE "common"."problem_errors" ADD CONSTRAINT "fk_problem_errors_problem" FOREIGN KEY ("problem_instance") REFERENCES "common"."problem" ("instance") ON DELETE CASCADE;

COMMENT ON TABLE "common"."problem" IS 'A problem report in the shape of RFC 9457, the body of every error response. Members beyond
those declared are allowed, as the RFC allows extensions. `instance` stays required because a
top-level model needs a key.';
COMMENT ON COLUMN "common"."problem"."type" IS 'A URI naming the kind of problem; `about:blank` when the status says it all.';
COMMENT ON COLUMN "common"."problem"."title" IS 'A short summary of the kind of problem, the same for every occurrence.';
COMMENT ON COLUMN "common"."problem"."status" IS 'The HTTP status code of the response.';
COMMENT ON COLUMN "common"."problem"."detail" IS 'What went wrong this time.';
COMMENT ON COLUMN "common"."problem"."instance" IS 'A URI naming this occurrence, to quote when asking for help.';
COMMENT ON TABLE "common"."problem_errors" IS 'One field of the request and what is wrong with it.';
