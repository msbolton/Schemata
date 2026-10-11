package io.schemata.target

import io.schemata.core.AnalysisOptions
import io.schemata.core.Analyzer
import io.schemata.core.ir.AnnotationValue
import io.schemata.core.ir.Annotations
import io.schemata.core.ir.Field
import io.schemata.core.ir.Namespace
import io.schemata.core.ir.QualifiedName
import io.schemata.core.ir.RecordType
import io.schemata.core.ir.Ref
import io.schemata.core.ir.Reserved
import io.schemata.core.ir.Schema
import io.schemata.lang.Parser
import io.schemata.lang.Span
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProtoPackagesTest {
    private val span = Span("s.schemata", 1, 1, 1, 5)

    /**
     * A namespace holding one model `T` with a field referring to `T` of each namespace in [refs].
     */
    private fun ns(name: String, refs: List<String> = emptyList(), pkg: String? = null): Namespace {
        val fields =
            refs.mapIndexed { i, other ->
                Field(
                    i + 1,
                    "f$i",
                    Ref(QualifiedName(other, listOf("T"))),
                    false,
                    null,
                    null,
                    null,
                    span,
                    span,
                )
            }
        val model =
            RecordType(
                QualifiedName(name, listOf("T")),
                "T",
                fields,
                Reserved.NONE,
                false,
                emptyList(),
                null,
                span,
                span,
            )
        val annotations =
            pkg?.let { Annotations(mapOf("proto" to mapOf("package" to AnnotationValue.Str(it)))) }
                ?: Annotations.NONE
        return Namespace(name, listOf(model), span, annotations)
    }

    private fun packages(vararg namespaces: Namespace) =
        ProtoPackages.of(Schema(namespaces.sortedBy { it.name }))

    @Test
    fun `a schema outside any cycle keeps its own package and path`() {
        val unit = packages(ns("b.c", pkg = "x.v1")).unitOf("b.c")
        assertEquals("x.v1", unit.packageName)
        assertEquals("b/c.proto", unit.path)
        assertFalse(unit.merged)
        assertFalse(unit.derived)
    }

    @Test
    fun `a cycle takes the common prefix of its members as its package`() {
        val result =
            packages(ns("cyc.alpha", listOf("cyc.beta")), ns("cyc.beta", listOf("cyc.alpha")))
        val unit = result.unitOf("cyc.alpha")
        assertEquals("cyc", unit.packageName)
        assertEquals("cyc.proto", unit.path)
        assertTrue(unit.derived)
        assertTrue(unit.merged)
        assertEquals(listOf("cyc.alpha", "cyc.beta"), unit.members.map { it.name })
        assertEquals(unit, result.unitOf("cyc.beta"))
        assertEquals("cyc", result.packageOf("cyc.beta"))
    }

    @Test
    fun `a common prefix is made of whole segments`() {
        assertEquals(
            "ab",
            packages(ns("ab.cd", listOf("ab.ce")), ns("ab.ce", listOf("ab.cd"))).packageOf("ab.cd"),
        )
        assertEquals(
            "shop",
            packages(
                    ns("shop.orders", listOf("shop.order_lines")),
                    ns("shop.order_lines", listOf("shop.orders")),
                )
                .packageOf("shop.orders"),
        )
        assertEquals(
            "abc",
            packages(ns("abc", listOf("abd")), ns("abd", listOf("abc"))).packageOf("abd"),
        )
    }

    @Test
    fun `a cycle without a common prefix takes its first member's name`() {
        val unit =
            packages(
                    ns("niem_core", listOf("uc2_system_task")),
                    ns("uc2_system_task", listOf("niem_core")),
                )
                .unitOf("uc2_system_task")
        assertEquals("niem_core", unit.packageName)
        assertEquals("niem_core.proto", unit.path)
        assertTrue(unit.derived)
    }

    @Test
    fun `a common prefix that names a schema outside the cycle is not used`() {
        val unit =
            packages(
                    ns("cyc"),
                    ns("cyc.alpha", listOf("cyc.beta")),
                    ns("cyc.beta", listOf("cyc.alpha")),
                )
                .unitOf("cyc.beta")
        assertEquals("cyc.alpha", unit.packageName)
        assertEquals("cyc/alpha.proto", unit.path)
        assertTrue(unit.derived)
    }

    @Test
    fun `a common prefix that is another schema's declared package is not used`() {
        val unit =
            packages(
                    ns("other", pkg = "cyc"),
                    ns("cyc.alpha", listOf("cyc.beta")),
                    ns("cyc.beta", listOf("cyc.alpha")),
                )
                .unitOf("cyc.beta")
        assertEquals("cyc.alpha", unit.packageName)
        assertEquals("cyc/alpha.proto", unit.path)
        assertTrue(unit.derived)
    }

    @Test
    fun `two cycles with one common prefix each take their first member's name`() {
        val packages =
            packages(
                ns("shop.a", listOf("shop.b")),
                ns("shop.b", listOf("shop.a")),
                ns("shop.c", listOf("shop.d")),
                ns("shop.d", listOf("shop.c")),
            )
        assertEquals("shop.a", packages.packageOf("shop.b"))
        assertEquals("shop.c", packages.packageOf("shop.d"))
    }

    @Test
    fun `one declared package names the cycle's file`() {
        val one = packages(ns("a", listOf("b"), pkg = "p.v1"), ns("b", listOf("a"))).unitOf("b")
        assertEquals("p.v1", one.packageName)
        assertEquals("p/v1.proto", one.path)
        assertFalse(one.derived)
        assertNull(one.conflict)
        val both =
            packages(ns("a", listOf("b"), pkg = "p.v1"), ns("b", listOf("a"), pkg = "p.v1"))
                .unitOf("a")
        assertEquals("p.v1", both.packageName)
        assertEquals("p/v1.proto", both.path)
        assertFalse(both.derived)
        assertNull(both.conflict)
    }

    @Test
    fun `the conflict is between the first declared package and the first that differs`() {
        val unit =
            packages(
                    ns("a", listOf("b"), pkg = "p"),
                    ns("b", listOf("c"), pkg = "p"),
                    ns("c", listOf("a"), pkg = "q"),
                )
                .unitOf("a")
        val (first, other) = unit.conflict!!
        assertEquals("a" to "c", first.name to other.name)
    }

    @Test
    fun `a declared package that is another schema's name keeps the file at its first member's path`() {
        val unit =
            packages(ns("p.v1"), ns("a", listOf("b"), pkg = "p.v1"), ns("b", listOf("a")))
                .unitOf("a")
        assertEquals("p.v1", unit.packageName)
        assertEquals("a.proto", unit.path)
    }

    private fun compile(vararg files: Pair<String, String>): Schema =
        Analyzer.analyze(
                files.map { (name, text) -> Parser.parse(text, name).file!! },
                AnalysisOptions(),
            )
            .let {
                assertEquals(emptyList(), it.diagnostics.map { d -> "${d.code.id} ${d.message}" })
                it.schema!!
            }

    @Test
    fun `a reference that carries a single key does not tie two schemas`() {
        val schema =
            compile(
                "a.schemata" to "schema a\nimport b\nmodel A { #1 id uuid { id }  #2 b B? }",
                "b.schemata" to "schema b\nimport a\nmodel B { #1 id uuid { id }  #2 a A? }",
            )
        val units = ProtoPackages.of(schema.referencesByKey()).units
        assertEquals(listOf(listOf("a"), listOf("b")), units.map { u -> u.members.map { it.name } })
    }

    @Test
    fun `a reference through a composite key keeps the cycle`() {
        val schema =
            compile(
                "a.schemata" to
                    "schema a\nimport b\nmodel A { #1 x int32  #2 y int32  #3 b B?  @@id(x, y) }",
                "b.schemata" to
                    "schema b\nimport a\nmodel B { #1 x int32  #2 y int32  #3 a A?  @@id(x, y) }",
            )
        val units = ProtoPackages.of(schema.referencesByKey()).units
        assertEquals(listOf(listOf("a", "b")), units.map { u -> u.members.map { it.name } })
    }
}
