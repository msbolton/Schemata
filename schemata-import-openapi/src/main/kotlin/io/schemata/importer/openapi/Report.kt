package io.schemata.importer.openapi

import io.schemata.importer.ImportCodes
import io.schemata.lang.Diagnostic
import io.schemata.lang.DiagnosticCode
import io.schemata.lang.Span

/**
 * Collects an import's diagnostics. swagger-parser keeps no line numbers, so a message opens with
 * its file and the JSON pointer of its place (`shop.yaml: #/paths/~1orders/get: …`), or with the
 * file alone when it is about the whole document, and its span is the file's first character.
 */
class Report(private val diagnostics: MutableList<Diagnostic>) {
    fun at(
        doc: OpenApiDoc,
        pointer: Pointer?,
        code: DiagnosticCode,
        message: String,
        help: String = ImportCodes.helpFor(code),
    ) {
        diagnostics += located(doc.path, code, pointer?.let { "$it: $message" } ?: message, help)
    }

    /** A diagnostic already located, such as one the reader made for a referenced file. */
    fun add(diagnostic: Diagnostic) {
        diagnostics += diagnostic
    }
}

/** A diagnostic about [path] as a whole: `"<path>: <message>"`, spanning its first character. */
internal fun located(
    path: String,
    code: DiagnosticCode,
    message: String,
    help: String,
): Diagnostic = Diagnostic(code, "$path: $message", Span(path, 1, 1, 1, 1), help)
