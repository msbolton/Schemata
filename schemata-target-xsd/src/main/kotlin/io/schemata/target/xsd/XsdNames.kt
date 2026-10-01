package io.schemata.target.xsd

import io.schemata.core.ir.Namespace
import io.schemata.target.Names
import io.schemata.target.string

/** The XSD target's naming rules: `@xsd` overrides, type and element names, XML name checks. */
object XsdNames {
    private val ncName = Regex("[A-Za-z_][A-Za-z0-9_.\\-]*")
    private val scheme = Regex("[A-Za-z][A-Za-z0-9+.\\-]*:[^\\s#]+")

    fun namespaceOf(namespace: Namespace): String =
        namespace.annotations.string("xsd", "namespace") ?: "urn:schemata:${namespace.name}"

    fun pathOf(namespace: Namespace): String = namespace.name.replace('.', '/') + ".xsd"

    /** `Order` → `OrderType`; `Order.Line` → `OrderLineType`. */
    fun typeName(path: List<String>): String = path.joinToString("") + "Type"

    /** Global element and union-member element names: `BankTransfer` → `bank_transfer`. */
    fun elementName(declName: String): String = Names.snakeCase(declName)

    /**
     * The `xs:import schemaLocation` from the directory of [from] (a [pathOf] result) to [to]: no
     * prefix when they share a directory, else one `..` per directory [from] must climb before
     * descending to [to].
     */
    fun relativePath(from: String, to: String): String {
        val fromDirs = from.split('/').dropLast(1)
        val toParts = to.split('/')
        val toDirs = toParts.dropLast(1)
        var common = 0
        while (
            common < fromDirs.size && common < toDirs.size && fromDirs[common] == toDirs[common]
        ) {
            common++
        }
        val ups = List(fromDirs.size - common) { ".." }
        return (ups + toDirs.drop(common) + toParts.last()).joinToString("/")
    }

    fun isNCName(s: String): Boolean = ncName.matches(s)

    fun isAbsoluteUri(s: String): Boolean = scheme.matches(s)
}
