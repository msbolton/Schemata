package io.schemata.target.jsonschema

import io.schemata.core.ir.Namespace
import io.schemata.target.Names
import io.schemata.target.string

/** The target's naming rules: paths, ids, `$defs` keys, union tags, and `@jsonschema` readers. */
object JsonSchemaNames {
    private val scheme = Regex("[A-Za-z][A-Za-z0-9+.\\-]*:[^\\s#]+")
    private const val REF_RESERVED = "/~#%?\"\\"

    fun pathOf(namespace: Namespace): String = namespace.name.replace('.', '/') + ".schema.json"

    fun idOf(namespace: Namespace): String =
        namespace.annotations.string("jsonschema", "id") ?: "urn:schemata:${namespace.name}"

    /** `["Order", "Line"]` → `Order.Line`; each segment is already its override when it has one. */
    fun defsKey(path: List<String>): String = path.joinToString(".")

    /** Union member tags: `BankTransfer` → `bank_transfer`. */
    fun tag(declName: String): String = Names.snakeCase(declName)

    /** A scheme, then no whitespace and no fragment: a `$id` must not carry one. */
    fun isAbsoluteUri(s: String): Boolean = scheme.matches(s)

    /**
     * The first character of [name] that a `$ref` pointer cannot carry verbatim (whitespace, a
     * control character, or one of `/ ~ # % ? " \`), or null when there is none.
     */
    fun reservedIn(name: String): Char? =
        name.firstOrNull { it.isWhitespace() || it < ' ' || it in REF_RESERVED }
}

/** A space as itself; any other whitespace or control character as a `\u` escape. */
fun shown(c: Char): String =
    if (c != ' ' && (c.isWhitespace() || c < ' ')) "\\u%04X".format(c.code) else c.toString()
