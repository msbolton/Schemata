package io.schemata.target.proto

import io.schemata.core.ir.Namespace
import io.schemata.target.Names
import io.schemata.target.string

/** The proto target's naming rules: packages, enum-value prefixes, and what proto can spell. */
object ProtoNames {
    private val identifier = Regex("[A-Za-z_][A-Za-z0-9_]*")

    /** `BankTransfer` → `bank_transfer`; a lowercase name is unchanged. */
    fun snakeCase(name: String): String = Names.snakeCase(name)

    /** `OrderStatus` → `ORDER_STATUS`. */
    fun upperSnake(name: String): String = snakeCase(name).uppercase()

    /** The raw `@proto(package)` text, else the namespace name. */
    fun packageOf(namespace: Namespace): String =
        namespace.annotations.string("proto", "package") ?: namespace.name

    /** `STATUS_PENDING`: the value upper-cased behind the proto enum name [enumName]. */
    fun valueName(enumName: String, value: String): String =
        "${upperSnake(enumName)}_${value.uppercase()}"

    fun zeroValue(enumName: String): String = "${upperSnake(enumName)}_UNSPECIFIED"

    /** What proto accepts as a name: a letter or underscore, then letters, digits, underscores. */
    fun isIdentifier(s: String): Boolean = identifier.matches(s)

    /** A package is dot-separated identifiers. */
    fun isPackage(s: String): Boolean = s.split('.').all { isIdentifier(it) }
}
