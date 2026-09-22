package io.schemata.core

import io.schemata.core.annotations.AnnotationRegistry
import io.schemata.core.annotations.AnnotationSpec
import io.schemata.core.annotations.CoreAnnotations
import io.schemata.core.annotations.Element
import io.schemata.core.annotations.Role
import io.schemata.core.annotations.ValueKind
import io.schemata.core.ir.AnnotationValue.Flag
import io.schemata.core.ir.AnnotationValue.Name
import io.schemata.core.ir.AnnotationValue.Names
import io.schemata.core.ir.AnnotationValue.Str
import io.schemata.core.ir.Annotations
import io.schemata.core.ir.EnumType
import io.schemata.core.ir.RecordType
import io.schemata.lang.Parser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AnnotationsTest {
    private val registry =
        AnnotationRegistry(
            CoreAnnotations.specs +
                listOf(
                    AnnotationSpec(
                        "sql",
                        "schema",
                        setOf(Element.NAMESPACE),
                        ValueKind.STRING,
                        Role.NAME,
                    ),
                    AnnotationSpec(
                        "sql",
                        "key",
                        setOf(Element.FIELD),
                        ValueKind.FLAG,
                        Role.STRATEGY,
                    ),
                    AnnotationSpec(
                        "sql",
                        "key",
                        setOf(Element.RECORD),
                        ValueKind.NAME_TUPLE,
                        Role.STRATEGY,
                    ),
                    AnnotationSpec(
                        "sql",
                        "strategy",
                        setOf(Element.FIELD),
                        ValueKind.NAME,
                        Role.STRATEGY,
                        choices = setOf("embed", "table", "json"),
                    ),
                    AnnotationSpec(
                        "proto",
                        "name",
                        setOf(Element.RECORD, Element.FIELD, Element.ENUM_VALUE),
                        ValueKind.STRING,
                        Role.NAME,
                    ),
                )
        )

    private fun analyze(vararg sources: Pair<String, String>): AnalysisResult =
        Analyzer.analyze(
            sources.map { (path, src) -> Parser.parse(src, path).file!! },
            AnalysisOptions(annotations = registry),
        )

    private fun messages(r: AnalysisResult) =
        r.diagnostics.map {
            "${it.span.file}:${it.span.startLine}:${it.span.startColumn} ${it.message}"
        }

    @Test
    fun `validated annotations land on every element`() {
        val src =
            "@sql(schema = \"shop\")\n" +
                "namespace a\n" +
                "@sql(key = (tenant_id, id))\n" +
                "@proto(name = \"OrderV2\")\n" +
                "record Order {\n" +
                "  @sql(key, strategy = embed)\n" +
                "  @deprecated(\"use id2\")\n" +
                "  id: uuid\n" +
                "  @deprecated\n" +
                "  @proto(name = \"n\")\n" +
                "  name: string\n" +
                "}\n" +
                "@deprecated\n" +
                "enum E { @proto(name = \"X\") x }"
        val r = analyze("t.schemata" to src)
        assertEquals(emptyList(), messages(r))
        val ns = r.schema!!.namespaces.single()
        assertEquals(Annotations(mapOf("sql" to mapOf("schema" to Str("shop")))), ns.annotations)
        val order = ns.declarations.first { it.name == "Order" } as RecordType
        assertEquals(
            Annotations(
                mapOf(
                    "sql" to mapOf("key" to Names(listOf("tenant_id", "id"))),
                    "proto" to mapOf("name" to Str("OrderV2")),
                )
            ),
            order.annotations,
        )
        assertEquals(
            Annotations(
                mapOf(
                    "sql" to mapOf("key" to Flag, "strategy" to Name("embed")),
                    "" to mapOf("deprecated" to Str("use id2")),
                )
            ),
            order.fields[0].annotations,
        )
        assertEquals(
            Annotations(
                mapOf("" to mapOf("deprecated" to Flag), "proto" to mapOf("name" to Str("n")))
            ),
            order.fields[1].annotations,
        )
        val e = ns.declarations.first { it.name == "E" } as EnumType
        assertEquals(Annotations(mapOf("" to mapOf("deprecated" to Flag))), e.annotations)
        assertEquals(
            Annotations(mapOf("proto" to mapOf("name" to Str("X")))),
            e.values[0].annotations,
        )
    }

    @Test
    fun `unknown, misplaced, malformed, and repeated annotations are reported`() {
        val src =
            "namespace a\n" +
                "@sql(strategy = embed)\n" +
                "record R {\n" +
                "  @mongo(index)\n" +
                "  a: bool\n" +
                "  @sql(table = \"t\")\n" +
                "  b: bool\n" +
                "  @sql(strategy = blob)\n" +
                "  c: bool\n" +
                "  @sql(key = 1)\n" +
                "  d: bool\n" +
                "  @sql(key)\n" +
                "  @sql(key)\n" +
                "  e: bool\n" +
                "  @deprecated(1)\n" +
                "  f: bool\n" +
                "  @sql\n" +
                "  g: bool\n" +
                "  @sql(\"x\")\n" +
                "  h: bool\n" +
                "}"
        val r = analyze("t.schemata" to src)
        assertNull(r.schema)
        assertEquals(
            listOf(
                "t.schemata:2:6 @sql(strategy) is not allowed on a record; allowed on: field",
                "t.schemata:4:3 unknown annotation '@mongo'; known: deprecated, proto, sql",
                "t.schemata:6:8 'table' is not a key of @sql; keys: key, schema, strategy",
                "t.schemata:8:8 @sql(strategy) expects one of: embed, json, table",
                "t.schemata:10:8 @sql(key) takes no value",
                "t.schemata:13:8 @sql(key) is given more than once",
                "t.schemata:15:3 @deprecated expects a string",
                "t.schemata:17:3 @sql needs at least one key",
                "t.schemata:19:8 @sql arguments are a bare key or key = value",
            ),
            messages(r),
        )
    }

    @Test
    fun `namespace annotations are merged across files in path order`() {
        val r =
            analyze(
                "a.schemata" to "@sql(schema = \"x\")\nnamespace n\nrecord A { x: bool }",
                "b.schemata" to "@sql(schema = \"y\")\nnamespace n\nrecord B { x: bool }",
            )
        assertEquals(listOf("b.schemata:1:6 @sql(schema) is given more than once"), messages(r))
    }

    @Test
    fun `alias annotations are checked and then dropped`() {
        val clean =
            analyze(
                "t.schemata" to
                    "namespace a\n@deprecated(\"x\")\nalias A = string\nrecord R { x: A }"
            )
        assertEquals(emptyList(), messages(clean))
        val bad =
            analyze("t.schemata" to "namespace a\n@sql(key)\nalias A = string\nrecord R { x: A }")
        assertEquals(
            listOf(
                "t.schemata:2:6 @sql(key) is not allowed on an alias; allowed on: record, field"
            ),
            messages(bad),
        )
    }

    @Test
    fun `the default registry knows only core keys`() {
        val file =
            Parser.parse("namespace a\nrecord R {\n  @sql(key)\n  x: bool\n}", "t.schemata").file!!
        val r = Analyzer.analyze(listOf(file))
        assertNull(r.schema)
        assertEquals(
            listOf("t.schemata:3:3 unknown annotation '@sql'; known: deprecated"),
            messages(r),
        )
    }
}
