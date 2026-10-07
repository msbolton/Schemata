package io.schemata.cli

import io.schemata.migrate.MigrationRenderer
import io.schemata.migrate.Risk
import io.schemata.target.sql.SqlTarget
import io.schemata.testkit.Postgres
import java.io.File
import java.sql.Connection
import kotlin.test.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * Every corpus case whose two sides both lower under the SQL target: OLD's DDL plus the migration
 * (destructive steps allowed) leaves the same catalog as NEW's DDL applied fresh. A case with an
 * `old/seed.sql` also proves its rows survive a migration with no destructive step.
 */
class MigrationRoundTripTest {
    private val corpus = File("src/test/resources/evolution")

    @TestFactory
    fun `a migrated database matches a fresh one`(): List<DynamicTest> =
        corpus
            .listFiles { f -> f.isDirectory }!!
            .sortedBy { it.name }
            .map { case -> DynamicTest.dynamicTest(case.name) { check(case) } }

    private fun check(case: File) {
        assumeTrue(Postgres.available, "Docker is not available")
        val oldSources = sources(File(case, "old"), "old")
        val newSources = sources(File(case, "new"), "new")
        val old = analyzeSide(oldSources)
        val new = analyzeSide(newSources)
        assumeTrue(old.schema != null && new.schema != null, "a side does not analyse")
        val migrated = migrate(old.schema!!, new.schema!!, allowDestructive = true)
        assumeTrue(migrated.lowered, "a side has sql errors")
        val oldDdl = ddl(oldSources).mapKeys { "0/${it.key}" }
        val newDdl = ddl(newSources)
        val files = MigrationRenderer.files(migrated.migration, allowDestructive = true)
        val migratedCatalog =
            Postgres.withDatabase(oldDdl + files.associate { "1/${it.first}" to it.second }) {
                Postgres.catalog(it)
            }
        val freshCatalog = Postgres.withDatabase(newDdl) { Postgres.catalog(it) }
        assertEquals(
            freshCatalog,
            migratedCatalog,
            "${case.name}: the migrated catalog differs from a fresh one",
        )
        rowsSurvive(case, oldDdl, files, migrated)
    }

    /**
     * Applies OLD's DDL and the seed, counts every table's rows, then does the same with the
     * migration applied after the seed: the totals must match.
     */
    private fun rowsSurvive(
        case: File,
        oldDdl: Map<String, String>,
        files: List<Pair<String, String>>,
        migrated: Migrated,
    ) {
        val seed = File(case, "old/seed.sql").takeIf { it.isFile } ?: return
        check(migrated.migration.steps.none { it.risk == Risk.DESTRUCTIVE }) {
            "${case.name} has a seed but a destructive step"
        }
        val seeded = oldDdl + ("1/seed.sql" to seed.readText())
        val before = Postgres.withDatabase(seeded) { counts(it) }
        check(before.values.sum() > 0) { "${case.name}: the seed inserted no rows" }
        val after =
            Postgres.withDatabase(seeded + files.associate { "2/${it.first}" to it.second }) {
                counts(it)
            }
        assertEquals(before.values.sum(), after.values.sum(), "${case.name}: rows were lost")
    }

    /** Row count per user table, keyed `schema.table`. */
    private fun counts(conn: Connection): Map<String, Int> {
        val tables =
            conn.createStatement().use { st ->
                st.executeQuery(
                        "select schemaname, tablename from pg_tables " +
                            "where schemaname not in ('pg_catalog', 'information_schema') " +
                            "order by schemaname, tablename"
                    )
                    .use { rs ->
                        generateSequence {
                                if (rs.next()) rs.getString(1) to rs.getString(2) else null
                            }
                            .toList()
                    }
            }
        return tables.associate { (schema, table) ->
            val name = "\"${schema.replace("\"", "\"\"")}\".\"${table.replace("\"", "\"\"")}\""
            "$schema.$table" to
                conn.createStatement().use { st ->
                    st.executeQuery("select count(*) from $name").use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
                }
        }
    }

    /** OLD's or NEW's own DDL, as `schemata compile --target sql` writes it. */
    private fun ddl(sources: List<SourceInput>): Map<String, String> {
        val result = Pipeline.compile(sources, listOf(SqlTarget))
        check(!result.hasErrors) {
            result.diagnostics.joinToString("\n") { "${it.code.id} ${it.message}" }
        }
        return result.files.associate { it.file.path to it.file.content }
    }

    private fun sources(dir: File, prefix: String): List<SourceInput> =
        dir.listFiles { f -> f.extension == "schemata" }!!
            .sortedBy { it.name }
            .map { SourceInput("$prefix/${it.name}", it.readText()) }
}
