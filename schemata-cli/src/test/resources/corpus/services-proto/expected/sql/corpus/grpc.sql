CREATE SCHEMA IF NOT EXISTS "grpc";

CREATE TABLE "grpc"."order_id" (
  "id" bigint NOT NULL,
  CONSTRAINT "pk_order_id" PRIMARY KEY ("id")
);

CREATE TABLE "grpc"."order" (
  "id" bigint NOT NULL,
  "note" text NOT NULL,
  CONSTRAINT "pk_order" PRIMARY KEY ("id")
);

CREATE TABLE "grpc"."summary" (
  "count" bigint NOT NULL,
  CONSTRAINT "pk_summary" PRIMARY KEY ("count")
);

CREATE TABLE "grpc"."chunk" (
  "seq" bigint NOT NULL,
  "data" bytea NOT NULL,
  CONSTRAINT "pk_chunk" PRIMARY KEY ("seq")
);
