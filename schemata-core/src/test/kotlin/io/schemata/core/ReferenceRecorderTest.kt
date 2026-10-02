package io.schemata.core

import io.schemata.lang.Parser
import io.schemata.lang.Span
import io.schemata.lang.ast.ImportDecl
import kotlin.test.Test
import kotlin.test.assertEquals

class ReferenceRecorderTest {
    private class Recording : ReferenceRecorder {
        val events = mutableListOf<String>()

        private fun at(site: Span) =
            "${site.file}:${site.startLine}:${site.startColumn}-${site.endColumn}"

        override fun type(site: Span, target: IndexedDecl) {
            events += "type ${at(site)} -> ${target.qualifiedName}"
        }

        override fun alias(site: Span, import: ImportDecl) {
            events += "alias ${at(site)} -> ${import.namespace}"
        }

        override fun namespace(site: Span, namespace: String) {
            events += "namespace ${at(site)} -> $namespace"
        }
    }

    private fun record(vararg sources: Pair<String, String>): List<String> {
        val recording = Recording()
        val files = sources.map { (path, text) -> Parser.parse(text, path).file!! }
        Analyzer.analyze(files, AnalysisOptions(references = recording))
        return recording.events.sorted()
    }

    private val customers =
        "c.schemata" to "namespace shop.customers\nrecord Customer { #1 id: uuid }"

    @Test
    fun `a bare name records the declaration it resolves to and builtins record nothing`() {
        val events =
            record(
                customers,
                "o.schemata" to
                    "namespace shop.orders\nimport shop.customers\n" +
                        "record Order { #1 who: Customer #2 n: int32 }",
            )
        assertEquals(listOf("type o.schemata:3:24-31 -> shop.customers.Customer"), events)
    }

    @Test
    fun `an aliased name records the alias and the declaration separately`() {
        val events =
            record(
                customers,
                "o.schemata" to
                    "namespace shop.orders\nimport shop.customers as cust\n" +
                        "record Order { #1 who: cust.Customer }",
            )
        assertEquals(
            listOf(
                "alias o.schemata:3:24-27 -> shop.customers",
                "type o.schemata:3:29-36 -> shop.customers.Customer",
            ),
            events,
        )
    }

    @Test
    fun `a fully qualified name records its namespace prefix as one site`() {
        val events =
            record(
                customers,
                "o.schemata" to
                    "namespace shop.orders\nimport shop.customers\n" +
                        "record Order { #1 who: shop.customers.Customer }",
            )
        assertEquals(
            listOf(
                "namespace o.schemata:3:24-37 -> shop.customers",
                "type o.schemata:3:39-46 -> shop.customers.Customer",
            ),
            events,
        )
    }

    @Test
    fun `each segment of a nested name records its own declaration`() {
        val events =
            record(
                "a.schemata" to
                    "namespace a\nrecord Outer { #1 x: int32\n  record Inner { #1 y: int32 } }\n" +
                        "record Use { #1 i: Outer.Inner }"
            )
        assertEquals(
            listOf(
                "type a.schemata:4:20-24 -> a.Outer",
                "type a.schemata:4:26-30 -> a.Outer.Inner",
            ),
            events,
        )
    }

    @Test
    fun `an alias target is recorded once however often the alias is used`() {
        val events =
            record(
                "a.schemata" to
                    "namespace a\nrecord R { #1 a: M #2 b: M }\nalias M = Money\n" +
                        "record Money { #1 v: int64 }"
            )
        assertEquals(
            listOf(
                "type a.schemata:2:18-18 -> a.M",
                "type a.schemata:2:26-26 -> a.M",
                "type a.schemata:3:11-15 -> a.Money",
            ),
            events,
        )
    }

    @Test
    fun `an unresolved name records nothing`() {
        val events = record("a.schemata" to "namespace a\nrecord R { #1 a: Missing }")
        assertEquals(emptyList(), events)
    }

    @Test
    fun `the alias of a name whose type does not resolve is still recorded`() {
        val events =
            record(
                customers,
                "o.schemata" to
                    "namespace shop.orders\nimport shop.customers as cust\n" +
                        "record Order { #1 who: cust.Missing }",
            )
        assertEquals(listOf("alias o.schemata:3:24-27 -> shop.customers"), events)
    }
}
