package io.schemata.core

import io.schemata.lang.Parser

/**
 * Parses [text] as the 2.0 surface and analyses it with core's own annotation keys. Core sits below
 * every target, so its tests never load a target's keys.
 */
fun analyze(text: String): AnalysisResult {
    val parsed = Parser.parse(text, "t.schemata")
    val file = parsed.file ?: error("does not parse: ${parsed.diagnostics}")
    return Analyzer.analyze(listOf(file))
}
