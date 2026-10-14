package io.schemata.importer.openapi

import com.fasterxml.jackson.databind.JsonNode
import io.schemata.importer.ImportCodes
import io.schemata.importer.ImportInput
import io.schemata.importer.resolvePath
import java.net.URLDecoder

/**
 * Follows `$ref`s for an import. A reference inside a document (`#/components/schemas/Money`)
 * resolves in its tree. A reference to a file (`common.yaml#/components/schemas/Money`, or
 * `common.yaml` for the whole file) resolves beside the referring document: among the inputs,
 * otherwise through [locate], reading the file once with the referring document's version. A
 * reference with a URL scheme (`https:`, `file:`) is refused and never fetched, as an XSD import
 * reads only local files.
 *
 * `at` is always the pointer of the object that holds the `$ref`. A reference that fails is
 * reported once for each place it stands, however often the import asks for it.
 */
class Refs(
    docs: Map<String, OpenApiDoc>,
    private val locate: (String) -> ImportInput?,
    private val report: Report,
) {
    /**
     * The component a `$ref` names: its document, its pointer, and its component name when it is
     * under #/components/<section>/.
     */
    data class Target(
        val doc: OpenApiDoc,
        val pointer: Pointer,
        val section: String?,
        val name: String?,
        val node: JsonNode,
    )

    private val docs: Map<String, OpenApiDoc> = docs.mapKeys { slashed(it.key) }

    /** Files read for a reference, by path; null for one that could not be read. */
    private val read = HashMap<String, OpenApiDoc?>()

    private val failed = HashSet<Triple<String, String, String>>()

    /** The target [ref] names, or null after reporting why it has none. */
    fun resolve(from: OpenApiDoc, at: Pointer, ref: String): Target? {
        val key = Triple(from.path, at.text, ref)
        if (key in failed) return null
        return find(from, at, ref, quiet = false).also { if (it == null) failed += key }
    }

    /**
     * The target [ref] names, or null, reporting nothing about the reference itself: for a value
     * that may name a schema but need not, such as a discriminator mapping's. A file it reads is
     * still read once, and that file's own messages are reported.
     */
    fun peek(from: OpenApiDoc, ref: String): Target? = find(from, Pointer.ROOT, ref, quiet = true)

    /**
     * Resolves every `$ref` in [doc], so a reference that cannot be resolved is reported even where
     * nothing else of the document is imported. A `$ref` under `example`, `examples`, `default`,
     * `enum` or `const`, or under an `x-` extension, is data, not a reference, so those values are
     * not searched. The entries of a map of names, such as `properties`, `paths` or a `components`
     * section, are searched whatever they are called.
     */
    fun checkAll(doc: OpenApiDoc) {
        fun walk(node: JsonNode, at: Pointer, names: Boolean) {
            if (node.isArray) {
                node.forEachIndexed { i, child -> walk(child, at / i, names = false) }
                return
            }
            if (!node.isObject) return
            if (!names)
                node.get(REF)?.takeIf { it.isTextual }?.let { resolve(doc, at, it.asText()) }
            for ((key, child) in node.properties()) {
                if (!names && (key == REF || key in LITERALS || key.startsWith("x-"))) continue
                walk(child, at / key, names = !names && key in NAMED)
            }
        }
        walk(doc.tree, Pointer.ROOT, names = false)
    }

    private fun find(from: OpenApiDoc, at: Pointer, ref: String, quiet: Boolean): Target? {
        if (URL.containsMatchIn(ref)) {
            if (!quiet)
                report.at(
                    from,
                    at,
                    ImportCodes.UNRESOLVED,
                    "reference '$ref' is a URL and is not fetched",
                    URL_HELP,
                )
            return null
        }
        val file = percentDecoded(ref.substringBefore('#'))
        val fragment = percentDecoded(ref.substringAfter('#', ""))
        if (fragment.isNotEmpty() && !fragment.startsWith("/")) {
            if (!quiet)
                report.at(
                    from,
                    at,
                    ImportCodes.UNRESOLVED,
                    "reference '$ref' is not a JSON pointer",
                    POINTER_HELP,
                )
            return null
        }
        val doc =
            if (file.isEmpty()) from
            else
                document(from, at, ref, resolvePath(slashed(from.path), file), quiet) ?: return null
        val node = doc.tree.at(fragment)
        if (node.isMissingNode) {
            if (!quiet)
                report.at(
                    from,
                    at,
                    ImportCodes.UNRESOLVED,
                    "reference '$ref' names nothing in ${doc.path}",
                    MISSING_HELP,
                )
            return null
        }
        val pointer = Pointer("#$fragment")
        val segments = pointer.segments
        val component = segments.size == 3 && segments[0] == "components"
        return Target(
            doc,
            pointer,
            if (component) segments[1] else null,
            if (component) segments[2] else null,
            node,
        )
    }

    /**
     * The document at [path]: an input, or a file read once through [locate]. A file that does not
     * exist is reported at each reference to it. A file that exists but cannot be read is reported
     * once, by the reader's own diagnostics.
     */
    private fun document(
        from: OpenApiDoc,
        at: Pointer,
        ref: String,
        path: String,
        quiet: Boolean,
    ): OpenApiDoc? {
        docs[path]?.let {
            return it
        }
        if (path in read) return read[path]
        val input = locate(path)
        if (input == null) {
            if (!quiet)
                report.at(
                    from,
                    at,
                    ImportCodes.UNRESOLVED,
                    "reference '$ref' names the file $path, which does not exist",
                    FILE_HELP,
                )
            return null
        }
        val doc =
            when (val outcome = OpenApiReader.read(input, fragmentVersion = from.version)) {
                is ReadOutcome.Read -> {
                    outcome.warnings.forEach(report::add)
                    outcome.doc
                }
                is ReadOutcome.Unreadable -> {
                    outcome.diagnostics.forEach(report::add)
                    null
                }
            }
        read[path] = doc
        return doc
    }

    private companion object {
        const val REF = "\$ref"
        const val URL_HELP =
            "copy the referenced file beside the document and refer to it by its relative path"
        const val POINTER_HELP =
            "write the fragment as a JSON pointer, such as #/components/schemas/Order"
        const val MISSING_HELP = "fix the reference, or add what it names"
        const val FILE_HELP = "add the file beside the document, or fix the reference"

        /** A URI scheme of two or more letters, so a Windows drive (`C:`) still reads as a path. */
        val URL = Regex("^[A-Za-z][A-Za-z0-9+.-]+:")

        val LITERALS = setOf("example", "examples", "default", "enum", "const")

        val NAMED =
            setOf(
                "properties",
                "patternProperties",
                "\$defs",
                "definitions",
                "dependentSchemas",
                "paths",
                "webhooks",
                "schemas",
                "responses",
                "parameters",
                "requestBodies",
                "headers",
                "securitySchemes",
                "links",
                "callbacks",
                "pathItems",
                "content",
                "encoding",
                "variables",
            )

        fun slashed(path: String): String = path.replace('\\', '/')

        /** A reference is a URI, so `%7B` in it is `{`; text that is not valid escaping stays. */
        fun percentDecoded(text: String): String =
            if ('%' !in text) text
            else
                try {
                    URLDecoder.decode(text.replace("+", "%2B"), Charsets.UTF_8)
                } catch (e: IllegalArgumentException) {
                    text
                }
    }
}
