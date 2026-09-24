package io.schemata.testkit

import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicInteger
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * A real Postgres for rendered DDL: one container per JVM, a fresh database per [withDatabase], and
 * a normalized snapshot of everything the DDL created.
 */
object Postgres {
    val available: Boolean by lazy {
        runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false)
    }

    private val container by lazy {
        PostgreSQLContainer<Nothing>(DockerImageName.parse("postgres:16-alpine")).also {
            it.start()
        }
    }

    private val databases = AtomicInteger()

    /** Applies [files] in sorted-path order to a new database and runs [block] on it. */
    fun <T> withDatabase(files: Map<String, String>, block: (Connection) -> T): T {
        val name = "schemata_${databases.incrementAndGet()}"
        admin().use { it.createStatement().use { st -> st.execute("CREATE DATABASE $name") } }
        val url = container.jdbcUrl.replace(Regex("/${container.databaseName}(?=\\?|$)"), "/$name")
        DriverManager.getConnection(url, container.username, container.password).use { conn ->
            files.toSortedMap().values.forEach { ddl ->
                conn.createStatement().use { it.execute(ddl) }
            }
            return block(conn)
        }
    }

    private fun admin(): Connection =
        DriverManager.getConnection(container.jdbcUrl, container.username, container.password)

    /**
     * Every user schema, table, column, constraint, and index, one line each, in a stable order.
     * Types are Postgres's canonical spellings (`character varying(8)`), so the snapshot is what
     * the database holds, not what the DDL said.
     */
    fun catalog(conn: Connection): String = buildString {
        for (schema in schemas(conn)) {
            appendLine("schema $schema")
            for ((table, comment) in tables(conn, schema)) {
                append("table $schema.$table")
                comment?.let { append(" -- $it") }
                appendLine()
                columns(conn, schema, table).forEach { appendLine("  $it") }
                constraints(conn, schema, table).forEach { appendLine("  $it") }
                indexes(conn, schema, table).forEach { appendLine("  $it") }
            }
        }
    }

    /** `public` exists in every database, so it is listed only when it holds a table. */
    private fun schemas(conn: Connection): List<String> =
        query(
            conn,
            """
            select nspname from pg_namespace
            where nspname not in ('pg_catalog', 'information_schema', 'pg_toast')
              and (nspname <> 'public' or exists (select 1 from pg_class c where c.relnamespace = pg_namespace.oid and c.relkind = 'r'))
            order by nspname
            """,
        ) {
            it.getString(1)
        }

    private fun tables(conn: Connection, schema: String): List<Pair<String, String?>> =
        query(
            conn,
            "select c.relname, obj_description(c.oid, 'pg_class') from pg_class c join pg_namespace n on n.oid = c.relnamespace where n.nspname = ${lit(schema)} and c.relkind = 'r' order by c.relname",
        ) {
            it.getString(1) to it.getString(2)
        }

    private fun columns(conn: Connection, schema: String, table: String): List<String> =
        query(
            conn,
            """
            select a.attname, format_type(a.atttypid, a.atttypmod), a.attnotnull,
                   pg_get_expr(d.adbin, d.adrelid), col_description(a.attrelid, a.attnum)
            from pg_attribute a
            join pg_class c on c.oid = a.attrelid
            join pg_namespace n on n.oid = c.relnamespace
            left join pg_attrdef d on d.adrelid = a.attrelid and d.adnum = a.attnum
            where n.nspname = ${lit(schema)} and c.relname = ${lit(table)} and a.attnum > 0 and not a.attisdropped
            order by a.attnum
            """,
        ) { rs ->
            buildString {
                append("column ${rs.getString(1)} ${rs.getString(2)}")
                if (rs.getBoolean(3)) append(" not null")
                rs.getString(4)?.let { append(" default $it") }
                rs.getString(5)?.let { append(" -- $it") }
            }
        }

    private fun constraints(conn: Connection, schema: String, table: String): List<String> =
        query(
            conn,
            "select conname, pg_get_constraintdef(oid) from pg_constraint where conrelid = ${regclass(schema, table)} order by conname",
        ) {
            "constraint ${it.getString(1)} ${it.getString(2)}"
        }

    /**
     * Indexes that back a primary key, unique, or exclusion constraint are already listed as that
     * constraint. A foreign key also records the index it references, so it must not hide one.
     */
    private fun indexes(conn: Connection, schema: String, table: String): List<String> =
        query(
            conn,
            """
            select c.relname, pg_get_indexdef(i.indexrelid) from pg_index i
            join pg_class c on c.oid = i.indexrelid
            where i.indrelid = ${regclass(schema, table)}
              and not exists (select 1 from pg_constraint k where k.conindid = i.indexrelid and k.contype in ('p', 'u', 'x'))
            order by c.relname
            """,
        ) {
            "index ${it.getString(1)} ${it.getString(2).substringAfter(" USING ")}"
        }

    private fun <T> query(conn: Connection, sql: String, row: (java.sql.ResultSet) -> T): List<T> =
        conn.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                generateSequence { if (rs.next()) row(rs) else null }.toList()
            }
        }

    /** A single-quoted SQL string literal, with embedded quotes doubled. */
    private fun lit(s: String) = "'" + s.replace("'", "''") + "'"

    /** A `schema.table`-style `regclass` literal, with embedded double quotes doubled. */
    private fun regclass(schema: String, table: String) =
        lit("\"" + schema.replace("\"", "\"\"") + "\".\"" + table.replace("\"", "\"\"") + "\"") +
            "::regclass"
}
