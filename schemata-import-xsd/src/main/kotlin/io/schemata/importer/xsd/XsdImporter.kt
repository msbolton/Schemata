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

        // A document another input includes is part of its includer, not a schema of its own:
        // each input is merged with what it includes, and an input that another input took in is
        // set aside. Two inputs that take each other in (an include cycle) keep the first.
        val visits = initial.map { mergeIncludes(it, byPath, locate, diagnostics) }
        val merged =
            visits
                .filterIndexed { i, (doc, visited) ->
                    visits.withIndex().none { (j, other) ->
                        j != i &&
                            doc.path in other.second &&
                            (other.first.path !in visited || j < i)
                    }
                }
                .map { it.first }

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

    /**
     * Merges [doc]'s `xs:include`s into it, and those included documents' own includes in turn,
     * until no new one appears, returning the merged document and every path it took in (its own
     * among them). That set of visited paths also guards against a cycle: an include that resolves
     * to an already-visited path is skipped rather than merged again.
     */
    private fun mergeIncludes(
        doc: XsdDoc,
        byPath: Map<String, ImportInput>,
        locate: (String) -> ImportInput?,
        diagnostics: MutableList<Diagnostic>,
    ): Pair<XsdDoc, Set<String>> {
        val visited = mutableSetOf(doc.path)
        var result = doc
        var frontier: List<Pair<String, String>> = doc.includes.map { doc.path to it }
        while (frontier.isNotEmpty()) {
            val next = mutableListOf<Pair<String, String>>()
            frontier.forEach { (basePath, include) ->
                val includedDoc =
                    fetch(basePath, include, byPath, locate, diagnostics) ?: return@forEach
                if (!visited.add(includedDoc.path)) return@forEach
                result = merge(result, includedDoc)
                next += includedDoc.includes.map { includedDoc.path to it }
            }
            frontier = next
        }
        return result to visited
    }

    /** Folds [included]'s imports (deduplicated by namespace) and declarations into [into]. */
    private fun merge(into: XsdDoc, included: XsdDoc): XsdDoc =
        into.copy(
            imports = (into.imports + included.imports).distinctBy { it.namespace },
            complexTypes = into.complexTypes + included.complexTypes,
            simpleTypes = into.simpleTypes + included.simpleTypes,
            elements = into.elements + included.elements,
            attributes = into.attributes + included.attributes,
            groups = into.groups + included.groups,
            attributeGroups = into.attributeGroups + included.attributeGroups,
            dropped = into.dropped + included.dropped,
        )

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
