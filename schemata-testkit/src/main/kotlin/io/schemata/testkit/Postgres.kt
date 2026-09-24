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

    private fun schemas(conn: Connection): List<String> =
        query(
            conn,
            "select nspname from pg_namespace where nspname not in ('pg_catalog', 'information_schema', 'public', 'pg_toast') order by nspname",
        ) {
            it.getString(1)
        }

    private fun tables(conn: Connection, schema: String): List<Pair<String, String?>> =
        query(
            conn,
            "select c.relname, obj_description(c.oid, 'pg_class') from pg_class c join pg_namespace n on n.oid = c.relnamespace where n.nspname = '$schema' and c.relkind = 'r' order by c.relname",
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
            where n.nspname = '$schema' and c.relname = '$table' and a.attnum > 0 and not a.attisdropped
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
            "select conname, pg_get_constraintdef(oid) from pg_constraint where conrelid = '\"$schema\".\"$table\"'::regclass order by conname",
        ) {
            "constraint ${it.getString(1)} ${it.getString(2)}"
        }

    private fun indexes(conn: Connection, schema: String, table: String): List<String> =
        query(
            conn,
            """
            select i.indexname, i.indexdef from pg_indexes i
            where i.schemaname = '$schema' and i.tablename = '$table'
              and not exists (select 1 from pg_constraint c where c.conname = i.indexname and c.conrelid = '"$schema"."$table"'::regclass)
            order by i.indexname
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
}
