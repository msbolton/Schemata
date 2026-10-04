package io.schemata.cli.diagnostics

import io.schemata.cli.Pipeline
import kotlin.test.Test
import kotlin.test.assertEquals

/** Every message and help across the fixtures follows the house style. */
class CatalogStyleTest {
    private fun all() =
        Fixture.all().flatMap { f ->
            val diagnostics =
                if (f.foreign.isNotEmpty()) f.importDiagnostics()
                else Pipeline.check(f.sources, f.targets, f.strict).diagnostics
            diagnostics.map { f.name to it }
        }

    @Test
    fun `messages start lower-case, carry no trailing period, and never name the severity or code`() {
        val bad =
            all().filter { (_, d) ->
                val m = d.message
                m.isEmpty() ||
                    m.first().isUpperCase() && !m.startsWith("@") ||
                    m.endsWith(".") ||
                    Regex("\\bSCH\\d{4}\\b").containsMatchIn(m) ||
                    Regex("^(error|warning)\\b").containsMatchIn(m)
            }
        assertEquals(emptyList(), bad.map { "${it.first}: ${it.second.message}" })
    }

    @Test
    fun `help is present, starts lower-case, and has no trailing period`() {
        val bad =
            all().filter { (_, d) ->
                val h = d.help
                val m = d.message
                h == null ||
                    h.isEmpty() ||
                    h.first().isUpperCase() ||
                    h.endsWith(".") ||
                    h == d.message ||
                    m.endsWith(h) ||
                    m.endsWith("; $h")
            }
        assertEquals(emptyList(), bad.map { "${it.first}: ${it.second.help}" })
    }
}
