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
}
