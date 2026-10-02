package io.schemata.core

import io.schemata.lang.Span
import io.schemata.lang.ast.ImportDecl

/**
 * Told about every name the resolver resolves, so a tool can map a reference site back to what it
 * names without repeating the lookup rules. A site is the span of one identifier, except for a
 * namespace prefix, whose site runs from its first segment to its last. Builtin types and names
 * that fail to resolve are not reported.
 */
interface ReferenceRecorder {
    /** [site] names the declaration [target]. */
    fun type(site: Span, target: IndexedDecl)

    /** [site] is the alias of [import], as in `cust.Customer`. */
    fun alias(site: Span, import: ImportDecl)

    /** [site] is the namespace prefix of a fully qualified name. */
    fun namespace(site: Span, namespace: String)
}
