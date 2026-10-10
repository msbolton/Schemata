package io.schemata.importer.xsd

import io.schemata.importer.ImportCodes
import io.schemata.importer.ImportInput
import io.schemata.importer.ImportResult
import io.schemata.importer.Importer
import io.schemata.importer.importResult
import io.schemata.importer.resolvePath
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

        // Every document the inputs reach: a located document's own imports and includes are
        // followed
        // too, each against its own directory, until nothing new appears. A document is read once.
        val known = merged.toMutableList()
        val knownPaths = known.map { it.path }.toMutableSet()
        val pending = ArrayDeque(known)
        while (pending.isNotEmpty()) {
            val doc = pending.removeFirst()
            doc.imports.forEach { imp ->
                val ns = imp.namespace
                if (ns == XsdReader.XML || ns == XsdReader.XS) return@forEach
                if (known.any { it.targetNamespace == ns && ns != null }) return@forEach
                val input = find(doc.path, imp.schemaLocation, byPath, locate) ?: return@forEach
                if (input.path in knownPaths) return@forEach
                val located = reader.read(input) ?: return@forEach
                val (withIncludes, visited) = mergeIncludes(located, byPath, locate, reader)
                knownPaths += visited
                known += withIncludes
                pending += withIncludes
            }
        }
        // Once the closure is complete, an import that named no file, or a file that was not there,
        // still resolves when some document read declares its namespace; the rest are dropped with
        // a
        // warning and the document imports without them.
        val resolved =
            known.map { doc ->
                val missing =
                    doc.imports.filter { imp ->
                        val ns = imp.namespace
                        ns != XsdReader.XML &&
                            ns != XsdReader.XS &&
                            known.none { it.targetNamespace == ns }
                    }
                missing.forEach { imp ->
                    diagnostics +=
                        Diagnostic(
                            ImportCodes.DROPPED,
                            "${doc.path}: import '${imp.namespace ?: "(no namespace)"}' not found; dropped",
                            Span(doc.path, imp.line, 1, imp.line, 1),
                            "add the schema that declares it to the inputs",
                        )
                }
                doc.copy(
                    imports = doc.imports - missing.toSet(),
                    unresolvedImports = missing.map { it.namespace ?: "(no namespace)" },
                )
            }
        val lowered = XsdImport.lower(resolved, namespace)
        diagnostics += lowered.diagnostics

        return importResult(lowered.units, diagnostics)
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
            // The xml and xml schema namespaces are built in: their attributes and types resolve
            // without a file, so a document that declares one is not lowered to a schema.
            val doc =
                result.doc?.takeUnless { d ->
                    val ns = d.targetNamespace
                    (ns == XsdReader.XML || ns == XsdReader.XS).also { builtIn ->
                        if (builtIn)
                            diagnostics +=
                                Diagnostic(
                                    ImportCodes.DROPPED,
                                    "${d.path}: namespace '$ns' is built in; skipped",
                                    Span(d.path, 1, 1, 1, 1),
                                    ImportCodes.helpFor(ImportCodes.DROPPED),
                                )
                    }
                }
            read[input.path] = doc
            return doc
        }
    }
}
