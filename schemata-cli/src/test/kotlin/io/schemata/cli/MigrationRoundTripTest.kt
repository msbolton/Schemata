package io.schemata.cli

import io.schemata.migrate.AlterColumnType
import io.schemata.migrate.Migration
import io.schemata.migrate.MigrationRenderer
import io.schemata.migrate.RenameTable
import io.schemata.migrate.RenameValue
import io.schemata.migrate.Risk
import io.schemata.migrate.SetSchema
import io.schemata.target.sql.SqlTarget
import io.schemata.testkit.Postgres
import java.io.File
import java.sql.Connection
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * Every corpus case outside [notMigrated] lowers on both sides under the SQL target, and OLD's DDL
 * plus the migration (destructive steps allowed) leaves the same catalog as NEW's DDL applied
 * fresh. A case with an `old/seed.sql` also proves its rows survive: every seeded table, followed
 * through the migration's table renames and schema moves, keeps its row count and the values of
 * every column whose name and type the migration leaves alone.
 */
class MigrationRoundTripTest {
    private val corpus = File("src/test/resources/evolution")

    /** The cases a side of which has SQL errors, so `migrate` plans nothing for them. */
    private val notMigrated = setOf("services")

    @TestFactory
    fun `a migrated database matches a fresh one`(): List<DynamicTest> =
        corpus
            .listFiles { f -> f.isDirectory }!!
            .sortedBy { it.name }
            .map { case -> DynamicTest.dynamicTest(case.name) { check(case) } }

    private fun check(case: File) {
        val oldSources = sources(File(case, "old"), "old")
        val newSources = sources(File(case, "new"), "new")
        val old = analyzeSide(oldSources)
        val new = analyzeSide(newSources)
        assertNotNull(old.schema, "${case.name}: OLD does not analyse")
        assertNotNull(new.schema, "${case.name}: NEW does not analyse")
        val migrated = migrate(old.schema!!, new.schema!!, allowDestructive = true)
        if (case.name in notMigrated) {
            assertFalse(migrated.lowered, "${case.name} lowers now; drop it from notMigrated")
            return
        }
        assertTrue(
            migrated.lowered,
            "${case.name}: a side has sql errors: ${migrated.diagnostics.map { it.message }}",
        )
        assumeTrue(Postgres.available, "Docker is not available")
        val oldDdl = ddl(oldSources).mapKeys { "0/${it.key}" }
        val newDdl = ddl(newSources)
        val files = MigrationRenderer.files(migrated.migration, allowDestructive = true)
        val migratedCatalog =
            Postgres.withDatabase(oldDdl + files.associate { "1/${it.first}" to it.second }) {
                columnsSorted(Postgres.catalog(it))
            }
        val freshCatalog = Postgres.withDatabase(newDdl) { columnsSorted(Postgres.catalog(it)) }
        assertEquals(
            freshCatalog,
            migratedCatalog,
            "${case.name}: the migrated catalog differs from a fresh one",
        )
        rowsSurvive(case, oldDdl, files, migrated.migration)
    }

    /**
     * [catalog] with each table's column lines sorted: a column a migration adds sits at the end of
     * its table, and no migration reorders columns, so position is the one thing a migrated table
     * may differ from a fresh one in.
     */
    private fun columnsSorted(catalog: String): String {
        val out = mutableListOf<String>()
        val run = mutableListOf<String>()
        catalog.lines().forEach { line ->
            if (line.startsWith("  column ")) run += line
            else {
                out += run.sorted()
                run.clear()
                out += line
            }
        }
        out += run.sorted()
        return out.joinToString("\n")
    }

