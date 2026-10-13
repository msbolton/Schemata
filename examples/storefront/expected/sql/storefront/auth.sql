CREATE SCHEMA IF NOT EXISTS "auth";

CREATE TABLE "auth"."register" (
  "email" text NOT NULL,
  "password" text NOT NULL,
  "name" varchar(100) NOT NULL,
  CONSTRAINT "pk_register" PRIMARY KEY ("email"),
  CONSTRAINT "ck_register_email_max" CHECK (char_length("email") <= 254),
  CONSTRAINT "ck_register_email_pattern" CHECK ("email" ~ '^[^@]+@[^@]+$'),
  CONSTRAINT "ck_register_password_min" CHECK (char_length("password") >= 12),
  CONSTRAINT "ck_register_password_max" CHECK (char_length("password") <= 128)
);

CREATE TABLE "auth"."login" (
  "email" text NOT NULL,
  "password" text NOT NULL,
  CONSTRAINT "pk_login" PRIMARY KEY ("email"),
  CONSTRAINT "ck_login_email_max" CHECK (char_length("email") <= 254),
  CONSTRAINT "ck_login_email_pattern" CHECK ("email" ~ '^[^@]+@[^@]+$'),
  CONSTRAINT "ck_login_password_min" CHECK (char_length("password") >= 1),
  CONSTRAINT "ck_login_password_max" CHECK (char_length("password") <= 128)
);

CREATE TABLE "auth"."refresh_token" (
  "refresh_token" varchar(512) NOT NULL,
  CONSTRAINT "pk_refresh_token" PRIMARY KEY ("refresh_token")
);

CREATE TABLE "auth"."session" (
  "access_token" varchar(4096) NOT NULL,
  "token_type" text NOT NULL DEFAULT 'Bearer',
  "expires_in" integer NOT NULL,
  "refresh_token" varchar(512) NOT NULL,
  "user_id" uuid NOT NULL,
  CONSTRAINT "pk_session" PRIMARY KEY ("access_token"),
  CONSTRAINT "ck_session_expires_in_min" CHECK ("expires_in" >= 1)
);

COMMENT ON TABLE "auth"."register" IS 'A new account.';
COMMENT ON TABLE "auth"."login" IS 'The credentials a session starts from.';
COMMENT ON TABLE "auth"."refresh_token" IS 'A refresh token, traded for a new session or given up at sign-out.';
COMMENT ON TABLE "auth"."session" IS 'A session: a short-lived access token, sent as `Authorization: Bearer …`, and the refresh
token that renews it.';
COMMENT ON COLUMN "auth"."session"."expires_in" IS 'Seconds until the access token expires.';
