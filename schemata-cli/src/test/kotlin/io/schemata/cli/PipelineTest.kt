package io.schemata.cli

import io.schemata.lang.Category
import io.schemata.target.proto.ProtoTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PipelineTest {
    private val orders =
        SourceInput(
            "src/orders.schemata",
            """
            namespace shop.orders

            record User {
              @sql(key) id:    uuid
              email: string?
              name:  string
              age:   int32
            }

            record Session {
              @sql(key) token:   string
              user_id: uuid
              active:  bool
            }
            """
                .trimIndent(),
        )

    private val customers =
        SourceInput(
            "src/customers.schemata",
            """
            namespace shop.customers

            record Customer {
              @sql(key) id:   uuid
              name: string
            }
            """
                .trimIndent(),
        )

    @Test
    fun `produces one file per namespace per target and collects lossy diagnostics`() {
        val result = Pipeline.compile(listOf(orders, customers), Pipeline.targets)
        assertFalse(result.hasErrors)
        assertEquals(
            listOf(
                "proto" to "shop/customers.proto",
                "proto" to "shop/orders.proto",
                "sql" to "shop/customers.sql",
                "sql" to "shop/orders.sql",
                "xsd" to "shop/customers.xsd",
                "xsd" to "shop/orders.xsd",
            ),
            result.files.map { it.target to it.file.path },
        )
        val lossy = result.diagnostics.filter { it.category == Category.LOSSY }
        assertEquals(3, lossy.size)
        assertTrue(lossy.all { it.span.file.endsWith(".schemata") })
    }

    @Test
    fun `stops at the first failing stage and reports every file's syntax errors`() {
        val bad1 = SourceInput("a.schemata", "namespace a\nrecord R { x uuid }")
        val bad2 = SourceInput("b.schemata", "namespace b\nrecord S { y uuid }")
        val syntax = Pipeline.compile(listOf(bad1, bad2), Pipeline.targets)
        assertTrue(syntax.hasErrors)
        assertEquals(emptyList(), syntax.files)
        assertEquals(listOf("a.schemata", "b.schemata"), syntax.diagnostics.map { it.span.file })

        val semantic =
            Pipeline.compile(
                listOf(SourceInput("c.schemata", "namespace c\nrecord R { x: money }")),
                Pipeline.targets,
            )
        assertTrue(semantic.hasErrors)
        assertEquals(emptyList(), semantic.files)
    }

    @Test
    fun `strict is threaded through to the analyzer`() {
        val src = SourceInput("src/t.schemata", "namespace a\n\nrecord R {\n  x: bool\n}")
        val strict = Pipeline.compile(listOf(src), listOf(ProtoTarget), strict = true)
        assertEquals(listOf("SCH1014"), strict.diagnostics.map { it.code.id })
        assertTrue(
            Pipeline.compile(listOf(src), listOf(ProtoTarget)).diagnostics.none {
                it.code.id == "SCH1014"
            }
        )
    }

    @Test
    fun `looks targets up by name`() {
        assertEquals("proto", Pipeline.targetNamed("proto")?.name)
        assertEquals(null, Pipeline.targetNamed("avro"))
    }

    @Test
    fun `annotations of every known target are accepted whatever targets are selected`() {
        val src =
            SourceInput(
                "src/t.schemata",
                "namespace a\n\nrecord R {\n  @sql(key)\n  id: uuid\n  x: bool\n}",
            )
        val result = Pipeline.compile(listOf(src), listOf(ProtoTarget))
        assertTrue(result.diagnostics.none { it.code.id.startsWith("SCH1") })
        assertEquals(setOf("SCH2001"), result.diagnostics.map { it.code.id }.toSet())
    }

    @Test
    fun `a target that errors is skipped while the other target still produces files`() {
        // An unused keyless record is an sql error (SCH2106) and fine for proto.
        val src =
            SourceInput(
                "p.schemata",
                """
                namespace p

                record Orphan { #1 name: string }

                record R { @sql(key) #1 id: uuid }
                """
                    .trimIndent(),
            )
        val result = Pipeline.compile(listOf(src), Pipeline.targets)
        val proto = result.targets.single { it.name == "proto" }
        val sql = result.targets.single { it.name == "sql" }
        val xsd = result.targets.single { it.name == "xsd" }
        assertTrue(proto.ok)
        assertEquals(listOf("p.proto"), proto.files.map { it.path })
        assertFalse(sql.ok)
        assertEquals(emptyList(), sql.files)
        assertTrue(xsd.ok)
        assertEquals(listOf("p.xsd"), xsd.files.map { it.path })
        assertTrue(result.hasErrors)
        assertEquals(
            listOf("proto" to "p.proto", "xsd" to "p.xsd"),
            result.files.map { it.target to it.file.path },
        )
    }

    @Test
    fun `check lowers every target and writes no files`() {
        val result = Pipeline.check(listOf(orders, customers), Pipeline.targets)
        assertEquals(listOf("proto", "sql", "xsd"), result.targets.map { it.name })
        assertTrue(result.targets.all { it.files.isEmpty() })
        assertEquals(
            Pipeline.compile(listOf(orders, customers), Pipeline.targets).diagnostics,
            result.diagnostics,
        )
    }

    @Test
    fun `core diagnostics are separate from target diagnostics`() {
        val result = Pipeline.compile(listOf(orders, customers), Pipeline.targets)
        assertEquals(emptyList(), result.core)
        assertEquals(3, result.targets.sumOf { it.diagnostics.size })
    }
}
