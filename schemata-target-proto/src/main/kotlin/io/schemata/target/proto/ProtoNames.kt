package io.schemata.target.proto

import io.schemata.core.ir.AnnotationValue
import io.schemata.core.ir.Annotations
import io.schemata.core.ir.EnumValue
import io.schemata.core.ir.Field
import io.schemata.core.ir.Namespace
import io.schemata.core.ir.TypeDecl

/** The proto target's naming rules: `@proto` overrides, enum-value prefixes, deprecation. */
object ProtoNames {
    private val boundary = Regex("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])")
    private val identifier = Regex("[A-Za-z_][A-Za-z0-9_]*")

    /** `BankTransfer` → `bank_transfer`; a lowercase name is unchanged. */
    fun snakeCase(name: String): String = name.split(boundary).joinToString("_").lowercase()

    /** `OrderStatus` → `ORDER_STATUS`. */
    fun upperSnake(name: String): String = snakeCase(name).uppercase()

    fun packageOf(namespace: Namespace): String =
        override(namespace.annotations, "package") ?: namespace.name

    fun of(decl: TypeDecl): String = override(decl.annotations, "name") ?: decl.name

    fun of(field: Field): String = override(field.annotations, "name") ?: field.name

    /** `STATUS_PENDING`, or the `@proto(name)` verbatim. [enumName] is the proto enum name. */
    fun of(enumName: String, value: EnumValue): String =
        override(value.annotations, "name") ?: valueName(enumName, value.name)

    fun valueName(enumName: String, value: String): String =
        "${upperSnake(enumName)}_${value.uppercase()}"

    fun zeroValue(enumName: String): String = "${upperSnake(enumName)}_UNSPECIFIED"

    fun deprecated(annotations: Annotations): Boolean = "deprecated" in annotations[""]

    /** What proto accepts as a name: a letter or underscore, then letters, digits, underscores. */
    fun isIdentifier(s: String): Boolean = identifier.matches(s)

    /** A package is dot-separated identifiers. */
    fun isPackage(s: String): Boolean = s.split('.').all { isIdentifier(it) }

    /** The raw `@proto(<key>)` text, before any check that it is spellable in proto. */
    fun override(annotations: Annotations, key: String): String? =
        (annotations["proto"][key] as? AnnotationValue.Str)?.value
}
