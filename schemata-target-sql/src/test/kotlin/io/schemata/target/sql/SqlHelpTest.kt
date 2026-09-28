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
                namespace t

                record Item { #1 name: string }

                record R {
                  @sql(key) #1 id: uuid
                  #2 tags: list<string(max = 3)>
                  #3 attrs: map<string, string>
                  @sql(strategy = table) #4 items: list<Item>(max = 5)
                  @sql(unique) #5 more: list<string>
                  @sql(strategy = embed) #6 numbers: list<int32>
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
            "move `@sql(unique)` to a field of the element record, or index the child table's columns",
            strategy.single { it.span.startLine == 10 }.help,
        )
    }

    @Test
    fun `the array lossy help fits whether the loss is the list's own bound or the element's`() {
        val ds =
            diagnostics(
                """
                namespace t

                record R {
                  @sql(key) #1 id: uuid
                  #2 sized: list<string>(max = 5)
                  #3 bounded: list<string(max = 3)>
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
                namespace t

                record Card { #1 last4: string(max = 4) }

                union Payment = Card | uuid

                record R {
                  @sql(key) #1 id: uuid
                  @sql(strategy = json) #2 payment: Payment
                  #3 attrs: map<string, string>
                  @sql(strategy = json) #4 grid: list<list<int32>>
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
                namespace t

                record R {
                  @sql(key) #1 id: uuid
                  #2 grid: list<list<int32>>
                }
                """
                    .trimIndent()
            )
        assertEquals(
            "add `@sql(strategy = json)` to store the field as jsonb",
            ds.single { it.code == SqlCodes.STRATEGY_NOT_ALLOWED }.help,
        )
    }
}
