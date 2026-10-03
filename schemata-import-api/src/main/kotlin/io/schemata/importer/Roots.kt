package io.schemata.importer

/**
 * Namespace names for formats whose files sit under roots, as `protoc` and Schemata's own output
 * arrange them.
 */
object Roots {
    /**
     * The namespace for [input]: its path under the root when it was found by walking one (what
     * Schemata wrote, and what protoc addresses), each segment lower-snaked when it is not already
     * a namespace segment; else [declared] (a proto package, a SQL schema) when that is already a
     * namespace name; else the file [stem], lower-snaked. The flag says the name was derived rather
     * than taken, which the caller reports.
     */
    fun namespaceFor(input: ImportInput, declared: String?, stem: String): Pair<String, Boolean> {
        input.relative?.let { rel ->
            val withoutExtension =
                rel.substringBeforeLast('.').takeIf { '/' !in rel.substringAfterLast('.') } ?: rel
            val segments = withoutExtension.split('/', '\\').filter { it.isNotEmpty() }
            val fixed =
                segments.map {
                    if (ImportNames.isNamespaceSegment(it)) it else ImportNames.lowerSnake(it)
                }
            return fixed.joinToString(".") to (fixed != segments)
        }
        if (declared != null && declared.split('.').all(ImportNames::isNamespaceSegment)) {
            return declared to false
        }
        return ImportNames.namespaceStem(stem) to true
    }
}
