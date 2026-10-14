package io.schemata.importer

import io.schemata.lang.Diagnostic
import io.schemata.lang.Severity
import io.schemata.lang.Span
import io.schemata.lang.format.FormatResult
import io.schemata.lang.format.Formatter

/**
 * One input file. [relative] is its path relative to the root the command walked
 * (`corpus/orders.proto` under `out/proto`), or null for a file named on its own; importers that
 * take a namespace from a path read it, the XSD importer ignores it.
 */
data class ImportInput(val path: String, val content: String, val relative: String? = null)

/** [path] is `<namespace as path>.schemata`. */
data class ImportedFile(val path: String, val content: String)

data class ImportResult(val files: List<ImportedFile>, val diagnostics: List<Diagnostic>)

/**
 * A format read back into Schemata. [locate] reads a file the format refers to that is not among
 * [inputs] (an `xs:import` location, a proto `import` path under a root), already resolved to a
 * path; it returns null when no such file exists.
 */
interface Importer {
    fun import(
        inputs: List<ImportInput>,
        namespace: String? = null,
        locate: (String) -> ImportInput? = { null },
    ): ImportResult
}

/**
 * The result of an import: [units] emitted, or no files at all when [diagnostics] hold an error.
 * Nothing is written then, and a lowering that met an error is not promised to emit source the
 * formatter accepts. A unit the formatter refuses, such as one whose doc holds a control character
 * the source cannot carry, is an error naming the file it came from, and nothing is written either.
 */
fun importResult(units: List<SchemataUnit>, diagnostics: List<Diagnostic>): ImportResult {
    if (diagnostics.any { it.severity == Severity.ERROR })
        return ImportResult(emptyList(), diagnostics)
    val emitted = emitted(units)
    val refused =
        emitted.flatMap { (unit, _, result) ->
            (result as? FormatResult.Failed)?.diagnostics.orEmpty().map { problem ->
                Diagnostic(
                    ImportCodes.UNRESOLVED,
                    "${unit.sourcePath}: schema '${unit.namespace}' cannot be written: ${problem.message}",
                    Span(unit.sourcePath, 1, 1, 1, 1),
                    REFUSED_HELP,
                )
            }
        }
    if (refused.isNotEmpty()) return ImportResult(emptyList(), diagnostics + refused)
    return ImportResult(
        emitted.map { (_, path, result) ->
            ImportedFile(path, (result as FormatResult.Formatted).text)
        },
        diagnostics,
    )
}

private const val REFUSED_HELP =
    "change the text the message names in the document, then import again"

/**
 * Every unit as formatted source, one file per namespace, with references that would be ambiguous
 * written in full (see [qualifyAmbiguousRefs]). The formatter's rejection proves an emitter bug,
 * not a user error.
 */
fun emitUnits(units: List<SchemataUnit>): List<ImportedFile> =
    emitted(units).map { (unit, path, formatted) ->
        check(formatted is FormatResult.Formatted) {
            "the formatter rejected the emitted output for '${unit.namespace}': $formatted"
        }
        ImportedFile(path, formatted.text)
    }

/** Each unit, the path it is written to, and what the formatter made of its emitted source. */
private fun emitted(units: List<SchemataUnit>): List<Triple<SchemataUnit, String, FormatResult>> =
    qualifyAmbiguousRefs(units).map { unit ->
        val path = unit.namespace.replace('.', '/') + ".schemata"
        Triple(unit, path, Formatter.format(SchemataEmitter.emit(unit), path))
    }
