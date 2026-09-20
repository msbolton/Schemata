package io.schemata.cli

import io.schemata.target.proto.ProtoTarget
import io.schemata.target.sql.SqlTarget
import io.schemata.testkit.Protoc
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/** The walking-skeleton proof: source in, both artifacts out, each validated by the real tool. */
class EndToEndTest {
    private val fixture =
        """
        namespace shop.orders

        record User {
          id:    uuid
          email: string?
          name:  string
          age:   int32
        }

        record Session {
          token:   string
          user_id: uuid
          active:  bool
        }
        """
            .trimIndent()

    @Test
    fun `proto output compiles under protoc`() {
        val result = Pipeline.compile(fixture, listOf(ProtoTarget))
        assertFalse(result.hasErrors)
        val files = result.files.associate { it.file.path to it.file.content }
        assertNull(Protoc.compile(files))
    }

    @Test
    fun `sql output executes against postgres and yields the expected columns`() {
        assumeTrue(
            DockerClientFactory.instance().isDockerAvailable,
            "Docker is not available; skipping",
        )

        val result = Pipeline.compile(fixture, listOf(SqlTarget))
        assertFalse(result.hasErrors)
        val ddl = result.files.single().file.content

        PostgreSQLContainer<Nothing>(DockerImageName.parse("postgres:16-alpine")).use { pg ->
            pg.start()
            DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password).use { conn ->
                conn.createStatement().use { it.execute(ddl) }
                assertEquals(
                    listOf(
                        Triple("id", "uuid", "NO"),
                        Triple("email", "text", "YES"),
                        Triple("name", "text", "NO"),
                        Triple("age", "integer", "NO"),
                    ),
                    columnsOf(conn, "user"),
                )
                assertEquals(
                    listOf(
                        Triple("token", "text", "NO"),
                        Triple("user_id", "uuid", "NO"),
                        Triple("active", "boolean", "NO"),
                    ),
                    columnsOf(conn, "session"),
                )
            }
        }
    }

    private fun columnsOf(conn: Connection, table: String): List<Triple<String, String, String>> =
        conn.createStatement().use { st ->
            val rs =
                st.executeQuery(
                    """
                    select column_name, data_type, is_nullable
                    from information_schema.columns
                    where table_schema = 'orders' and table_name = '$table'
                    order by ordinal_position
                    """
                        .trimIndent()
                )
            generateSequence {
                    if (rs.next()) Triple(rs.getString(1), rs.getString(2), rs.getString(3))
                    else null
                }
                .toList()
        }
}
