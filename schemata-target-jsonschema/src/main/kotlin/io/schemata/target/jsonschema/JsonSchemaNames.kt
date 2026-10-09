package io.schemata.target.jsonschema

import io.schemata.core.ir.Namespace
import io.schemata.target.string

/** The target's naming rules: paths, ids, `$defs` keys, and what a `$ref` can carry. */
object JsonSchemaNames {
    private val scheme = Regex("[A-Za-z][A-Za-z0-9+.\\-]*:[^\\s#]+")
    private const val REF_RESERVED = "/~#%?\"\\"

    fun pathOf(namespace: Namespace): String = namespace.name.replace('.', '/') + ".schema.json"

    fun idOf(namespace: Namespace): String =
        namespace.annotations.string("jsonschema", "id") ?: "urn:schemata:${namespace.name}"

    /** `["Order", "Line"]` → `Order.Line`; each segment is already its override when it has one. */
    fun defsKey(path: List<String>): String = path.joinToString(".")

    /** A scheme, then no whitespace and no fragment: a `$id` must not carry one. */
    fun isAbsoluteUri(s: String): Boolean = scheme.matches(s)

    /**
     * The first character of [name] that a `$ref` pointer cannot carry verbatim (whitespace, a
     * control character, or one of `/ ~ # % ? " \`), or null when there is none.
     */
    fun reservedIn(name: String): Char? =
        name.firstOrNull { it.isWhitespace() || it < ' ' || it in REF_RESERVED }
}

/**
 * A space as itself; a tab, line feed, or carriage return as `\t`, `\n`, or `\r`; any other
 * whitespace or control character as a `\u` escape.
 */
internal fun shown(c: Char): String =
    when {
        c == ' ' -> " "
        c == '\t' -> "\\t"
        c == '\n' -> "\\n"
        c == '\r' -> "\\r"
        c.isWhitespace() || c < ' ' -> "\\u" + c.code.toString(16).uppercase().padStart(4, '0')
        else -> c.toString()
    }
