package io.schemata.target.sql

import io.schemata.core.AnalysisOptions
import io.schemata.core.Analyzer
import io.schemata.core.annotations.AnnotationRegistry
import io.schemata.core.annotations.CoreAnnotations
import io.schemata.lang.Parser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SqlHelpTest {
    private fun diagnostics(text: String) =
        SqlTarget.lower(
                Analyzer.analyze(
                        listOf(Parser.parse(text, "t.schemata").file!!),
                        AnalysisOptions(
                            annotations =
                                AnnotationRegistry(CoreAnnotations.specs + SqlAnnotations.specs)
                        ),
                    )
                    .schema!!
            )
            .diagnostics

    @Test
    fun `lossy and strategy diagnostics carry help`() {
        val ds =
            diagnostics(
                """
                schema t

                model Item { #1 name string }

                model R {
                  #1 id      uuid                { id }
                  #2 tags    string[]            { max 3 }
                  #3 attrs   map<string, string>
                  #4 items   Item[]              { maxItems 5 } @sql(strategy: table)
                  #5 flag    bool                @sql(strategy: json)
                }
                """
                    .trimIndent()
            )
        val lossy = ds.filter { it.code == SqlCodes.LOSSY }
        val strategy = ds.filter { it.code == SqlCodes.STRATEGY_NOT_ALLOWED }
        assertTrue(
            lossy.isNotEmpty() && strategy.isNotEmpty(),
            ds.joinToString("\n") { it.message },
        )
        assertTrue(lossy.all { it.help != null }, lossy.joinToString("\n") { it.message })
        assertTrue(strategy.all { it.help != null }, strategy.joinToString("\n") { it.message })
        assertEquals(
            "use `@sql(strategy = table)` so the elements become rows with their own constraints",
            lossy.single { it.span.startLine == 7 }.help,
        )
        assertEquals(
            "use `@sql(strategy = table)` to lower the entries to a child table",
            lossy.single { it.span.startLine == 8 }.help,
        )
        assertEquals(
            "enforce the collection bound in application code; child tables carry no row-count constraints",
            lossy.single { it.span.startLine == 9 }.help,
        )
        assertEquals(
            "remove the strategy annotation",
            strategy.single { it.span.startLine == 10 }.help,
        )
    }

    @Test
    fun `the array lossy help fits whether the loss is the list's own bound or the element's`() {
        val ds =
            diagnostics(
                """
                schema t

                model R {
                  #1 id      uuid     { id }
                  #2 sized   string[] { maxItems 5 }
                  #3 bounded string[] { max 3 }
                }
                """
                    .trimIndent()
            )
        val lossy = ds.filter { it.code == SqlCodes.LOSSY }
        assertEquals(
            "enforce the list's size bound in application code; Postgres arrays carry no length constraint",
            lossy.single { it.span.startLine == 5 }.help,
        )
        assertEquals(
            "use `@sql(strategy = table)` so the elements become rows with their own constraints",
            lossy.single { it.span.startLine == 6 }.help,
        )
    }

    @Test
    fun `the jsonb help fits what the field's shape could actually do`() {
        val ds =
            diagnostics(
                """
                schema t

                model Card { #1 last4 string { max 4 } }

                union Payment = Card | uuid

                model R {
                  #1 id      uuid                { id }
                  #2 payment Payment             @sql(strategy: json)
                  #3 attrs   map<string, string>
                  #4 grid    list<int32[]>       @sql(strategy: json)
                }
                """
                    .trimIndent()
            )
        val lossy = ds.filter { it.code == SqlCodes.LOSSY }
        assertEquals(
            "remove `strategy = json` to get the default mapping for this field",
            lossy.single { it.span.startLine == 9 }.help,
        )
        assertEquals(
            "use `@sql(strategy = table)` to lower the entries to a child table",
            lossy.single { it.span.startLine == 10 }.help,
        )
        assertEquals(
            "keep jsonb; Postgres has no typed mapping for this shape",
            lossy.single { it.span.startLine == 11 }.help,
        )
    }

    @Test
    fun `a shape with no relational mapping points at json`() {
        val ds =
            diagnostics(
                """
                schema t

                model R { #1 id uuid { id }  #2 grid list<int32[]> }
                """
                    .trimIndent()
            )
        assertEquals(
            "add `@sql(strategy = json)` to store the field as jsonb",
            ds.single { it.code == SqlCodes.STRATEGY_NOT_ALLOWED }.help,
        )
    }

    @Test
    fun `an unused keyless record's message drops the mark-key-fields clause, kept in help`() {
        val ds =
            diagnostics(
                """
                schema t

                model Orphan { #1 name string }

                model R { #1 id uuid { id } }
                """
                    .trimIndent()
            )
        val diagnostic = ds.single { it.code == SqlCodes.MISSING_KEY }
        assertEquals(
            "record 'Orphan' has no primary key and is not used by any field",
            diagnostic.message,
        )
        assertEquals(
            "mark its key fields with `{ id }`, or the model with `@@id(a, b)`; a keyless model only lowers when a field embeds it",
            diagnostic.help,
        )
    }

    @Test
    fun `a recursive embed carries help`() {
        val ds =
            diagnostics(
                """
                schema t

                model A { #1 b B }

                model B { #1 a A }

                model R { #1 id uuid { id }  #2 a A }
                """
                    .trimIndent()
            )
        val diagnostic = ds.single { it.code == SqlCodes.RECURSIVE_EMBED }
        assertEquals(
            "use `@sql(strategy = json)` on this field, or give 'A' a key so it becomes a table",
            diagnostic.help,
        )
    }

    @Test
    fun `a relation name collision carries help`() {
        val ds =
            diagnostics(
                """
                schema t

                model RItems { #1 id uuid { id } }

                model Item { #1 n bool }

                model R { #1 id uuid { id }  #2 items Item[] @sql(strategy: table) }
                """
                    .trimIndent()
            )
        val diagnostic = ds.single { it.code == SqlCodes.NAME_COLLISION }
        assertEquals("rename one of them, or set `@sql(table = \"…\")` on one", diagnostic.help)
    }
}
