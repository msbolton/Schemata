package io.schemata.importer.xsd

import io.schemata.importer.ImportCodes
import io.schemata.importer.ImportInput
import io.schemata.importer.ImportResult
import io.schemata.importer.Importer
import io.schemata.importer.emitUnits
import io.schemata.lang.Diagnostic
import io.schemata.lang.Span

/**
 * Runs the reader, the lowering, the emitter, and the formatter over a set of `.xsd` inputs.
 * [locate] reads an `xs:import`/`xs:include` target that is not among [inputs], resolved against
 * the importing file's directory; it is called only when the target is not already present.
 */
object XsdImporter : Importer {
    override fun import(
        inputs: List<ImportInput>,
        namespace: String?,
        locate: (String) -> ImportInput?,
    ): ImportResult {
        val diagnostics = mutableListOf<Diagnostic>()
        val byPath = inputs.associateBy { it.path }
        val reader = Reader(diagnostics)

        val initial = inputs.mapNotNull { reader.read(it) }

        // A document another input includes is part of its includer, not a schema of its own:
        // each input is merged with what it includes, and an input that another input took in is
        // set aside. Two inputs that take each other in (an include cycle) keep the first.
        val visits = initial.map { mergeIncludes(it, byPath, locate, reader) }
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
                    val located = fetch(doc.path, imp.schemaLocation, byPath, locate, reader)
                    if (located != null) known += located
                }
            }
        }

        val lowered = XsdImport.lower(known, namespace)
        diagnostics += lowered.diagnostics

        val files = emitUnits(lowered.units)
        return ImportResult(files, diagnostics)
    }

    /**
     * Merges [doc]'s `xs:include`s (and the documents its `xs:redefine`s and `xs:override`s name)
     * into it, and those included documents' own includes in turn, until no new one appears,
     * returning the merged document and every path it took in (its own among them). That set of
     * visited paths also guards against a cycle: an include that resolves to an already-visited
     * path is skipped rather than merged again. An include that names no document, or one of
     * another namespace, is an error and is left out; one that names a document the reader could
     * not read is left out, the reader having reported why. An included document whose form
     * defaults differ from the including one's is noted: the merge keeps only the including
     * document's.
     */
    private fun mergeIncludes(
        doc: XsdDoc,
        byPath: Map<String, ImportInput>,
        locate: (String) -> ImportInput?,
        reader: Reader,
    ): Pair<XsdDoc, Set<String>> {
        val diagnostics = reader.diagnostics
        val visited = mutableSetOf(doc.path)
        var result = doc
        var frontier: List<Pair<String, String>> = doc.includes.map { doc.path to it }
        while (frontier.isNotEmpty()) {
            val next = mutableListOf<Pair<String, String>>()
            frontier.forEach { (basePath, include) ->
                val input = find(basePath, include, byPath, locate)
                if (input == null) {
                    diagnostics += includeError(basePath, "include '$include' cannot be resolved")
                    return@forEach
                }
                if (input.path in visited) return@forEach
                val includedDoc = reader.read(input) ?: return@forEach
                if (!visited.add(includedDoc.path)) return@forEach
                // A chameleon include has no namespace of its own: it takes the includer's,
                // references and all, so that `type="Foo"` written inside it means the includer's
                // Foo.
                val includerNamespace = result.targetNamespace
                val adopted =
                    if (includedDoc.targetNamespace == null && includerNamespace != null)
                        includedDoc.rebased(includerNamespace)
                    else includedDoc
                if (adopted.targetNamespace != includerNamespace) {
                    val declared =
                        adopted.targetNamespace?.let { "declares namespace '$it'" }
                            ?: "declares no namespace"
                    val expected =
                        includerNamespace?.let { "not '$it'" }
                            ?: "but the including document has no namespace"
                    diagnostics += includeError(basePath, "include '$include' $declared, $expected")
                    return@forEach
                }
                // The included document's components keep its own form defaults in XSD, but the
                // merged namespace has only the including document's.
                if (
                    XsdImport.elementForm(adopted) != XsdImport.elementForm(result) ||
                        XsdImport.attributeForm(adopted) != XsdImport.attributeForm(result)
                ) {
                    diagnostics +=
                        Diagnostic(
                            ImportCodes.APPROXIMATED,
                            "${adopted.path}: form defaults differ from the including document; " +
                                "dropped",
                            Span(adopted.path, 1, 1, 1, 1),
                            ImportCodes.helpFor(ImportCodes.APPROXIMATED),
                        )
                }
                result = merge(result, adopted)
                next += adopted.includes.map { adopted.path to it }
            }
            frontier = next
        }
        return result to visited
    }

    /** An include of [path] that cannot be merged; the include's own line is not kept. */
    private fun includeError(path: String, message: String) =
        Diagnostic(
            ImportCodes.UNRESOLVED,
            "$path: $message",
            Span(path, 1, 1, 1, 1),
            ImportCodes.helpFor(ImportCodes.UNRESOLVED),
        )

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
     * Resolves [relative] against [basePath]'s directory and reads it: an already-read input first,
     * else [locate].
     */
    private fun fetch(
        basePath: String,
        relative: String?,
        byPath: Map<String, ImportInput>,
        locate: (String) -> ImportInput?,
        reader: Reader,
    ): XsdDoc? = find(basePath, relative, byPath, locate)?.let { reader.read(it) }

    /** The input [relative] names from [basePath]'s directory, or `null` when there is none. */
    private fun find(
        basePath: String,
        relative: String?,
        byPath: Map<String, ImportInput>,
        locate: (String) -> ImportInput?,
    ): ImportInput? {
        if (relative == null) return null
        val resolved = resolvePath(basePath, relative)
        return byPath[resolved] ?: byPath[relative] ?: locate(resolved)
    }

    /**
     * Reads each document once, by path, however many inputs and includes name it, so a problem the
     * reader finds in it is reported once.
     */
    private class Reader(val diagnostics: MutableList<Diagnostic>) {
        private val read = mutableMapOf<String, XsdDoc?>()

        fun read(input: ImportInput): XsdDoc? {
            if (input.path in read) return read[input.path]
            val result = XsdReader.read(input.path, input.content)
            diagnostics += result.diagnostics
            read[input.path] = result.doc
            return result.doc
        }
    }

    private fun resolvePath(basePath: String, relative: String): String {
        val dir = basePath.substringBeforeLast('/', "")
        return if (dir.isEmpty()) relative else "$dir/$relative"
    }
}