    /**
     * Applies OLD's DDL and the seed, snapshots every seeded table, applies the migration on the
     * same database, and snapshots each table again where the migration moved it. A seeded case may
     * only lose data through a type change, whose column the snapshot leaves out.
     */
    private fun rowsSurvive(
        case: File,
        oldDdl: Map<String, String>,
        files: List<Pair<String, String>>,
        migration: Migration,
    ) {
        val seed = File(case, "old/seed.sql").takeIf { it.isFile } ?: return
        val lossy = migration.steps.filter { it.risk == Risk.DESTRUCTIVE && it !is AlterColumnType }
        check(lossy.isEmpty()) { "${case.name} has a seed but drops data: $lossy" }
        Postgres.withDatabase(oldDdl + ("1/seed.sql" to seed.readText())) { conn ->
            val seeded = tables(conn).filter { count(conn, it) > 0 }
            check(seeded.isNotEmpty()) { "${case.name}: the seed inserted no rows" }
            val before =
                seeded.associateWith { table ->
                    renamedValues(snapshot(conn, table), movedTo(table, migration), migration)
                }
            files.forEach { (_, sql) -> conn.createStatement().use { it.execute(sql) } }
            before.forEach { (table, rows) ->
                val moved = movedTo(table, migration)
                assertTrue(moved in tables(conn), "${case.name}: $table is gone after migrating")
                val after = snapshot(conn, moved)
                val shared = rows.types.filter { (column, type) -> after.types[column] == type }
                assertEquals(rows.count, after.count, "${case.name}: $table lost rows (now $moved)")
                assertEquals(
                    rows.over(shared.keys),
                    after.over(shared.keys),
                    "${case.name}: $table's values changed (now $moved)",
                )
            }
        }
    }

    /** [rows] as the migration's enum value renames on [table] leave them. */
    private fun renamedValues(
        rows: Snapshot,
        table: Pair<String, String>,
        migration: Migration,
    ): Snapshot {
        val renames =
            migration.steps.filterIsInstance<RenameValue>().filter {
                it.at.schema to it.at.table == table && !it.array
            }
        val rewritten =
            rows.rows.map { row ->
                row.mapValues { (column, value) ->
                    renames
                        .firstOrNull { it.column == column }
                        ?.renames
                        ?.firstOrNull { it.first == value }
                        ?.second ?: value
                }
            }
        return Snapshot(rows.types, rows.count, rewritten)
    }

    /** Where a table OLD had stands once the migration's schema moves and renames have run. */
    private fun movedTo(table: Pair<String, String>, migration: Migration): Pair<String, String> {
        var at = table
        migration.steps.forEach { step ->
            when (step) {
                is SetSchema ->
                    if (at == step.at.schema to step.at.table) at = step.to to step.at.table
                is RenameTable ->
                    if (at == step.at.schema to step.at.table) at = at.first to step.to
                else -> {}
            }
        }
        return at
    }

    /** A table's column types and its rows as text, each row's values joined in column order. */
    private class Snapshot(
        val types: Map<String, String>,
        val count: Int,
        val rows: List<Map<String, String?>>,
    ) {
        fun over(columns: Set<String>): List<String> =
            rows.map { row -> columns.sorted().joinToString("|") { "$it=${row[it]}" } }.sorted()
    }

    private fun snapshot(conn: Connection, table: Pair<String, String>): Snapshot {
        val (schema, name) = table
        val types =
            query(
                    conn,
                    "select a.attname, format_type(a.atttypid, a.atttypmod) from pg_attribute a " +
                        "where a.attrelid = '${quote(schema)}.${quote(name)}'::regclass " +
                        "and a.attnum > 0 and not a.attisdropped",
                ) {
                    it.getString(1) to it.getString(2)
                }
                .toMap()
        val rows =
            query(conn, "select * from ${quote(schema)}.${quote(name)}") { rs ->
                (1..rs.metaData.columnCount).associate {
                    rs.metaData.getColumnName(it) to rs.getString(it)
                }
            }
        return Snapshot(types, rows.size, rows)
    }

    private fun count(conn: Connection, table: Pair<String, String>): Int =
        query(conn, "select count(*) from ${quote(table.first)}.${quote(table.second)}") {
                it.getInt(1)
            }
            .single()

    /** Every user table, as schema and name. */
    private fun tables(conn: Connection): List<Pair<String, String>> =
        query(
            conn,
            "select schemaname, tablename from pg_tables " +
                "where schemaname not in ('pg_catalog', 'information_schema') " +
                "order by schemaname, tablename",
        ) {
            it.getString(1) to it.getString(2)
        }

    private fun <T> query(conn: Connection, sql: String, row: (java.sql.ResultSet) -> T): List<T> =
        conn.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                generateSequence { if (rs.next()) row(rs) else null }.toList()
            }
        }

    private fun quote(name: String) = "\"${name.replace("\"", "\"\"")}\""

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
