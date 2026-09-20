package io.schemata.lang

/** A region of source text. Lines and columns are 1-based; the end is inclusive. */
data class Span(val startLine: Int, val startColumn: Int, val endLine: Int, val endColumn: Int)
