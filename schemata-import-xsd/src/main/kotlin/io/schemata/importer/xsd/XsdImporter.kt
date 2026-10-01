package io.schemata.importer.xsd

import io.schemata.lang.Diagnostic
import io.schemata.lang.format.FormatResult
import io.schemata.lang.format.Formatter

data class ImportInput(val path: String, val content: String)

/** [path] is `<namespace as path>.schemata`. */
data class ImportedFile(val path: String, val content: String)

data class ImportResult(val files: List<ImportedFile>, val diagnostics: List<Diagnostic>)

/**
 * Runs the reader, the lowering, the emitter, and the formatter over a set of `.xsd` inputs.
 * [locate] reads an `xs:import`/`xs:include` target that is not among [inputs], resolved against
 * the importing file's directory; it is called only when the target is not already present.
 */
object XsdImporter {
    fun import(
        inputs: List<ImportInput>,
        namespace: String? = null,
        locate: (String) -> ImportInput? = { null },
    ): ImportResult {
        val diagnostics = mutableListOf<Diagnostic>()
        val byPath = inputs.associateBy { it.path }

        val initial =
            inputs.mapNotNull { input ->
                val result = XsdReader.read(input.path, input.content)
                diagnostics += result.diagnostics
                result.doc
            }

        val merged = initial.map { mergeIncludes(it, byPath, locate, diagnostics) }

        val known = merged.toMutableList()
        merged.forEach { doc ->
            doc.imports.forEach { imp ->
                val ns = imp.namespace
                if (ns != null && known.none { it.targetNamespace == ns }) {
                    val located = fetch(doc.path, imp.schemaLocation, byPath, locate, diagnostics)
                    if (located != null) known += located
                }
            }
        }

        val lowered = XsdImport.lower(known, namespace)
        diagnostics += lowered.diagnostics

        val files =
            lowered.units.map { unit ->
                val text = SchemataEmitter.emit(unit)
                val path = unit.namespace.replace('.', '/') + ".schemata"
                val formatted = Formatter.format(text, path)
                check(formatted is FormatResult.Formatted) {
                    "the formatter rejected the emitted output for '${unit.namespace}': $formatted"
                }
                ImportedFile(path, formatted.text)
            }
        return ImportResult(files, diagnostics)
    }

    private fun mergeIncludes(
        doc: XsdDoc,
        byPath: Map<String, ImportInput>,
        locate: (String) -> ImportInput?,
        diagnostics: MutableList<Diagnostic>,
    ): XsdDoc {
        if (doc.includes.isEmpty()) return doc
        var result = doc
        doc.includes.forEach { include ->
            val includedDoc =
                fetch(doc.path, include, byPath, locate, diagnostics) ?: return@forEach
            result =
                result.copy(
                    complexTypes = result.complexTypes + includedDoc.complexTypes,
                    simpleTypes = result.simpleTypes + includedDoc.simpleTypes,
                    elements = result.elements + includedDoc.elements,
                    attributes = result.attributes + includedDoc.attributes,
                    groups = result.groups + includedDoc.groups,
                    attributeGroups = result.attributeGroups + includedDoc.attributeGroups,
                )
        }
        return result
    }

    /**
     * Resolves [relative] against [basePath]'s directory: an already-read input first, else
     * [locate].
     */
    private fun fetch(
        basePath: String,
        relative: String?,
        byPath: Map<String, ImportInput>,
        locate: (String) -> ImportInput?,
        diagnostics: MutableList<Diagnostic>,
    ): XsdDoc? {
        if (relative == null) return null
        val resolved = resolvePath(basePath, relative)
        val input = byPath[resolved] ?: byPath[relative] ?: locate(resolved) ?: return null
        val result = XsdReader.read(input.path, input.content)
        diagnostics += result.diagnostics
        return result.doc
    }

    private fun resolvePath(basePath: String, relative: String): String {
        val dir = basePath.substringBeforeLast('/', "")
        return if (dir.isEmpty()) relative else "$dir/$relative"
    }
}
