package io.schemata.testkit

import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue

class PostgresTest {
    @Test
    fun `applies files in path order and snapshots the catalog`() {
        assumeTrue(Postgres.available, "Docker is not available; skipping")
        val files =
            mapOf(
                "b/second.sql" to
                    "ALTER TABLE \"a\".\"t\" ADD CONSTRAINT \"fk_t_p\" FOREIGN KEY (\"p\") REFERENCES \"a\".\"t\" (\"id\");",
                "a/first.sql" to
                    """
                    CREATE SCHEMA IF NOT EXISTS "a";

                    CREATE TABLE "a"."t" (
                      "id" uuid NOT NULL,
                      "p" uuid,
                      "n" integer NOT NULL DEFAULT 3,
                      "s" varchar(8),
                      CONSTRAINT "pk_t" PRIMARY KEY ("id"),
                      CONSTRAINT "uq_t_p" UNIQUE ("p"),
                      CONSTRAINT "ck_t_n_min" CHECK ("n" >= 0)
                    );

                    CREATE INDEX "ix_t_n" ON "a"."t" ("n");
                    CREATE UNIQUE INDEX "ux_t_s" ON "a"."t" ("s");

                    COMMENT ON TABLE "a"."t" IS 'A table.';
                    COMMENT ON COLUMN "a"."t"."n" IS 'A number.';
                    """
                        .trimIndent(),
            )
        val catalog = Postgres.withDatabase(files) { Postgres.catalog(it) }
        assertEquals(
            """
            schema a
            table a.t -- A table.
              column id uuid not null
              column p uuid
              column n integer not null default 3 -- A number.
              column s character varying(8)
              constraint ck_t_n_min CHECK ((n >= 0))
              constraint fk_t_p FOREIGN KEY (p) REFERENCES a.t(id)
              constraint pk_t PRIMARY KEY (id)
              constraint uq_t_p UNIQUE (p)
              index ix_t_n btree (n)
              index ux_t_s btree (s)
            """
                .trimIndent() + "\n",
            catalog,
        )
    }

    @Test
    fun `each call gets a fresh database`() {
        assumeTrue(Postgres.available, "Docker is not available; skipping")
        Postgres.withDatabase(mapOf("x.sql" to "CREATE SCHEMA \"only\";")) {}
        assertEquals("", Postgres.withDatabase(emptyMap()) { Postgres.catalog(it) })
    }

    @Test
    fun `the public schema appears once it holds a table`() {
        assumeTrue(Postgres.available, "Docker is not available; skipping")
        val files = mapOf("p.sql" to "CREATE TABLE \"public\".\"p\" (\"id\" integer NOT NULL);")
        val catalog = Postgres.withDatabase(files) { Postgres.catalog(it) }
        assertEquals(
            """
            schema public
            table public.p
              column id integer not null
            """
                .trimIndent() + "\n",
            catalog,
        )
    }

    @Test
    fun `a unique index that a foreign key references is still listed`() {
        assumeTrue(Postgres.available, "Docker is not available; skipping")
        val files =
            mapOf(
                "x.sql" to
                    """
                    CREATE SCHEMA "a";

                    CREATE TABLE "a"."t" (
                      "id" integer NOT NULL,
                      "code" integer NOT NULL,
                      "parent" integer,
                      CONSTRAINT "pk_t" PRIMARY KEY ("id")
                    );

                    CREATE UNIQUE INDEX "ux_t_code" ON "a"."t" ("code");

                    ALTER TABLE "a"."t" ADD CONSTRAINT "fk_t_parent" FOREIGN KEY ("parent") REFERENCES "a"."t" ("code");
                    """
                        .trimIndent()
            )
        val catalog = Postgres.withDatabase(files) { Postgres.catalog(it) }
        assertEquals(
            """
            schema a
            table a.t
              column id integer not null
              column code integer not null
              column parent integer
              constraint fk_t_parent FOREIGN KEY (parent) REFERENCES a.t(code)
              constraint pk_t PRIMARY KEY (id)
              index ux_t_code btree (code)
            """
                .trimIndent() + "\n",
            catalog,
        )
    }

    @Test
    fun `names with quotes are snapshotted safely`() {
        assumeTrue(Postgres.available, "Docker is not available; skipping")
        val files =
            mapOf(
                "x.sql" to
                    """
                    CREATE SCHEMA "od'd";

                    CREATE TABLE "od'd"."we""ird" (
                      "id" integer NOT NULL,
                      CONSTRAINT "pk_we""ird" PRIMARY KEY ("id")
                    );
                    """
                        .trimIndent()
            )
        val catalog = Postgres.withDatabase(files) { Postgres.catalog(it) }
        assertEquals(
            """
            schema od'd
            table od'd.we"ird
              column id integer not null
              constraint pk_we"ird PRIMARY KEY (id)
            """
                .trimIndent() + "\n",
            catalog,
        )
    }
}
