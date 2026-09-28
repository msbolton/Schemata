package io.schemata.cli.report

import io.schemata.cli.Pipeline
import io.schemata.cli.PipelineResult
import io.schemata.cli.TargetResult
import io.schemata.core.CoreCodes
import io.schemata.lang.Diagnostic
import io.schemata.lang.Span
import io.schemata.target.OutputFile
import io.schemata.target.sql.SqlCodes
import io.schemata.testkit.Golden
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JsonRendererTest {
    private val report =
        Report.of(
            PipelineResult(
                core =
                    listOf(
                        Diagnostic(
                            CoreCodes.NULL_DEFAULT,
                            "a default may not be null",
                            Span("shop/orders.schemata", 4, 20, 4, 24),
                            help = "declare the field as `Status?`",
                        )
                    ),
                targets =
                    listOf(
                        TargetResult(
                            "proto",
                            listOf(OutputFile("shop/orders.proto", "")),
                            emptyList(),
                        ),
                        TargetResult(
                            "sql",
                            emptyList(),
                            listOf(
                                Diagnostic(
                                    SqlCodes.LOSSY,
                                    "quote \" and backslash \\ and newline\n",
                                    Span("shop/orders.schemata", 1, 1, 1, 2),
                                ),
                                Diagnostic(
                                    SqlCodes.MISSING_KEY,
                                    "no key",
                                    Span("shop/orders.schemata", 3, 8, 3, 13),
                                ),
                            ),
                        ),
                    ),
            ),
            strict = false,
            checkOnly = false,
        )

    @Test
    fun `report document matches the golden`() {
        Golden.assertMatches("report/report.json", JsonRenderer.report(report, out = "out"))
    }

    @Test
    fun `report document is valid JSON with the fields in order`() {
        val text = JsonRenderer.report(report, out = "out")
        val keys = Regex("\"([a-zA-Z]+)\":").findAll(text).map { it.groupValues[1] }.toList()
        assertEquals(
            listOf(
                "diagnostics",
                "code",
                "severity",
                "category",
                "promoted",
                "target",
                "message",
                "help",
                "span",
            ),
            keys.take(9),
        )
        assertEquals("exitCode", keys.last())
        assertTrue(
            keys.indexOf("written") < keys.indexOf("skipped") &&
                keys.indexOf("skipped") < keys.lastIndex
        )
        MiniJson.parse(text)
    }

    @Test
    fun `string escaping covers quotes, backslashes, and control characters`() {
        assertEquals(
            "\"quote \\\" and backslash \\\\ and newline\\n\"",
            Json.string("quote \" and backslash \\ and newline\n"),
        )
        assertEquals("\"tab\\t bell\\u0007\"", Json.string("tab\t bell\u0007"))
    }

    @Test
    fun `targets document matches the golden`() {
        Golden.assertMatches("report/targets.json", JsonRenderer.targets(Pipeline.targets))
    }
}

/**
 * A recursive-descent validator: accepts exactly RFC 8259 objects, arrays, strings, numbers, true,
 * false, null; throws on anything else. Values are discarded.
 */
object MiniJson {
    fun parse(text: String) {
        val p = P(text)
        p.ws()
        p.value()
        p.ws()
        check(p.i == text.length) { "trailing content at ${p.i}" }
    }

    private class P(val s: String) {
        var i = 0

        fun ws() {
            while (i < s.length && s[i] in " \n\r\t") i++
        }

        fun value() {
            when {
                i >= s.length -> error("unexpected end")
                s[i] == '{' -> obj()
                s[i] == '[' -> arr()
                s[i] == '"' -> str()
                s.startsWith("true", i) -> i += 4
                s.startsWith("false", i) -> i += 5
                s.startsWith("null", i) -> i += 4
                else -> num()
            }
        }

        fun obj() {
            i++
            ws()
            if (s[i] == '}') {
                i++
                return
            }
            while (true) {
                ws()
                str()
                ws()
                expect(':')
                ws()
                value()
                ws()
                if (s[i] == ',') {
                    i++
                    continue
                }
                expect('}')
                return
            }
        }

        fun arr() {
            i++
            ws()
            if (s[i] == ']') {
                i++
                return
            }
            while (true) {
                ws()
                value()
                ws()
                if (s[i] == ',') {
                    i++
                    continue
                }
                expect(']')
                return
            }
        }

        fun str() {
            expect('"')
            while (s[i] != '"') {
                if (s[i] == '\\') {
                    i++
                    if (s[i] == 'u') {
                        repeat(4) {
                            i++
                            check(s[i].isLetterOrDigit())
                        }
                    } else check(s[i] in "\"\\/bfnrt") { "bad escape at $i" }
                } else check(s[i] >= ' ') { "control character at $i" }
                i++
            }
            i++
        }

        fun num() {
            val start = i
            while (i < s.length && s[i] in "-+.eE0123456789") i++
            check(i > start) { "expected a value at $start" }
            s.substring(start, i).toDouble()
        }

        fun expect(c: Char) {
            check(i < s.length && s[i] == c) { "expected '$c' at $i" }
            i++
        }
    }
}
