package io.schemata.target

import io.schemata.core.AnalysisOptions
import io.schemata.core.Analyzer
import io.schemata.core.ir.Schema
import io.schemata.lang.Parser
import kotlin.test.Test
import kotlin.test.assertEquals

class NamespaceGraphTest {
    private fun compile(vararg files: Pair<String, String>): Schema {
        val analysis =
            Analyzer.analyze(
                files.map { (name, text) -> Parser.parse(text, name).file!! },
                AnalysisOptions(),
            )
        assertEquals(emptyList(), analysis.diagnostics.map { "${it.code.id} ${it.message}" })
        return analysis.schema!!
    }

    @Test
    fun `references follow fields nested declarations collections unions and payloads`() {
        val schema =
            compile(
                "a.schemata" to
                    """
                    schema a
                    import b
                    import c
                    import d
                    import e
                    import f
                    import g
                    import h

                    model Root {
                      #1 one B?
                      #2 many C[]
                      #3 by map<string, D>
                      model Inner { #1 deep E }
                    }
                    union Pick = F | Root
                    model Req { #1 x int32 }
                    service Svc { #1 call(G): H }
                    """,
                "b.schemata" to "schema b\nmodel B { #1 x int32 }",
                "c.schemata" to "schema c\nmodel C { #1 x int32 }",
                "d.schemata" to "schema d\nmodel D { #1 x int32 }",
                "e.schemata" to "schema e\nmodel E { #1 x int32 }",
                "f.schemata" to "schema f\nmodel F { #1 x int32 }",
                "g.schemata" to "schema g\nmodel G { #1 x int32 }",
                "h.schemata" to "schema h\nmodel H { #1 x int32 }",
            )
        assertEquals(
            setOf("b", "c", "d", "e", "f", "g", "h"),
            schema.namespaceReferences().getValue("a"),
        )
    }

    @Test
    fun `a back-reference and a namespace's own types add no edge`() {
        val schema =
            compile(
                "a.schemata" to
                    """
                    schema a
                    import b

                    model A { #1 id uuid { id }  #2 self A?  #3 b B? }
                    """,
                "b.schemata" to
                    """
                    schema b
                    import a

                    model B { #1 id uuid { id }  #2 a A[] @relation(b) }
                    """,
            )
        val references = schema.namespaceReferences()
        assertEquals(setOf("b"), references.getValue("a"))
        assertEquals(emptySet(), references.getValue("b"))
    }

    @Test
    fun `every namespace has an entry`() {
        val schema =
            compile(
                "a.schemata" to "schema a\nmodel A { #1 x int32 }",
                "b.schemata" to "schema b\nmodel B { #1 x int32 }",
            )
        assertEquals(mapOf("a" to emptySet(), "b" to emptySet()), schema.namespaceReferences())
    }

    @Test
    fun `components are sorted and ordered by their first member`() {
        val graph =
            mapOf("d" to setOf<String>(), "b" to setOf("a"), "a" to setOf("b"), "c" to setOf("a"))
        assertEquals(listOf(listOf("a", "b"), listOf("c"), listOf("d")), stronglyConnected(graph))
    }

    @Test
    fun `a loop through three namespaces is one component`() {
        val graph = mapOf("a" to setOf("b"), "b" to setOf("c"), "c" to setOf("a"))
        assertEquals(listOf(listOf("a", "b", "c")), stronglyConnected(graph))
    }
}
