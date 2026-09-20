package io.schemata.lang

/**
 * A region of one source file. [file] is the path as the user gave it (normalized); lines and
 * columns are 1-based; the end is inclusive.
 */
data class Span(
    val file: String,
    val startLine: Int,
    val startColumn: Int,
    val endLine: Int,
    val endColumn: Int,
)
