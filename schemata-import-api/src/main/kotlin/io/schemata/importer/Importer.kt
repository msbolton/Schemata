package io.schemata.importer

import io.schemata.lang.Diagnostic
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
 * Every unit as formatted source, one file per namespace. The formatter proves the emitted text
 * parses; its rejection is an emitter bug, not a user error.
 */
fun emitUnits(units: List<SchemataUnit>): List<ImportedFile> =
    units.map { unit ->
        val text = SchemataEmitter.emit(unit)
        val path = unit.namespace.replace('.', '/') + ".schemata"
        val formatted = Formatter.format(text, path)
        check(formatted is FormatResult.Formatted) {
            "the formatter rejected the emitted output for '${unit.namespace}': $formatted"
        }
        ImportedFile(path, formatted.text)
    }
