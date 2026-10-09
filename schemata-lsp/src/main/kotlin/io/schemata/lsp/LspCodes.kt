package io.schemata.lsp

import io.schemata.lang.Category
import io.schemata.lang.DiagnosticCode
import io.schemata.lang.Severity

/** What the language server itself reports about a file, apart from what the compiler finds. */
object LspCodes {
    /** The parser threw on a file; the editor shows it like a syntax error. */
    val PARSE_FAILED: DiagnosticCode =
        DiagnosticCode("SCH2801", Severity.ERROR, Category.SYNTAX, "the parser failed on a file")

    /** A file the set lists but the disk would not let the server read. */
    val UNREADABLE: DiagnosticCode =
        DiagnosticCode("SCH2802", Severity.ERROR, Category.SEMANTIC, "a file could not be read")

    /** The codes the appendix lists: one entry per id. */
    val all: List<DiagnosticCode> = listOf(PARSE_FAILED, UNREADABLE)
}
